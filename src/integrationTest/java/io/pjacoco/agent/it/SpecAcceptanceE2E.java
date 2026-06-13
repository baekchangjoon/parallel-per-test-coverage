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
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance: drives the app through the REAL {@code -javaagent:jacocoagent-parallel.jar}
 * and asserts the design spec's observable guarantees via the {@code .exec}/{@code .json} files on
 * disk. Runs only in the {@code e2eTest} Gradle task (real agent attached). The keystone
 * vanilla byte-equivalence is proven in-process by GoldenEquivalenceIT; here we verify the
 * out-of-process system: Baggage routing, control endpoint, per-test isolation, sidecar/manifest,
 * strict mode, and untagged no-op.
 */
@Tag("e2e")
class SpecAcceptanceE2E {

    static final int CONTROL_PORT = 6310;
    static final Path COVERAGE = Paths.get("build/coverage");
    static final String TARGET_VM = "io/pjacoco/agent/it/TargetService";
    static final String TARGET_FQCN = "io.pjacoco.agent.it.TargetService";

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

    // ---- spec §1, §4, §7.1: per-test isolation + valid vanilla .exec ----
    @Test
    void perTestIsolation_andValidExec() throws Exception {
        control("/__coverage__/test/start?testId=ISO_NEG&shardId=shard-1");
        control("/__coverage__/test/start?testId=ISO_POS&shardId=shard-2");
        // interleave requests across the two tests
        app("ISO_NEG", "negative");
        app("ISO_POS", "positive");
        app("ISO_NEG", "negative");
        control("/__coverage__/test/stop?testId=ISO_NEG&result=passed");
        control("/__coverage__/test/stop?testId=ISO_POS&result=passed");

        Path negExec = COVERAGE.resolve("ISO_NEG.exec");
        Path posExec = COVERAGE.resolve("ISO_POS.exec");
        assertTrue(Files.exists(negExec), "ISO_NEG.exec must exist");
        assertTrue(Files.exists(posExec), "ISO_POS.exec must exist");

        SortedSet<Integer> negLines = coveredLines(negExec);
        SortedSet<Integer> posLines = coveredLines(posExec);
        assertFalse(negLines.isEmpty(), "negative test must have coverage");
        assertFalse(posLines.isEmpty(), "positive test must have coverage");
        // ISOLATION: different branches → different covered line sets, no cross-contamination.
        assertFalse(negLines.equals(posLines),
                "two tests on different branches must yield different coverage; neg=" + negLines + " pos=" + posLines);
    }

    // ---- spec §5.1: sidecar json schema ----
    @Test
    void sidecarSchema() throws Exception {
        control("/__coverage__/test/start?testId=SIDE_A&shardId=shard-7");
        app("SIDE_A", "positive");
        control("/__coverage__/test/stop?testId=SIDE_A&result=passed");

        Path sidecar = COVERAGE.resolve("SIDE_A.json");
        assertTrue(Files.exists(sidecar), "SIDE_A.json sidecar must exist");
        String json = readFile(sidecar);
        assertContains(json, "\"testId\":\"SIDE_A\"");
        assertContains(json, "\"exec\":\"SIDE_A.exec\"");
        assertContains(json, "\"precision\":\"line\"");
        assertContains(json, "\"result\":\"passed\"");
        assertContains(json, "\"shardId\":\"shard-7\"");
        assertContains(json, "\"status\":\"complete\"");
        assertContains(json, "\"classCount\":1");
    }

    // ---- spec §5.2: global manifest header written at premain with commitSha ----
    @Test
    void manifestHeaderHasCommitSha() throws Exception {
        Path manifest = COVERAGE.resolve("manifest.json");
        assertTrue(Files.exists(manifest), "manifest.json header must exist");
        String json = readFile(manifest);
        assertContains(json, "\"schemaVersion\":1");
        assertContains(json, "\"precision\":\"line\"");
        assertContains(json, "\"commitSha\":\"e2e-deadbeef\"");   // from env PJACOCO_COMMIT
    }

    // ---- spec §2/§4.3: strict mode — unregistered testId is not recorded ----
    @Test
    void strictMode_unregisteredTestId_producesNoExec() throws Exception {
        // never call start for GHOST; just send a tagged request
        app("GHOST", "positive");
        // and a registered control stop for a never-started id is a no-op
        Path ghostExec = COVERAGE.resolve("GHOST.exec");
        assertFalse(Files.exists(ghostExec), "strict mode must not record an unregistered testId");
    }

    // ---- spec §4.3: untagged traffic is not recorded into an active test ----
    @Test
    void untaggedRequest_notRecorded() throws Exception {
        control("/__coverage__/test/start?testId=UNTAGGED");
        app(null, "positive");                       // no baggage header
        app(null, "negative");
        control("/__coverage__/test/stop?testId=UNTAGGED&result=passed");

        Path exec = COVERAGE.resolve("UNTAGGED.exec");
        assertTrue(Files.exists(exec), "stopped test still flushes a (possibly empty) exec");
        assertTrue(coveredLines(exec).isEmpty(),
                "untagged requests must not be recorded into the active test");
    }

    // ================= helpers =================

    private void control(String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + CONTROL_PORT + pathAndQuery).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(new byte[0]);
        os.close();
        assertEquals(200, c.getResponseCode(), "control call failed: " + pathAndQuery);
        c.getInputStream().close();
    }

    private void app(String testIdOrNull, String mode) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + appPort + "/run?mode=" + mode).openConnection();
        if (testIdOrNull != null) {
            c.setRequestProperty("baggage", "test.id=" + testIdOrNull);
        }
        assertEquals(200, c.getResponseCode(), "app request failed");
        c.getInputStream().close();
    }

    private static SortedSet<Integer> coveredLines(Path exec) throws Exception {
        byte[] cls = readResourceBytes("/" + TARGET_VM + ".class");
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

    private static String readFile(Path p) throws Exception {
        byte[] b = Files.readAllBytes(p);
        return new String(b, "UTF-8");
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), "expected to contain " + needle + " but was: " + haystack);
    }

    private static byte[] readResourceBytes(String res) throws Exception {
        InputStream in = SpecAcceptanceE2E.class.getResourceAsStream(res);
        assertNotNull(in, "resource not found: " + res);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
