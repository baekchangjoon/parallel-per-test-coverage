package io.pjacoco.e2emm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.pjacoco.testkit.HeaderStyle;
import io.pjacoco.testkit.Pjacoco;
import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-MM-013 (design spec &sect;4.5 MM-E2E-3, &sect;4.6; REQ-MM-008 E2E cross-reference): drives two
 * child JVMs of the same Boot 3 spike app (instance A, instance B &mdash; no Docker, per the plan's
 * process-based backprop correction) through the real {@code -javaagent}, with A's own
 * Micrometer/Brave tracer propagating B3 + the {@code test.id} baggage field from A's inbound request
 * to its downstream call on B.
 *
 * <p><strong>Deviation from the task brief's literal procedure (documented per the process
 * instructions: investigate systematically, fix the test rather than weaken assertions):</strong> the
 * brief describes an explicit {@code POST A:/test/start?testId=D1} strict-mode boundary. Empirically
 * (and per design spec &sect;4.6, "트레이서-활성 앱에서의 test.id (S5 귀결 문서화)") that does NOT
 * work on this SUT: both A and B have an always-on Brave tracer ({@code sampling.probability=1.0}), and
 * {@code ServletAdvice.activate()} tries tracer sources (Brave/OTel) BEFORE the baggage/field-header
 * fallback &mdash; on a tracer-active app the fallback is unreachable and the coverage KEY is always
 * the Brave traceId, never a harness-supplied {@code test.id} value (confirmed by re-running the
 * brief's literal procedure: it produced {@code classCount:0, recordedProbes:0}, all probes dropped as
 * "unregistered testId=&lt;traceId&gt;" in strict mode &mdash; the &sect;3 S5 spike finding
 * reproduced). So both instances run {@code traceKeyAutoCreate=true} (matching
 * {@code MmTracerPathE2E#b3TraceIdKeyedStore}'s proven mechanism) and the shared identifier the two
 * execs collect under is the propagated Brave traceId, read back from the response body exactly like
 * {@code MmTracerPathE2E#asyncAttributedToSameStore} does &mdash; NOT the literal harness testId. The
 * harness still sends the {@code HeaderStyle.FIELD} {@code test.id: D1} header (consuming Task 6's
 * harness API per the brief's interface contract) and this test verifies, via B's {@code /sink}
 * header-dump file, that the field header actually survives the two-hop wire propagation &mdash; that
 * wire-level proof is what "REQ-MM-008 E2E cross-reference" in the traceability matrix means here.
 */
class MmDistributedFieldE2E {

    private static final Pattern TOMCAT_PORT_LOG = Pattern.compile("Tomcat started on port\\D*(\\d+)");
    private static final Pattern REQUEST_TRACE_ID = Pattern.compile("requestTraceId=([0-9a-f]+)");
    private static final Pattern SINK_TRACE_ID = Pattern.compile("sinkTraceId=([0-9a-f]+)");
    private static final String TEST_ID = "D1";

    @Test
    @DisplayName("REQ-MM-013: 2-hop FIELD 스타일 — 양 서비스 exec가 같은 testId로 수집·병합")
    void twoHopSameTestId() throws Exception {
        File logB = Files.createTempFile("mm-distfield-b", ".log").toFile();
        File logA = Files.createTempFile("mm-distfield-a", ".log").toFile();
        Path destB = Files.createTempDirectory("mm-distfield-b-dest-");
        Path destA = Files.createTempDirectory("mm-distfield-a-dest-");
        File sinkFile = Files.createTempFile("mm-distfield-sink", ".txt").toFile();
        Process appB = null;
        Process appA = null;
        try {
            // Both instances traceKeyAutoCreate=true (see class javadoc for why the brief's strict
            // /test/start?testId=D1 boundary does not apply to a tracer-active app): the propagated
            // Brave traceId is the shared key both execs collect under.
            appB = startApp(logB, destB, "traceKeyAutoCreate=true", sinkFile);
            int bPort = awaitPatternFromLog(logB, TOMCAT_PORT_LOG, 60);

            appA = startApp(logA, destA, "traceKeyAutoCreate=true", null,
                    "--downstream.base-url=http://127.0.0.1:" + bPort);
            int aPort = awaitPatternFromLog(logA, TOMCAT_PORT_LOG, 60);

            Pjacoco.setCurrentTestId(TEST_ID);
            Response response;
            try {
                // FIELD style: emits "test.id: D1" on the request to A (Task 6 harness API). A's
                // inbound Brave tracer mints a fresh traceId (no b3 header supplied) and, because
                // management.tracing.baggage.remote-fields=test.id is configured, attaches the field
                // as baggage on that trace context; A's own RestTemplate call to B then re-propagates
                // both B3 and the test.id field (S4 wire format) — that tracer-propagated hop is the
                // thing under test.
                response = RestAssured.given()
                        .filter(PjacocoRestAssured.baggageFilter(HeaderStyle.FIELD))
                        .get("http://127.0.0.1:" + aPort + "/call-downstream");
                assertEquals(200, response.statusCode(), "A must respond 200 on /call-downstream");
            } finally {
                Pjacoco.clearCurrentTestId();
            }

            String traceId = extractGroup(response.body().asString(), REQUEST_TRACE_ID, "requestTraceId");
            String sinkTraceId = extractGroup(response.body().asString(), SINK_TRACE_ID, "sinkTraceId");
            assertEquals(traceId, sinkTraceId,
                    "B3 must propagate A's traceId to B unchanged (both services collect under this key)");

            shutdownGracefully(appA);   // SIGTERM -> shutdown hook flushes A's traceId-keyed store
            shutdownGracefully(appB);   // SIGTERM -> shutdown hook flushes B's traceId-keyed store

            Path execA = destA.resolve(traceId + ".exec");
            Path execB = destB.resolve(traceId + ".exec");
            assertTrue(Files.exists(execA), "A must flush " + traceId + ".exec on SIGTERM: " + execA);
            assertTrue(Files.exists(execB), "B must flush " + traceId + ".exec on SIGTERM: " + execB);

            // ---- REQ-MM-008 E2E cross-reference: the FIELD-style header (not just B3) actually
            // travelled the full two-hop wire, proving HeaderStyle.FIELD's emitted header survives
            // real tracer-driven propagation (not just the testkit's own unit-level emission check). ----
            String sinkHeaders = Files.readString(sinkFile.toPath(), StandardCharsets.UTF_8);
            assertTrue(sinkHeaders.toLowerCase(java.util.Locale.ROOT).contains("test.id: " + TEST_ID.toLowerCase(java.util.Locale.ROOT)),
                    "B's /sink must have received the propagated 'test.id: " + TEST_ID + "' field header: " + sinkHeaders);

            byte[] downstreamWorkerBytes = readClassBytes("spike/DownstreamWorker");
            byte[] spikeControllerBytes = readClassBytes("spike/SpikeController");

            // ---- per-instance isolation: proves the two execs carry genuinely different, non-
            // overlapping contributions (the thing a last-writer-wins merge would silently destroy) ----
            ExecutionDataStore edsA = readExecs(execA);
            ExecutionDataStore edsB = readExecs(execB);

            // A-only class: DownstreamWorker#mark is called only from callDownstream(), which only A's
            // instance ever receives (B never gets a /call-downstream request in this flow) — see the
            // class's own "Marker class covered ONLY by /call-downstream" javadoc in the app source.
            assertAnyProbeCovered(edsA, "spike/DownstreamWorker", "A's exec");
            assertNotCovered(edsB, "spike/DownstreamWorker", "B's exec");

            // The app has no separate class dedicated to the /sink handler (SpikeController#sink is
            // inline), so "B's uniquely-executed unit" is expressed at method granularity within the
            // shared SpikeController class instead of a wholly separate class: A's window never calls
            // its own sink() (it delegates to B), and B's window never calls callDownstream() (only A's
            // harness request hits that endpoint) — each instance's store holds only its own method's
            // lines for this shared class.
            assertTrue(methodHasCoveredLine(edsA, "spike.SpikeController", spikeControllerBytes, "callDownstream"),
                    "A's exec must cover SpikeController#callDownstream (A's own request handling)");
            assertFalse(methodHasCoveredLine(edsA, "spike.SpikeController", spikeControllerBytes, "sink"),
                    "A's exec must NOT cover SpikeController#sink (A never handles /sink itself in this flow)");
            assertTrue(methodHasCoveredLine(edsB, "spike.SpikeController", spikeControllerBytes, "sink"),
                    "B's exec must cover SpikeController#sink (B's own request handling, reached via A's "
                            + "tracer-propagated downstream call)");
            assertFalse(methodHasCoveredLine(edsB, "spike.SpikeController", spikeControllerBytes, "callDownstream"),
                    "B's exec must NOT cover SpikeController#callDownstream (B never receives that endpoint)");

            // ---- merge: vanilla JaCoCo union (ExecutionDataStore visits both files sequentially into
            // one store, which merges/ORs matching class entries rather than overwriting) — both sides'
            // unique contributions must survive ----
            ExecutionDataStore merged = readExecs(execA, execB);
            assertAnyProbeCovered(merged, "spike/DownstreamWorker", "merged store");
            assertTrue(methodHasCoveredLine(merged, "spike.SpikeController", spikeControllerBytes, "callDownstream"),
                    "merged store must retain A's unique callDownstream coverage (no last-writer-wins loss)");
            assertTrue(methodHasCoveredLine(merged, "spike.SpikeController", spikeControllerBytes, "sink"),
                    "merged store must retain B's unique sink coverage (no last-writer-wins loss)");
        } finally {
            terminate(appA);
            terminate(appB);
            deleteRecursively(destA);
            deleteRecursively(destB);
            logA.delete();
            logB.delete();
            sinkFile.delete();
        }
    }

    // ================= app lifecycle =================

    /**
     * @param extraAgentOptions extra {@code key=value} agent options appended after {@code destdir=..,
     *     port=0} (comma-prefixed automatically), or {@code ""} for none
     * @param sinkFileOrNull when non-null, routes the app's {@code /sink} handler's header-dump file to
     *     this temp file via {@code -Dspike.sink.file=..} instead of the CWD-relative default (avoids
     *     leaving stray {@code sink-headers.txt} litter in the working directory)
     * @param extraBootArgs extra {@code --key=value} Spring Boot arguments appended after
     *     {@code --server.port=0}
     */
    private static Process startApp(File log, Path destDir, String extraAgentOptions, File sinkFileOrNull,
            String... extraBootArgs) throws IOException {
        String agentOptions = "destdir=" + destDir + ",port=0"
                + (extraAgentOptions.isEmpty() ? "" : "," + extraAgentOptions);
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin());
        cmd.add("-javaagent:" + agentJar() + "=" + agentOptions);
        if (sinkFileOrNull != null) {
            cmd.add("-Dspike.sink.file=" + sinkFileOrNull.getAbsolutePath());
        }
        cmd.add("-jar");
        cmd.add(bootJar());
        cmd.add("--server.port=0");
        for (String arg : extraBootArgs) {
            cmd.add(arg);
        }
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
    }

    /** SIGTERM + wait, so the agent's shutdown hook (partial-store dump of the traceKeyAutoCreate
     *  store) runs — see {@code MmTracerPathE2E#shutdownGracefully} for why SIGKILL would skip it
     *  entirely. */
    private static void shutdownGracefully(Process app) throws InterruptedException {
        app.destroy();
        boolean exited = app.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            fail("app (pid " + app.pid() + ") did not exit within 20s of SIGTERM");
        }
    }

    /** Teardown safety net for every exit path, on BOTH child JVMs: force-kill if still alive (no-op
     *  if graceful shutdown already reaped it), then assert zero remaining processes — the leak-check
     *  gate this E2E's REQ-MM-013 acceptance criterion requires. */
    private static void terminate(Process app) throws InterruptedException {
        if (app == null) {
            return;
        }
        if (app.isAlive()) {
            app.destroyForcibly();
            app.waitFor(10, TimeUnit.SECONDS);
        }
        assertFalse(app.isAlive(), "child JVM (pid " + app.pid() + ") must not remain alive after teardown");
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

    // ================= boot log polling (pattern-parameterized single-file variant of
    // MmTracerPathE2E/MmBoot3BootE2E's per-file Tomcat-port poll; the cross-file dedup itself is still
    // the open debt noted in MmTracerPathE2E) =================

    private static int awaitPatternFromLog(File log, Pattern pattern, int timeoutSeconds)
            throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String content = Files.readString(log.toPath());
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            if (content.contains("APPLICATION FAILED TO START") || content.contains("Error starting ApplicationContext")) {
                fail("app failed to start within " + timeoutSeconds + "s:\n" + content);
            }
            Thread.sleep(200);
        }
        fail("pattern '" + pattern + "' not found in log within " + timeoutSeconds + "s:\n" + Files.readString(log.toPath()));
        throw new AssertionError("unreachable");
    }

    private static String extractGroup(String body, Pattern pattern, String label) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            fail("response body did not contain " + label + "=<hex>: " + body);
        }
        return matcher.group(1);
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

    private static void assertAnyProbeCovered(ExecutionDataStore eds, String vmName, String execDescription) {
        ExecutionData target = find(eds, vmName);
        assertNotNull(target, execDescription + " must contain ExecutionData for " + vmName);
        boolean anySet = false;
        for (boolean p : target.getProbes()) {
            if (p) {
                anySet = true;
                break;
            }
        }
        assertTrue(anySet, vmName + " must have at least one fired probe in " + execDescription);
    }

    /** Absent entirely, or present with every probe false — both count as "not covered" (mirrors
     *  {@code MmBaggageHeadersE2E#assertEmptyOrAbsent}'s strict-mode convention). */
    private static void assertNotCovered(ExecutionDataStore eds, String vmName, String execDescription) {
        ExecutionData target = find(eds, vmName);
        if (target == null) {
            return;
        }
        for (boolean p : target.getProbes()) {
            assertFalse(p, vmName + " must not have any fired probe in " + execDescription);
        }
    }

    private static ExecutionData find(ExecutionDataStore eds, String vmName) {
        for (ExecutionData candidate : eds.getContents()) {
            if (vmName.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /** True when any executable (non-{@link ICounter#EMPTY}) line of {@code methodName} within
     *  {@code fqcn} is at least {@link ICounter#PARTLY_COVERED} in {@code eds}. Works even when the
     *  class has no entry at all in {@code eds} (jacoco's {@link Analyzer} still structurally analyzes
     *  the class bytes and reports every line NOT_COVERED in that case). */
    private static boolean methodHasCoveredLine(ExecutionDataStore eds, String fqcn, byte[] classBytes,
            String methodName) throws IOException {
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(eds, builder).analyzeClass(classBytes, fqcn);
        IClassCoverage classCoverage = null;
        for (IClassCoverage c : builder.getClasses()) {
            classCoverage = c;   // exactly one class was analyzed above
            break;
        }
        assertNotNull(classCoverage, "class coverage analysis produced nothing for " + fqcn);
        IMethodCoverage method = null;
        for (IMethodCoverage m : classCoverage.getMethods()) {
            if (methodName.equals(m.getName())) {
                method = m;
                break;
            }
        }
        assertNotNull(method, fqcn + " must declare a " + methodName + "() method");
        for (int line = method.getFirstLine(); line <= method.getLastLine(); line++) {
            int status = classCoverage.getLine(line).getStatus();
            if (status == ICounter.FULLY_COVERED || status == ICounter.PARTLY_COVERED) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readClassBytes(String vmName) throws IOException {
        try (InputStream in = MmDistributedFieldE2E.class.getResourceAsStream("/" + vmName + ".class")) {
            assertNotNull(in, "class resource not found on test classpath: /" + vmName
                    + ".class (expected via e2e-mm-boot3's main sourceSet output)");
            return in.readAllBytes();
        }
    }

    /** Reads one or more {@code .exec} files into a SINGLE {@link ExecutionDataStore}. jacoco's store
     *  merges (ORs probe arrays) same-id class entries across successive
     *  {@link ExecutionDataReader#read()} calls sharing one visitor rather than overwriting — this is
     *  the "vanilla union merge, not last-writer-wins" the REQ-MM-013 acceptance criterion requires. */
    private static ExecutionDataStore readExecs(Path... execs) throws IOException {
        ExecutionDataStore eds = new ExecutionDataStore();
        SessionInfoStore sessions = new SessionInfoStore();
        for (Path exec : execs) {
            try (InputStream in = Files.newInputStream(exec)) {
                ExecutionDataReader r = new ExecutionDataReader(in);
                r.setExecutionDataVisitor(eds);
                r.setSessionInfoVisitor(sessions);
                r.read();
            }
        }
        return eds;
    }
}
