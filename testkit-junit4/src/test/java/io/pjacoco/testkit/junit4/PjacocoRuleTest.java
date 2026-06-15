package io.pjacoco.testkit.junit4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.testkit.Pjacoco;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Drives {@link PjacocoRule} via its JUnit 4 TestWatcher API; assertions run on the JUnit 5 platform. */
class PjacocoRuleTest {

    static class SampleSuite { }

    HttpServer server;
    final List<String> queries = new CopyOnWriteArrayList<String>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__coverage__/test/", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                queries.add(ex.getRequestURI().getPath() + "?" + ex.getRequestURI().getRawQuery());
                ex.sendResponseHeaders(200, -1);
                ex.close();
            }
        });
        server.start();
        System.setProperty(Pjacoco.CONTROL_URL_PROPERTY, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(Pjacoco.CONTROL_URL_PROPERTY);
        Pjacoco.clearCurrentTestId();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void passingTest_startsThenStopsPassed_andClears() throws Throwable {
        PjacocoRule rule = new PjacocoRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "doesThing");
        final String[] idDuring = new String[1];
        Statement base = new Statement() {
            public void evaluate() { idDuring[0] = Pjacoco.currentTestId(); }
        };
        rule.apply(base, desc).evaluate();

        assertEquals("SampleSuite#doesThing", idDuring[0], "id active during the test");
        assertNull(Pjacoco.currentTestId(), "id cleared after the test");
        assertEquals(2, queries.size());
        assertEquals("/__coverage__/test/start?testId=SampleSuite%23doesThing", queries.get(0));
        assertEquals("/__coverage__/test/stop?testId=SampleSuite%23doesThing&result=passed", queries.get(1));
    }

    @Test
    void failingTest_stopsFailed() {
        PjacocoRule rule = new PjacocoRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "boom");
        Statement base = new Statement() {
            public void evaluate() { throw new AssertionError("boom"); }
        };
        assertThrows(AssertionError.class, () -> rule.apply(base, desc).evaluate());
        assertEquals("/__coverage__/test/stop?testId=SampleSuite%23boom&result=failed", queries.get(1));
        assertNull(Pjacoco.currentTestId());
    }
}
