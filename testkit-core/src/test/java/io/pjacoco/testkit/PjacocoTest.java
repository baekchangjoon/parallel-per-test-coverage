package io.pjacoco.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PjacocoTest {

    static final class Captured {
        final String path;
        final String query;
        Captured(String path, String query) { this.path = path; this.query = query; }
    }

    HttpServer server;
    final List<Captured> received = new CopyOnWriteArrayList<Captured>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__coverage__/test/", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                received.add(new Captured(ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery()));
                byte[] body = "ok".getBytes("UTF-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
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
    void enabledReflectsControlUrlProperty() {
        assertTrue(Pjacoco.enabled());
        System.clearProperty(Pjacoco.CONTROL_URL_PROPERTY);
        assertFalse(Pjacoco.enabled());
        System.setProperty(Pjacoco.CONTROL_URL_PROPERTY, "   ");
        assertFalse(Pjacoco.enabled(), "blank control-url must count as disabled");
    }

    @Test
    void threadLocalAndBaggage() {
        assertNull(Pjacoco.currentTestId());
        assertNull(Pjacoco.baggageHeaderValue());
        Pjacoco.setCurrentTestId("MyTest#it_works");
        assertEquals("MyTest#it_works", Pjacoco.currentTestId());
        assertEquals("test.id=MyTest#it_works", Pjacoco.baggageHeaderValue());
        Pjacoco.clearCurrentTestId();
        assertNull(Pjacoco.currentTestId());
    }

    @Test
    void childThreadInheritsTestId() throws Exception {
        Pjacoco.setCurrentTestId("Parent#spawns");
        final String[] seen = new String[1];
        Thread t = new Thread(new Runnable() {
            public void run() { seen[0] = Pjacoco.currentTestId(); }
        });
        t.start();
        t.join();
        assertEquals("Parent#spawns", seen[0], "spawned thread must inherit the active test id");
    }

    @Test
    void startUrlEncodesTestIdSoTheHashIsNotAFragment() {
        Pjacoco.start("OwnerControllerTests#testInitCreationForm", "shard-1");
        assertEquals(1, received.size());
        Captured c = received.get(0);
        assertEquals("/__coverage__/test/start", c.path);
        // The '#' MUST be percent-encoded; otherwise it would be a URL fragment and the method name lost.
        assertEquals("testId=OwnerControllerTests%23testInitCreationForm&shardId=shard-1", c.query);
    }

    @Test
    void stopUrlEncodesTestIdAndResult() {
        Pjacoco.stop("A#b", "passed");
        assertEquals(1, received.size());
        Captured c = received.get(0);
        assertEquals("/__coverage__/test/stop", c.path);
        assertEquals("testId=A%23b&result=passed", c.query);
    }

    @Test
    void disabledWhenNoControlUrl_isNoOp() {
        System.clearProperty(Pjacoco.CONTROL_URL_PROPERTY);
        Pjacoco.start("A#b", null);
        Pjacoco.stop("A#b", "passed");
        assertEquals(0, received.size(), "no control calls must be made when disabled");
    }
}
