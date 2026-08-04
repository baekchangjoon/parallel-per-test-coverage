package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MM-E2E-2 (design §4.5): inbound baggage 헤더 3종 — 필드 헤더 2종 신규 인식 + W3C 무회귀 +
 * 우선순위 충돌. SUT: strict 모드(옵션 없음 = 기본), {@code traceKeyAutoCreate} 미설정, 트레이서
 * 없음(폴백 경로만 관측). SpecAcceptanceE2E와 같은 {@code e2eTest} Gradle 태스크가 부착하는
 * 실제 {@code -javaagent}와 같은 control 포트(6310)·{@code build/coverage} 출력 디렉터리를
 * 공유하므로, testId는 SpecAcceptanceE2E가 쓰지 않는 값만 사용한다(T1/T2/W1/WINNER/LOSER1/LOSER2).
 */
@Tag("e2e")
class MmBaggageHeadersE2E {

    static final int CONTROL_PORT = 6310;
    static final Path COVERAGE = Paths.get("build/coverage");
    static final String TARGET_VM = "com/example/app/TargetService";

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

    @Test
    @DisplayName("REQ-MM-001: test.id 필드 헤더로 per-test exec 산출")
    void fieldHeaderProducesPerTestExec() throws Exception {
        control("/__coverage__/test/start?testId=T1");
        appWithHeader("test.id", "T1");
        control("/__coverage__/test/stop?testId=T1&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("T1.exec")), "field header must route coverage to T1");
        assertCovered("T1.exec");
    }

    @Test
    @DisplayName("REQ-MM-002: baggage-test.id legacy 헤더로 per-test exec 산출")
    void legacyHeaderProducesPerTestExec() throws Exception {
        control("/__coverage__/test/start?testId=T2");
        appWithHeader("baggage-test.id", "T2");
        control("/__coverage__/test/stop?testId=T2&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("T2.exec")));
        assertCovered("T2.exec");
    }

    @Test
    @DisplayName("REQ-MM-003: W3C baggage 헤더 기존 동작 무회귀")
    void w3cBaggageUnchanged() throws Exception {
        control("/__coverage__/test/start?testId=W1");
        appWithHeader("baggage", "test.id=W1");
        control("/__coverage__/test/stop?testId=W1&result=passed");
        assertTrue(Files.exists(COVERAGE.resolve("W1.exec")));
        assertCovered("W1.exec");
    }

    @Test
    @DisplayName("REQ-MM-004: 3-way 충돌 시 W3C baggage 승리")
    void conflictW3cWins() throws Exception {
        control("/__coverage__/test/start?testId=WINNER");
        control("/__coverage__/test/start?testId=LOSER1");
        control("/__coverage__/test/start?testId=LOSER2");
        appWithHeaders(new String[][] {                        // 서로 다른 값의 3중 헤더
            {"baggage", "test.id=WINNER"}, {"test.id", "LOSER1"}, {"baggage-test.id", "LOSER2"}});
        control("/__coverage__/test/stop?testId=WINNER&result=passed");
        control("/__coverage__/test/stop?testId=LOSER1&result=passed");
        control("/__coverage__/test/stop?testId=LOSER2&result=passed");
        assertCovered("WINNER.exec");
        assertEmptyOrAbsent("LOSER1.exec");                    // strict라 빈 store는 파일 미생성일 수 있음
        assertEmptyOrAbsent("LOSER2.exec");
    }

    // ================= helpers (SpecAcceptanceE2E의 기동/제어 구조를 헤더 파라미터화) =================

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

    /** GET the app with a single custom header; assert 200 and that the servlet actually ran. */
    private void appWithHeader(String headerName, String headerValue) throws Exception {
        appWithHeaders(new String[][] {{headerName, headerValue}});
    }

    /** GET the app with an arbitrary set of headers (name/value pairs); assert 200 and "ok" body. */
    private void appWithHeaders(String[][] headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + appPort + "/run?mode=positive").openConnection();
        for (String[] h : headers) {
            c.setRequestProperty(h[0], h[1]);
        }
        assertEquals(200, c.getResponseCode(), "app request failed");
        assertEquals("ok", readStream(c.getInputStream()), "servlet must have actually handled the request");
    }

    /** Asserts the .exec exists and carries real fired probe data for TargetService. */
    private static void assertCovered(String fileName) throws Exception {
        Path exec = COVERAGE.resolve(fileName);
        assertTrue(Files.exists(exec), "expected exec file to exist: " + exec);
        ExecutionData ed = targetExecutionData(exec);
        assertTrue(ed != null, "exec must contain ExecutionData for TargetService: " + exec);
        boolean anySet = false;
        for (boolean p : ed.getProbes()) {
            if (p) { anySet = true; break; }
        }
        assertTrue(anySet, "TargetService must have at least one fired probe in " + exec);
    }

    /**
     * Asserts the losing testId's store never captured this request's coverage: either the .exec
     * was never written (strict mode may skip flushing an untouched store), or it exists but carries
     * no fired TargetService probes.
     */
    private static void assertEmptyOrAbsent(String fileName) throws Exception {
        Path exec = COVERAGE.resolve(fileName);
        if (!Files.exists(exec)) {
            return;
        }
        ExecutionData ed = targetExecutionData(exec);
        if (ed == null) {
            return;
        }
        for (boolean p : ed.getProbes()) {
            assertFalse(p, fileName + " must not carry fired TargetService probes (losing header must not win priority)");
        }
    }

    private static ExecutionData targetExecutionData(Path exec) throws Exception {
        ExecutionDataStore eds = readExec(exec);
        for (ExecutionData candidate : eds.getContents()) {
            if (TARGET_VM.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
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
}
