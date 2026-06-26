package io.pjacoco.agent.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
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
    private final ExecWriter writer;
    private final AgentOptions options;
    private final String host;
    private final int port;
    private HttpServer server;

    public ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping,
                           ExecWriter writer, AgentOptions options, String host, int port) {
        this.registry = registry;
        this.mapping = mapping;
        this.writer = writer;
        this.options = options;
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

        String format = q.getOrDefault("format", "text");
        boolean persist = parseBoolean(q.get("persist"), options.persistOnStop());

        TestStoreRegistry.StopResult closed = registry.closeForStop(testId, q.get("result"));
        if (closed == null) {
            if ("binary".equalsIgnoreCase(format)) {
                respond(ex, 404, "unknown testId");
            } else {
                respond(ex, 200, "stopped " + testId);
            }
            return;
        }

        if ("binary".equalsIgnoreCase(format)) {
            handleBinaryStop(ex, closed, persist);
            return;
        }

        registry.persistClosed(closed, true, true);
        registry.markStopCompleted();
        respond(ex, 200, "stopped " + testId);
    }

    private void handleBinaryStop(HttpExchange ex, TestStoreRegistry.StopResult closed,
                                  boolean persist) throws IOException {
        TestStore store = closed.snapshot();
        setBinaryHeaders(ex, closed, persist);
        if (closed.wasEmpty()) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            registry.markStopCompleted();
            return;
        }
        try {
            byte[] execBytes = writer.toExecBytes(store, System.currentTimeMillis());
            if (persist) {
                registry.persistClosed(closed, true, false);
            }
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, execBytes.length);
            OutputStream os = ex.getResponseBody();
            os.write(execBytes);
            os.close();
            registry.markStopCompleted();
        } catch (Exception e) {
            respond(ex, 500, "serialization error: " + e.getMessage());
            registry.markStopCompleted();
        }
    }

    private void setBinaryHeaders(HttpExchange ex, TestStoreRegistry.StopResult closed, boolean persist) {
        TestStore store = closed.snapshot();
        ex.getResponseHeaders().set("X-Pjacoco-TestId", closed.testId());
        ex.getResponseHeaders().set("X-Pjacoco-ClassCount", String.valueOf(store.classCount()));
        ex.getResponseHeaders().set("X-Pjacoco-RecordedProbes",
                String.valueOf(writer.countRecordedProbes(store)));
        ex.getResponseHeaders().set("X-Pjacoco-DroppedProbes", String.valueOf(store.droppedProbes()));
        ex.getResponseHeaders().set("X-Pjacoco-Persisted", String.valueOf(persist));
    }

    private void handleTraceMap(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String traceId = q.get("traceId");
        String testId = q.get("testId");
        if (traceId == null || testId == null) { respond(ex, 400, "missing traceId or testId"); return; }
        mapping.register(traceId, testId);
        respond(ex, 200, "mapped " + traceId + " -> " + testId);
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
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
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }
}
