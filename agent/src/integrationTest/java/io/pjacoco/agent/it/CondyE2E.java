package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof on <strong>modern (Java 11+) bytecode</strong>: drives {@link CondyServlet}
 * (which calls {@code CondyTarget}, class-file major 55) through the REAL
 * {@code -javaagent:pjacoco-agent.jar} and asserts per-test {@code .exec} files carry real,
 * branch-exclusive coverage. Because {@code CondyTarget} is major 55, jacoco instruments it with
 * {@code CondyProbeArrayStrategy}; a green run here is the out-of-process confirmation that the
 * routing hook is not limited to Java 8 bytecode. Runs only in the {@code e2eCondyTest} Gradle task.
 */
@Tag("e2econdy")
class CondyE2E {

    static final int CONTROL_PORT = 6312;
    static final Path COVERAGE = Paths.get("build/coverage-condy");
    static final String TARGET_VM = "com/example/app/CondyTarget";
    static final String TARGET_FQCN = "com.example.app.CondyTarget";

    static Server server;
    static int appPort;

    @BeforeAll
    static void startApp() throws Exception {
        server = new Server(0);
        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(CondyServlet.class, "/run");
        server.setHandler(handler);
        server.start();
        appPort = server.getURI().getPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void condyBytecode_perTestIsolation_andRealProbes() throws Exception {
        startTest("CONDY_NEG", "shard-1");
        startTest("CONDY_POS", "shard-2");
        app("CONDY_NEG", "negative");
        app("CONDY_POS", "positive");
        app("CONDY_NEG", "negative");
        stopTest("CONDY_NEG", "passed");
        stopTest("CONDY_POS", "passed");

        // Each .exec must carry real fired probes for the Java 11+ class (not an empty shell) —
        // this is exactly what would be missing if the hook no-op'd on CondyProbeArrayStrategy.
        assertTargetProbesPresent(COVERAGE.resolve("CONDY_NEG.exec"));
        assertTargetProbesPresent(COVERAGE.resolve("CONDY_POS.exec"));

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("CONDY_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("CONDY_POS.exec"));
        assertFalse(minus(neg, pos).isEmpty(),
                "CONDY_NEG must cover its own branch line absent from CONDY_POS. neg=" + neg + " pos=" + pos);
        assertFalse(minus(pos, neg).isEmpty(),
                "CONDY_POS must cover its own branch line absent from CONDY_NEG (no contamination). neg=" + neg + " pos=" + pos);
    }

    // ================= helpers =================

    private void startTest(String testId, String shardId) throws Exception {
        String q = "/__coverage__/test/start?testId=" + testId + (shardId != null ? "&shardId=" + shardId : "");
        assertEquals("started " + testId, control(q), "start control body");
    }

    private void stopTest(String testId, String result) throws Exception {
        assertEquals("stopped " + testId,
                control("/__coverage__/test/stop?testId=" + testId + "&result=" + result), "stop control body");
    }

    private String control(String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + CONTROL_PORT + pathAndQuery).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(new byte[0]);
        os.close();
        assertEquals(200, c.getResponseCode(), "control call failed: " + pathAndQuery);
        return readStream(c.getInputStream());
    }

    private void app(String testIdOrNull, String mode) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + appPort + "/run?mode=" + mode).openConnection();
        if (testIdOrNull != null) {
            c.setRequestProperty("baggage", "test.id=" + testIdOrNull);
        }
        assertEquals(200, c.getResponseCode(), "app request failed");
        assertEquals("ok", readStream(c.getInputStream()), "servlet must have actually handled the request");
    }

    private static SortedSet<Integer> coveredLines(Path exec) throws Exception {
        byte[] cls = readResourceBytes("/" + TARGET_VM + ".class");
        ExecutionDataStore eds = readExec(exec);
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(eds, cb).analyzeClass(cls, TARGET_FQCN);
        SortedSet<Integer> lines = new TreeSet<Integer>();
        for (IClassCoverage c : cb.getClasses()) {
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                int s = c.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) {
                    lines.add(l);
                }
            }
        }
        return lines;
    }

    private static void assertTargetProbesPresent(Path exec) throws Exception {
        ExecutionDataStore eds = readExec(exec);
        ExecutionData ed = null;
        for (ExecutionData candidate : eds.getContents()) {
            if (TARGET_VM.equals(candidate.getName())) { ed = candidate; break; }
        }
        assertNotNull(ed, "exec must contain ExecutionData for CondyTarget: " + exec);
        boolean anySet = false;
        for (boolean p : ed.getProbes()) if (p) { anySet = true; break; }
        assertTrue(ed.getProbes().length > 0, "CondyTarget must have a probe array");
        assertTrue(anySet, "CondyTarget must have at least one fired probe (Condy routing worked)");
    }

    private static ExecutionDataStore readExec(Path exec) throws Exception {
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
        return eds;
    }

    private static SortedSet<Integer> minus(SortedSet<Integer> a, SortedSet<Integer> b) {
        SortedSet<Integer> out = new TreeSet<Integer>(a);
        out.removeAll(b);
        return out;
    }

    private static String readStream(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static byte[] readResourceBytes(String res) throws Exception {
        InputStream in = CondyE2E.class.getResourceAsStream(res);
        assertNotNull(in, "resource not found: " + res);
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
}
