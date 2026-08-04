package io.pjacoco.e2emm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-MM-012 (design spec &sect;4.5 MM-E2E-1; CI wiring is Task 10): Boot 3.3 +
 * {@code micrometer-tracing-bridge-brave} + B3, {@code traceKeyAutoCreate=true} &mdash; verifies the
 * tracer path (sync + async) actually works end-to-end through the real {@code -javaagent}, turning
 * the 2026-08-03 spike's S2/S3 findings (scratchpad {@code mm-spike/spike-results.md}, uploaded to
 * Drive/Evernote) into a regression test.
 */
class MmTracerPathE2E {

    private static final Pattern TOMCAT_PORT_LOG = Pattern.compile("Tomcat started on port\\D*(\\d+)");
    private static final Pattern REQUEST_TRACE_ID = Pattern.compile("requestTraceId=([0-9a-f]+)");

    @Test
    @DisplayName("REQ-MM-012: fixed b3 header traceId becomes the store key and carries SyncWorker coverage")
    void b3TraceIdKeyedStore() throws Exception {
        // Spike S3 vector verbatim (spike-results.md): this exact b3 header made
        // "80f198ee56343ba864fe8b2a57d3eff7.exec" appear, traceId taken as-is as the store key.
        String traceId = "80f198ee56343ba864fe8b2a57d3eff7";
        String b3Header = traceId + "-e457b5a2e4d86bd1-1";

        File log = Files.createTempFile("mm-tracer-b3", ".log").toFile();
        Path destDir = Files.createTempDirectory("mm-tracer-b3-dest-");
        Process app = startApp(log, destDir);
        try {
            int port = awaitPortFromLog(log, 60);
            HttpResponse<String> response = get("http://127.0.0.1:" + port + "/sync", "b3", b3Header);
            assertEquals(200, response.statusCode(), "app must respond 200 on /sync");

            shutdownGracefully(app);

            Path exec = destDir.resolve(traceId + ".exec");
            assertTrue(Files.exists(exec),
                    "b3 traceId-keyed exec must exist after SIGTERM flush (traceKeyAutoCreate store): " + exec);
            assertAnyProbeCovered(exec, "spike/SyncWorker");
        } finally {
            terminate(app);
            deleteRecursively(destDir);
            log.delete();
        }
    }

    @Test
    @DisplayName("REQ-MM-012: async work's executed lines all attribute to the same request traceId store")
    void asyncAttributedToSameStore() throws Exception {
        File log = Files.createTempFile("mm-tracer-async", ".log").toFile();
        Path destDir = Files.createTempDirectory("mm-tracer-async-dest-");
        Process app = startApp(log, destDir);
        try {
            int port = awaitPortFromLog(log, 60);
            // No inbound b3 header needed here: management.tracing.sampling.probability=1.0 means
            // Brave mints its own traceId for the request, and SpikeController#async echoes it in the
            // response body once the async work has completed (asyncWorker.work(10).join()) — so the
            // exec filename is read back from the response instead of being pre-supplied like above.
            HttpResponse<String> response = get("http://127.0.0.1:" + port + "/async");
            assertEquals(200, response.statusCode(), "app must respond 200 on /async");
            String requestTraceId = extractTraceId(response.body());

            shutdownGracefully(app);

            Path exec = destDir.resolve(requestTraceId + ".exec");
            assertTrue(Files.exists(exec),
                    "async request's own traceId-keyed exec must exist after SIGTERM flush: " + exec);
            assertWorkMethodLinesCovered(exec);
        } finally {
            terminate(app);
            deleteRecursively(destDir);
            log.delete();
        }
    }

    // ================= app lifecycle =================

    private static Process startApp(File log, Path destDir) throws IOException {
        return new ProcessBuilder(
                javaBin(),
                "-javaagent:" + agentJar() + "=traceKeyAutoCreate=true,destdir=" + destDir + ",port=0",
                "-jar", bootJar(),
                "--server.port=0")
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
    }

    /**
     * SIGTERM ({@link Process#destroy()} on POSIX) so the agent's shutdown hook runs
     * {@code TestStoreRegistry#dumpRemainingAsPartial} and flushes the trace-keyed store to disk;
     * {@link Process#destroyForcibly()} sends SIGKILL and would skip the hook entirely, so no exec
     * would ever be written &mdash; the whole point of this E2E is that graceful shutdown flush.
     */
    private static void shutdownGracefully(Process app) throws InterruptedException {
        app.destroy();
        boolean exited = app.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            fail("app did not exit within 20s of SIGTERM (shutdown-hook flush may be stuck)");
        }
    }

    /** Teardown safety net for every exit path (assertion failure, exception, or the timeout above):
     *  force-kill only if the graceful shutdown did not already finish it (idempotent otherwise). */
    private static void terminate(Process app) throws InterruptedException {
        if (app.isAlive()) {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort scratch cleanup; nothing actionable if the OS is still holding a handle
                }
            });
        }
    }

    // ================= HTTP =================

    private static HttpResponse<String> get(String url, String... headerNameValue)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        for (int i = 0; i + 1 < headerNameValue.length; i += 2) {
            builder.header(headerNameValue[i], headerNameValue[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extractTraceId(String body) {
        Matcher matcher = REQUEST_TRACE_ID.matcher(body);
        if (!matcher.find()) {
            fail("response body did not contain requestTraceId=<hex>: " + body);
        }
        return matcher.group(1);
    }

    // ================= boot log polling (duplicated from MmBoot3BootE2E — Task 1's noted E2E-harness
    // dedup debt is still open; not addressed by this task) =================

    private static int awaitPortFromLog(File log, int timeoutSeconds) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String content = Files.readString(log.toPath());
            Matcher matcher = TOMCAT_PORT_LOG.matcher(content);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            if (content.contains("APPLICATION FAILED TO START") || content.contains("Error starting ApplicationContext")) {
                fail("app failed to start within " + timeoutSeconds + "s:\n" + content);
            }
            Thread.sleep(200);
        }
        fail("app did not log a Tomcat startup line within " + timeoutSeconds + "s:\n" + Files.readString(log.toPath()));
        throw new AssertionError("unreachable");
    }

    private static String javaBin() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private static String agentJar() {
        String path = System.getProperty("pjacoco.agentJar");
        if (path == null || path.isBlank()) {
            fail("pjacoco.agentJar system property not set (expected from build.gradle.kts test task)");
        }
        File jar = new File(path);
        if (!jar.isFile()) {
            fail("agent jar not found at " + jar.getAbsolutePath()
                    + " -- run `./gradlew --no-daemon :agent:shadowJar` from the repo root first");
        }
        return jar.getAbsolutePath();
    }

    private static String bootJar() {
        String path = System.getProperty("e2emm.bootJar");
        if (path == null || path.isBlank()) {
            fail("e2emm.bootJar system property not set (expected from build.gradle.kts test task)");
        }
        File jar = new File(path);
        if (!jar.isFile()) {
            fail("boot jar not found at " + jar.getAbsolutePath() + " -- the bootJar task should have built it");
        }
        return jar.getAbsolutePath();
    }

    // ================= coverage assertions =================

    /** Simple probe-fired check via jacoco's {@link ExecutionDataReader} — mirrors the agent module's
     *  own {@code MmBaggageHeadersE2E#assertCovered} pattern (any probe set = the class actually ran
     *  under this store). */
    private static void assertAnyProbeCovered(Path exec, String vmName) throws Exception {
        ExecutionDataStore eds = readExec(exec);
        ExecutionData target = null;
        for (ExecutionData candidate : eds.getContents()) {
            if (vmName.equals(candidate.getName())) {
                target = candidate;
                break;
            }
        }
        assertNotNull(target, "exec must contain ExecutionData for " + vmName + ": " + exec);
        boolean anySet = false;
        for (boolean p : target.getProbes()) {
            if (p) {
                anySet = true;
                break;
            }
        }
        assertTrue(anySet, vmName + " must have at least one fired probe in " + exec);
    }

    /**
     * REQ-MM-012 / design spec &sect;4.5 MM-E2E-1: every executable line of {@code AsyncWorker#work}
     * must be covered in the async request's own traceId store.
     *
     * <p>Scoped to the {@code work} method (via JaCoCo's {@link IMethodCoverage} line range) rather
     * than the whole class, because the class also contains the constructor — the design spec's
     * &sect;3 S2 row records the line-mapping re-verification that settled this: the constructor
     * (lines 15-17) runs during Spring bean creation, OUTSIDE any trace's activation window, so it is
     * CORRECTLY absent from every per-trace store (the original spike's "9 of 11" was a probe-coverage
     * ratio, not an attribution failure) — asserting it here would assert the wrong thing.
     *
     * <p>Lines with {@link ICounter#EMPTY} (no probe — annotations, braces, blank lines) are skipped.
     * Every remaining line in {@code work()} must be at least {@link ICounter#PARTLY_COVERED}: the
     * method's one conditional expression (the null-guard ternary for {@code currentSpan}) always
     * takes its true branch under an active trace, which JaCoCo reports as PARTLY_COVERED rather than
     * FULLY_COVERED (the false branch's bytecode never executes) — still "the line executed", which is
     * the property this assertion is checking, not full branch coverage.
     */
    private static void assertWorkMethodLinesCovered(Path exec) throws Exception {
        String vmName = "spike/AsyncWorker";
        String fqcn = "spike.AsyncWorker";
        byte[] classBytes = readClassBytes(vmName);
        ExecutionDataStore eds = readExec(exec);
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(eds, builder).analyzeClass(classBytes, fqcn);

        IClassCoverage classCoverage = null;
        for (IClassCoverage c : builder.getClasses()) {
            classCoverage = c;   // exactly one class was analyzed above
            break;
        }
        assertNotNull(classCoverage, "exec must contain class coverage for " + fqcn + ": " + exec);

        IMethodCoverage workMethod = null;
        for (IMethodCoverage m : classCoverage.getMethods()) {
            if ("work".equals(m.getName())) {
                workMethod = m;
                break;
            }
        }
        assertNotNull(workMethod, "AsyncWorker must declare a work() method for coverage analysis");

        boolean anyExecutableLine = false;
        for (int line = workMethod.getFirstLine(); line <= workMethod.getLastLine(); line++) {
            ILine coverageLine = classCoverage.getLine(line);
            if (coverageLine.getStatus() == ICounter.EMPTY) {
                continue;
            }
            anyExecutableLine = true;
            assertNotEquals(ICounter.NOT_COVERED, coverageLine.getStatus(),
                    "AsyncWorker.work() line " + line + " must be covered in the async request's traceId "
                            + "store (spike S2: a context-propagating executor decorator makes 100% of "
                            + "work()'s executed lines attribute to the request's trace): " + exec);
        }
        assertTrue(anyExecutableLine, "work() must have at least one executable line to assert over");
    }

    private static byte[] readClassBytes(String vmName) throws IOException {
        try (InputStream in = MmTracerPathE2E.class.getResourceAsStream("/" + vmName + ".class")) {
            assertNotNull(in, "class resource not found on test classpath: /" + vmName
                    + ".class (expected via e2e-mm-boot3's main sourceSet output)");
            return in.readAllBytes();
        }
    }

    private static ExecutionDataStore readExec(Path exec) throws Exception {
        ExecutionDataStore eds = new ExecutionDataStore();
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(new SessionInfoStore());
            r.read();
        }
        return eds;
    }
}
