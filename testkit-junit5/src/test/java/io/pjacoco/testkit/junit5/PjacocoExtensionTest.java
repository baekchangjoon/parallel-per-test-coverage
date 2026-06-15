package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.testkit.Pjacoco;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

class PjacocoExtensionTest {

    HttpServer server;
    final List<String> queries = new CopyOnWriteArrayList<String>();

    @SuppressWarnings("unused")
    void sampleMethod() { /* used only as a Method handle for the mocked context */ }

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

    private ExtensionContext mockContext(boolean failed) throws Exception {
        Method m = PjacocoExtensionTest.class.getDeclaredMethod("sampleMethod");
        ExtensionContext ctx = Mockito.mock(ExtensionContext.class);
        Mockito.<Class<?>>when(ctx.getRequiredTestClass()).thenReturn(PjacocoExtensionTest.class);
        when(ctx.getRequiredTestMethod()).thenReturn(m);
        when(ctx.getExecutionException()).thenReturn(failed ? Optional.<Throwable>of(new AssertionError("x")) : Optional.<Throwable>empty());
        return ctx;
    }

    @Test
    void beforeEachStartsAndSetsId_afterEachStopsAndClears() throws Exception {
        PjacocoExtension ext = new PjacocoExtension();
        ExtensionContext ctx = mockContext(false);

        ext.beforeEach(ctx);
        assertEquals("PjacocoExtensionTest#sampleMethod", Pjacoco.currentTestId(),
                "id must be active during the test");
        ext.afterEach(ctx);
        assertNull(Pjacoco.currentTestId(), "id must be cleared after the test");

        assertEquals(2, queries.size());
        assertEquals("/__coverage__/test/start?testId=PjacocoExtensionTest%23sampleMethod", queries.get(0));
        assertEquals("/__coverage__/test/stop?testId=PjacocoExtensionTest%23sampleMethod&result=passed", queries.get(1));
    }

    @Test
    void afterEachReportsFailedWhenExecutionExceptionPresent() throws Exception {
        PjacocoExtension ext = new PjacocoExtension();
        ExtensionContext ctx = mockContext(true);
        ext.beforeEach(ctx);
        ext.afterEach(ctx);
        assertEquals("/__coverage__/test/stop?testId=PjacocoExtensionTest%23sampleMethod&result=failed",
                queries.get(1));
    }
}
