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
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance for the {@code jakarta.servlet} stack (Servlet 5+, as used by Spring Boot
 * 3–4 / Tomcat 10–11), the twin of {@link SpecAcceptanceE2E} which covers {@code javax.servlet}.
 *
 * <p>
 * Drives a real Jetty 11 app whose servlet extends {@code jakarta.servlet.http.HttpServlet} through
 * the real {@code -javaagent:pjacoco-agent.jar} and asserts per-test isolation via the
 * {@code .exec} files on disk. This proves the agent's inbound hook recognises the jakarta servlet
 * choke point (the javax-only matcher would record nothing here). It uses its own control port and
 * output directory so it can run alongside the javax {@code e2eTest}.
 */
class SpecAcceptanceJakartaE2E {

    static final int CONTROL_PORT = 6311;
    static final Path COVERAGE = Paths.get("build/coverage-jakarta");
    static final String TARGET_VM = "com/example/app/TargetService";
    static final String TARGET_FQCN = "com.example.app.TargetService";

    static Server server;
    static int appPort;

    @BeforeAll
    static void startApp() throws Exception {
        server = new Server(0);
        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(SampleServletJakarta.class, "/run");
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

    // ---- per-test isolation (branch-exclusive) routed through the jakarta.servlet hook ----
    @Test
    void perTestIsolation_branchExclusive_overJakartaServlet() throws Exception {
        startTest("JK_NEG", "shard-1");
        startTest("JK_POS", "shard-2");
        app("JK_NEG", "negative");
        app("JK_POS", "positive");
        app("JK_NEG", "negative");
        stopTest("JK_NEG", "passed");
        stopTest("JK_POS", "passed");

        // Structural validity: the .exec really carries TargetService probe data, not an empty shell.
        assertTargetProbesPresent(COVERAGE.resolve("JK_NEG.exec"));
        assertTargetProbesPresent(COVERAGE.resolve("JK_POS.exec"));

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("JK_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("JK_POS.exec"));
        assertFalse(minus(neg, pos).isEmpty(),
                "JK_NEG must cover a line JK_POS does not (its own branch). neg=" + neg + " pos=" + pos);
        assertFalse(minus(pos, neg).isEmpty(),
                "JK_POS must cover a line JK_NEG does not (no contamination). neg=" + neg + " pos=" + pos);
    }

    // ---- isolation holds under REAL concurrency (interleaved testIds), jakarta stack ----
    @Test
    void concurrentIsolation_overJakartaServlet() throws Exception {
        startTest("JK_CC_NEG", null);
        startTest("JK_CC_POS", null);

        final int requests = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < requests; i++) {
            final boolean negative = (i % 2 == 0);
            futures.add(pool.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    go.await();
                    app(negative ? "JK_CC_NEG" : "JK_CC_POS", negative ? "negative" : "positive");
                    return null;
                }
            }));
        }
        go.countDown(); // release all threads at once
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        stopTest("JK_CC_NEG", "passed");
        stopTest("JK_CC_POS", "passed");

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("JK_CC_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("JK_CC_POS.exec"));
        assertFalse(minus(neg, pos).isEmpty(),
                "concurrent jakarta: JK_CC_NEG branch-exclusive line missing. neg=" + neg + " pos=" + pos);
        assertFalse(minus(pos, neg).isEmpty(),
                "concurrent jakarta: contamination into JK_CC_NEG. neg=" + neg + " pos=" + pos);
    }

    // ================= helpers (mirror SpecAcceptanceE2E; namespace-independent) =================

    private void startTest(String testId, String shardId) throws Exception {
        String q = "/__coverage__/test/start?testId=" + testId + (shardId != null ? "&shardId=" + shardId : "");
        assertEquals("started " + testId, control(q), "start control body");
    }

    private void stopTest(String testId, String result) throws Exception {
        assertEquals("stopped " + testId, control("/__coverage__/test/stop?testId=" + testId + "&result=" + result),
                "stop control body");
    }

    /** POST to the control endpoint; assert 200 and return the response body. */
    private String control(String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + CONTROL_PORT + pathAndQuery)
            .openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(new byte[0]);
        os.close();
        assertEquals(200, c.getResponseCode(), "control call failed: " + pathAndQuery);
        return readStream(c.getInputStream());
    }

    /** GET the app; assert 200 AND that the servlet actually ran (body "ok"). */
    private void app(String testIdOrNull, String mode) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + appPort + "/run?mode=" + mode)
            .openConnection();
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

    /** Asserts the .exec carries real probe data for TargetService (at least one fired probe). */
    private static void assertTargetProbesPresent(Path exec) throws Exception {
        ExecutionDataStore eds = readExec(exec);
        ExecutionData ed = null;
        for (ExecutionData candidate : eds.getContents()) {
            if (TARGET_VM.equals(candidate.getName())) {
                ed = candidate;
                break;
            }
        }
        assertNotNull(ed, "exec must contain ExecutionData for TargetService: " + exec);
        boolean anySet = false;
        for (boolean p : ed.getProbes()) {
            if (p) {
                anySet = true;
                break;
            }
        }
        assertTrue(ed.getProbes().length > 0, "TargetService must have a probe array");
        assertTrue(anySet, "TargetService must have at least one fired probe");
    }

    private static ExecutionDataStore readExec(Path exec) throws Exception {
        ExecutionDataStore eds = new ExecutionDataStore();
        InputStream in = Files.newInputStream(exec);
        try {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(eds);
            r.setSessionInfoVisitor(new SessionInfoStore());
            r.read();
        }
        finally {
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
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        }
        finally {
            in.close();
        }
    }

    private static byte[] readResourceBytes(String res) throws Exception {
        InputStream in = SpecAcceptanceJakartaE2E.class.getResourceAsStream(res);
        assertNotNull(in, "resource not found: " + res);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
        finally {
            in.close();
        }
    }
}
