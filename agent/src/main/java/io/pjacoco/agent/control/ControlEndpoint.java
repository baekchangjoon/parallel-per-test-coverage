package io.pjacoco.agent.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** Loopback HTTP control plane: {@code POST /__coverage__/test/start|stop|trace/map}. */
public final class ControlEndpoint {
    private final TestStoreRegistry registry;
    private final TestIdMappingRegistry mapping;
    private final String host;
    private final int port;
    private HttpServer server;

    public ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping, String host, int port) {
        this.registry = registry;
        this.mapping = mapping;
        this.host = host;
        this.port = port;
    }

    /** @return the actual bound port. */
    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/__coverage__/test/start", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleStart(ex); }
        });
        server.createContext("/__coverage__/test/stop", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleStop(ex); }
        });
        server.createContext("/__coverage__/trace/map", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleTraceMap(ex); }
        });
        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handleStart(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }
        registry.start(testId, q.get("shardId"), q.get("commitSha"));
        respond(ex, 200, "started " + testId);
    }

    private void handleStop(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }
        registry.stop(testId, q.get("result"));
        respond(ex, 200, "stopped " + testId);
    }

    private void handleTraceMap(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String traceId = q.get("traceId");
        String testId = q.get("testId");
        if (traceId == null || testId == null) { respond(ex, 400, "missing traceId or testId"); return; }
        mapping.register(traceId, testId);
        respond(ex, 200, "mapped " + traceId + " -> " + testId);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<String, String>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                try {
                    m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                          URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
                } catch (Exception ignored) { /* skip malformed pair */ }
            }
        }
        return m;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        ex.close();
    }
}
