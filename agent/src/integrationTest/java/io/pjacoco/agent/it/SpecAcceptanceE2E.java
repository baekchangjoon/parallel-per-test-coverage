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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance: drives the app through the REAL {@code -javaagent:pjacoco-agent.jar}
 * and asserts the design spec's observable guarantees via the {@code .exec}/{@code .json} files on
 * disk. Runs only in the {@code e2eTest} Gradle task (real agent attached). The keystone
 * vanilla byte-equivalence is proven in-process by GoldenEquivalenceIT; here we verify the
 * out-of-process system end-to-end and rigorously: no loose "not-equal"/"non-empty"/200-only checks.
 */
@Tag("e2e")
class SpecAcceptanceE2E {

    static final int CONTROL_PORT = 6310;
    static final Path COVERAGE = Paths.get("build/coverage");
    static final String TARGET_VM = "com/example/app/TargetService";
    static final String TARGET_FQCN = "com.example.app.TargetService";

    static Server server;
    static int appPort;

    @BeforeAll
    static void startApp() throws Exception {
        server = new Server(0);
        ServletHandler handler = new ServletHandler();
        handler.addServletWithMapping(SampleServlet.class, "/run");
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

    // ---- spec §1, §4, §7.1: per-test isolation (branch-exclusive) + structurally valid .exec ----
    @Test
    void perTestIsolation_branchExclusive_andValidExec() throws Exception {
        startTest("ISO_NEG", "shard-1");
        startTest("ISO_POS", "shard-2");
        app("ISO_NEG", "negative");
        app("ISO_POS", "positive");
        app("ISO_NEG", "negative");
        stopTest("ISO_NEG", "passed");
        stopTest("ISO_POS", "passed");

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("ISO_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("ISO_POS.exec"));

        // Structural validity: the .exec really carries TargetService probe data (not an empty shell).
        assertTargetProbesPresent(COVERAGE.resolve("ISO_NEG.exec"));
        assertTargetProbesPresent(COVERAGE.resolve("ISO_POS.exec"));

        // Mutual exclusivity catches partial cross-contamination that a bare "neg != pos" would miss:
        // if ISO_NEG absorbed ISO_POS's probes, posOnly (= pos - neg) would become empty.
        SortedSet<Integer> negOnly = minus(neg, pos);
        SortedSet<Integer> posOnly = minus(pos, neg);
        assertFalse(negOnly.isEmpty(),
                "ISO_NEG must cover a line ISO_POS does not (its own branch). neg=" + neg + " pos=" + pos);
        assertFalse(posOnly.isEmpty(),
                "ISO_POS must cover a line ISO_NEG does not (no contamination into ISO_NEG). neg=" + neg + " pos=" + pos);
    }

    // ---- spec §7.1: isolation holds under REAL concurrency (many threads, interleaved testIds) ----
    @Test
    void concurrentIsolation() throws Exception {
        startTest("CC_NEG", null);
        startTest("CC_POS", null);

        final int requests = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < requests; i++) {
            final boolean negative = (i % 2 == 0);
            futures.add(pool.submit(new java.util.concurrent.Callable<Void>() {
                public Void call() throws Exception {
                    go.await();
                    app(negative ? "CC_NEG" : "CC_POS", negative ? "negative" : "positive");
                    return null;
                }
            }));
        }
        go.countDown();                       // release all threads at once
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        stopTest("CC_NEG", "passed");
        stopTest("CC_POS", "passed");

        SortedSet<Integer> neg = coveredLines(COVERAGE.resolve("CC_NEG.exec"));
        SortedSet<Integer> pos = coveredLines(COVERAGE.resolve("CC_POS.exec"));
        assertFalse(minus(neg, pos).isEmpty(), "concurrent: CC_NEG branch-exclusive line missing. neg=" + neg + " pos=" + pos);
        assertFalse(minus(pos, neg).isEmpty(), "concurrent: contamination into CC_NEG. neg=" + neg + " pos=" + pos);
    }

    // ---- spec §7.3: retry overwrite — latest attempt wins, retryCount increments ----
    @Test
    void retryOverwrite_latestAttemptWins() throws Exception {
        // baselines for the two branches (independent tests)
        runSingle("BASE_POS", "positive");
        runSingle("BASE_NEG", "negative");
        SortedSet<Integer> posBase = coveredLines(COVERAGE.resolve("BASE_POS.exec"));
        SortedSet<Integer> negBase = coveredLines(COVERAGE.resolve("BASE_NEG.exec"));
        assertFalse(posBase.equals(negBase), "sanity: the two branches differ");

        // T_RETRY: a positive attempt, then a retry (start again) overwrites it with a negative attempt.
        startTest("T_RETRY", null);
        app("T_RETRY", "positive");
        startTest("T_RETRY", null);             // retry -> store reset, retryCount++
        app("T_RETRY", "negative");
        stopTest("T_RETRY", "passed");

        String sidecar = readFile(COVERAGE.resolve("T_RETRY.json"));
        assertJsonEquals(sidecar, "retryCount", "1");

        SortedSet<Integer> retry = coveredLines(COVERAGE.resolve("T_RETRY.exec"));
        // Latest (negative) attempt only: equals the negative baseline, and the discarded positive
        // attempt's distinctive lines are absent.
        assertEquals(negBase, retry, "retry result must equal the latest (negative) attempt only");
        assertFalse(minus(posBase, retry).isEmpty(),
                "the discarded positive attempt's lines must NOT appear in the retry result");
    }

    // ---- spec §5.1: sidecar json schema (exact field values, not loose substrings) ----
    @Test
    void sidecarSchema_exactFields() throws Exception {
        startTest("SIDE_A", "shard-7");
        app("SIDE_A", "positive");
        stopTest("SIDE_A", "passed");

        String json = readFile(COVERAGE.resolve("SIDE_A.json"));
        assertJsonEquals(json, "testId", "\"SIDE_A\"");
        assertJsonEquals(json, "exec", "\"SIDE_A.exec\"");
        assertJsonEquals(json, "precision", "\"line\"");
        assertJsonEquals(json, "result", "\"passed\"");
        assertJsonEquals(json, "shardId", "\"shard-7\"");
        assertJsonEquals(json, "status", "\"complete\"");
        assertJsonEquals(json, "classCount", "1");        // exactly 1 (TargetService), not "1x"
        assertJsonEquals(json, "retryCount", "0");
        // timing fields present and sane
        long started = Long.parseLong(jsonRaw(json, "startedAtMillis"));
        long stopped = Long.parseLong(jsonRaw(json, "stoppedAtMillis"));
        assertTrue(stopped >= started, "stoppedAtMillis must be >= startedAtMillis");
        assertEquals(stopped - started, Long.parseLong(jsonRaw(json, "durationMs")), "durationMs consistency");
    }

    // ---- spec §5.2: global manifest header written at premain (exact fields) ----
    @Test
    void manifestHeader_exactFields() throws Exception {
        String json = readFile(COVERAGE.resolve("manifest.json"));
        assertJsonEquals(json, "schemaVersion", "1");
        assertJsonEquals(json, "precision", "\"line\"");
        assertJsonEquals(json, "commitSha", "\"e2e-deadbeef\"");   // from env PJACOCO_COMMIT
        assertJsonEquals(json, "jacocoVersion", "\"0.8.12\"");
        // header MUST NOT accumulate the per-test list (spec §5.2: avoids stop contention)
        assertFalse(json.contains("\"tests\""), "manifest header must not contain a tests array");
    }

    // ---- spec §2/§4.3: strict mode — an unregistered testId is processed by the app but never recorded.
    // Crucially we ALSO stop GHOST: a lenient/auto-create regression only surfaces on flush, so without
    // the stop the test could not tell strict-reject from lazily-created-but-never-flushed. ----
    @Test
    void strictMode_unregisteredTestId_producesNoExec() throws Exception {
        app("GHOST", "positive");                  // app() asserts the request returned "ok" (it ran)
        control("/__coverage__/test/stop?testId=GHOST&result=passed");   // no-op under strict; would flush if lenient
        assertFalse(Files.exists(COVERAGE.resolve("GHOST.exec")), "strict mode must not record/flush an unregistered testId");
        assertFalse(Files.exists(COVERAGE.resolve("GHOST.json")), "no sidecar for an unregistered testId");
    }

    // ---- spec §4.3: untagged traffic is neither recorded as its own test NOR leaked into an active
    // test via a reused worker whose ThreadLocal context was not cleared on request exit. ----
    @Test
    void untaggedRequest_notRecorded_andNoThreadLeak() throws Exception {
        // a clean positive run = what T_LEAK must look like if untagged negatives are NOT leaked in
        runSingle("BASE_POS_U", "positive");
        SortedSet<Integer> posBase = coveredLines(COVERAGE.resolve("BASE_POS_U.exec"));

        startTest("T_LEAK", null);
        for (int i = 0; i < 12; i++) app("T_LEAK", "positive");   // warm the worker pool with T_LEAK context
        for (int i = 0; i < 12; i++) app(null, "negative");       // untagged, DIFFERENT branch, on reused workers
        stopTest("T_LEAK", "passed");

        SortedSet<Integer> leak = coveredLines(COVERAGE.resolve("T_LEAK.exec"));
        // If clear() were skipped, a reused worker would still hold T_LEAK and record the untagged
        // negative branch into it -> leak would gain the negative-only lines.
        assertEquals(posBase, leak,
                "untagged negatives must not be recorded/leaked into T_LEAK (positive-only); leak=" + leak + " posBase=" + posBase);

        // a separately-started test that only ever sees untagged traffic stays empty
        startTest("UNTAGGED", null);
        app(null, "positive");
        stopTest("UNTAGGED", "passed");
        assertTrue(coveredLines(COVERAGE.resolve("UNTAGGED.exec")).isEmpty(), "untagged-only test must be empty");
        assertJsonEquals(readFile(COVERAGE.resolve("UNTAGGED.json")), "classCount", "0");
    }

    // ================= helpers =================

    private void startTest(String testId, String shardId) throws Exception {
        String q = "/__coverage__/test/start?testId=" + testId + (shardId != null ? "&shardId=" + shardId : "");
        assertEquals("started " + testId, control(q), "start control body");
    }

    private void stopTest(String testId, String result) throws Exception {
        assertEquals("stopped " + testId, control("/__coverage__/test/stop?testId=" + testId + "&result=" + result),
                "stop control body");
    }

    private void runSingle(String testId, String mode) throws Exception {
        startTest(testId, null);
        app(testId, mode);
        stopTest(testId, "passed");
    }

    /** POST to the control endpoint; assert 200 and return the response body. */
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

    /** GET the app; assert 200 AND that the servlet actually ran (body "ok"), not just any 200. */
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

    /** Asserts the .exec carries real probe data for TargetService (at least one probe set). */
    private static void assertTargetProbesPresent(Path exec) throws Exception {
        ExecutionDataStore eds = readExec(exec);
        ExecutionData ed = null;
        for (ExecutionData candidate : eds.getContents()) {
            if (TARGET_VM.equals(candidate.getName())) { ed = candidate; break; }
        }
        assertNotNull(ed, "exec must contain ExecutionData for TargetService: " + exec);
        boolean anySet = false;
        for (boolean p : ed.getProbes()) if (p) { anySet = true; break; }
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

    /** Exact value of a flat-JSON field (raw token incl. surrounding quotes for strings), or null. */
    private static String jsonRaw(String json, String key) {
        String k = "\"" + key + "\":";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int start = i + k.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static void assertJsonEquals(String json, String key, String expectedRaw) {
        assertEquals(expectedRaw, jsonRaw(json, key), "json field '" + key + "' in " + json);
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

    private static String readFile(Path p) throws Exception {
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    private static byte[] readResourceBytes(String res) throws Exception {
        InputStream in = SpecAcceptanceE2E.class.getResourceAsStream(res);
        assertNotNull(in, "resource not found: " + res);
        return readResource(in);
    }

    private static byte[] readResource(InputStream in) throws Exception {
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
