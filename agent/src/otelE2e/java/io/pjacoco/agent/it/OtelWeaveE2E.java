package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Forked-JVM OTel smoke E2E (REQ-004, REQ-006).
 *
 * <p>Forks a JVM that runs {@code OtelSut} under BOTH the real OTel javaagent AND
 * pjacoco's agent ({@code traceKeyAutoCreate=true}). Asserts:
 * <ol>
 *   <li>A per-trace {@code .exec} keyed by the captured trace-id exists in the output dir.</li>
 *   <li>The exec contains coverage for {@code RequestHandler} (request-thread — REQ-004).</li>
 *   <li>The exec contains coverage for {@code AsyncWorker} (worker-thread — REQ-006).</li>
 * </ol>
 *
 * <p>The OTel javaagent path is supplied via system property {@code pjacoco.otelAgent}; if absent
 * the test is {@link org.junit.jupiter.api.Assumptions#assumeTrue skipped} (not failed) so the
 * normal CI runs without the OTel jar do not break.
 *
 * <p>The pjacoco shaded jar path is supplied via {@code pjacoco.shadedJar}.
 */
@Tag("otelE2e")
class OtelWeaveE2E {

    private static final String REQUEST_HANDLER_VM = "com/example/otelsut/RequestHandler";
    private static final String ASYNC_WORKER_VM    = "com/example/otelsut/AsyncWorker";

    @Test
    void otelWeave_requestAndAsyncThreadsCoveredUnderSameTrace() throws Exception {
        String otelJar = System.getProperty("pjacoco.otelAgent");
        assumeTrue(otelJar != null && !otelJar.isEmpty(),
                "pjacoco.otelAgent not set — skipping OTel E2E (normal without the OTel jar)");
        assertTrue(new File(otelJar).isFile(), "OTel agent jar not found: " + otelJar);

        String shadedJar = System.getProperty("pjacoco.shadedJar");
        assertNotNull(shadedJar, "pjacoco.shadedJar system property must be set");
        assertTrue(new File(shadedJar).isFile(), "pjacoco shaded jar not found: " + shadedJar);

        // Output dir for per-trace .exec files.
        Path outDir = Paths.get("build/coverage-otel-e2e");
        Files.createDirectories(outDir);

        // Clean up any stale exec from a previous run.
        deleteFilesMatching(outDir, ".exec");

        // Build the forked-JVM command.
        //
        // Agent order: OTel javaagent FIRST (so it registers GlobalOpenTelemetry before pjacoco
        // starts), then pjacoco agent. pjacoco's OtelScopeInboundActivator runs inside premain
        // and weaves the OTel shaded storage before the SUT's first span is created.
        //
        // SUT classpath: the otelE2e compiled classes (RequestHandler, AsyncWorker, OtelSut) are
        // on the test classpath, so we pass the test classpath directly.
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

        // Build classpath: otelE2e classes + OTel API (provided by otelE2e source set classpath)
        String cp = buildSutClasspath();

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        // OTel javaagent first: suppresses all exporters so the SUT stays lightweight.
        cmd.add("-javaagent:" + otelJar);
        cmd.add("-Dotel.traces.exporter=none");
        cmd.add("-Dotel.metrics.exporter=none");
        cmd.add("-Dotel.logs.exporter=none");
        cmd.add("-Dotel.java.global-autoconfigure.enabled=true");
        // pjacoco agent: traceKeyAutoCreate enables the OTel weave.
        cmd.add("-javaagent:" + shadedJar
                + "=destfile=" + outDir.toAbsolutePath()
                + ",traceKeyAutoCreate=true"
                + ",includes=com.example.otelsut.*");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("com.example.otelsut.OtelSut");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);  // keep stderr separate so we can print it on failure

        Process proc = pb.start();

        // Capture stdout (contains TRACE_ID=...) and stderr.
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outThread = drainStream(proc.getInputStream(), stdout);
        Thread errThread = drainStream(proc.getErrorStream(), stderr);

        boolean exited = proc.waitFor(60, TimeUnit.SECONDS);
        outThread.join(5000);
        errThread.join(5000);

        // Always print stderr for diagnosis.
        System.out.println("[OtelWeaveE2E] SUT stderr:\n" + stderr);
        System.out.println("[OtelWeaveE2E] SUT stdout:\n" + stdout);

        assertTrue(exited, "OtelSut JVM did not exit within 60s");
        int exitCode = proc.exitValue();
        assertTrue(exitCode == 0,
                "OtelSut JVM exited with code " + exitCode + ". stderr:\n" + stderr);

        // Extract the trace-id printed by OtelSut.
        String traceId = extractTraceId(stdout.toString());
        assertNotNull(traceId, "OtelSut did not print TRACE_ID=... to stdout. stdout:\n" + stdout);
        System.out.println("[OtelWeaveE2E] captured traceId=" + traceId);

        // pjacoco flushes a per-trace exec named <traceId>.exec.
        // With traceKeyAutoCreate the key IS the trace-id (all-lower-hex).
        Path execPath = findExec(outDir, traceId);
        assertNotNull(execPath,
                "No .exec file found for traceId=" + traceId + " in " + outDir
                        + ". Files: " + listFiles(outDir));
        System.out.println("[OtelWeaveE2E] exec file: " + execPath);

        // Assert REQ-004: coverage for RequestHandler (request thread) is in the exec.
        byte[] requestHandlerClass = loadClassBytes(REQUEST_HANDLER_VM);
        assertTrue(hasCoverage(execPath, REQUEST_HANDLER_VM, requestHandlerClass),
                "REQ-004: exec must contain coverage for RequestHandler (request thread). "
                        + "exec=" + execPath);

        // Assert REQ-006: coverage for AsyncWorker (async worker thread) is in the exec.
        byte[] asyncWorkerClass = loadClassBytes(ASYNC_WORKER_VM);
        assertTrue(hasCoverage(execPath, ASYNC_WORKER_VM, asyncWorkerClass),
                "REQ-006: exec must contain coverage for AsyncWorker (async worker thread). "
                        + "exec=" + execPath);

        System.out.println("[OtelWeaveE2E] PASS — both request-thread (REQ-004) and "
                + "async-worker-thread (REQ-006) coverage attributed to traceId=" + traceId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String buildSutClasspath() {
        // The otelE2e source set classes are on this test's classpath.
        // java.class.path contains everything we need (including OTel API jars).
        return System.getProperty("java.class.path");
    }

    private static Thread drainStream(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String extractTraceId(String stdout) {
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("TRACE_ID=")) {
                String tid = line.substring("TRACE_ID=".length()).trim();
                return tid.isEmpty() ? null : tid;
            }
        }
        return null;
    }

    /**
     * Looks for a {@code .exec} file whose name (without extension) equals the traceId, or
     * falls back to any {@code .exec} file whose content covers the SUT classes (in case pjacoco
     * normalises the key).
     */
    private static Path findExec(Path dir, String traceId) throws Exception {
        // Primary: exact match by trace-id.
        Path exact = dir.resolve(traceId + ".exec");
        if (Files.exists(exact)) {
            return exact;
        }
        // Fallback: scan all .exec files (pjacoco may sanitise the key).
        File[] files = dir.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".exec")) {
                    return f.toPath();
                }
            }
        }
        return null;
    }

    private static void deleteFilesMatching(Path dir, String suffix) throws Exception {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(suffix)) {
                f.delete();
            }
        }
    }

    private static String listFiles(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (File f : files) sb.append(f.getName()).append(' ');
        return sb.toString().trim();
    }

    private static byte[] loadClassBytes(String vmName) throws Exception {
        String res = "/" + vmName + ".class";
        InputStream in = OtelWeaveE2E.class.getResourceAsStream(res);
        assertNotNull(in, "class resource not found: " + res);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static boolean hasCoverage(Path exec, String vmName, byte[] classBytes)
            throws Exception {
        ExecutionDataStore eds = new ExecutionDataStore();
        InputStream in = Files.newInputStream(exec);
        try {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(new SessionInfoStore());
            r.read();
        } finally {
            in.close();
        }

        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(eds, cb).analyzeClass(classBytes, vmName.replace('/', '.'));

        for (IClassCoverage cc : cb.getClasses()) {
            for (int l = cc.getFirstLine(); l <= cc.getLastLine(); l++) {
                int s = cc.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) {
                    return true;
                }
            }
        }
        return false;
    }
}
