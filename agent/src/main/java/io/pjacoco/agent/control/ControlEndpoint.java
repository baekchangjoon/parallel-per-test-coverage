package io.pjacoco.agent.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Loopback HTTP control plane: {@code POST /__coverage__/test/start|stop|trace/map}. */
public final class ControlEndpoint {
    private final TestStoreRegistry registry;
    private final TestIdMappingRegistry mapping;
    private final ExecWriter writer;
    private final AgentOptions options;
    private final String host;
    private final int port;
    private final AgentLog log;
    private HttpServer server;
    private ExecutorService executor;

    public ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping,
                           ExecWriter writer, AgentOptions options, String host, int port) {
        this(registry, mapping, writer, options, host, port, new AgentLog());
    }

    public ControlEndpoint(TestStoreRegistry registry, TestIdMappingRegistry mapping,
                           ExecWriter writer, AgentOptions options, String host, int port,
                           AgentLog log) {
        this.registry = registry;
        this.mapping = mapping;
        this.writer = writer;
        this.options = options;
        this.host = host;
        this.port = port;
        this.log = log;
    }

    /** @return the actual bound port. */
    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        warnIfNotLoopback(server.getAddress().getAddress());
        server.createContext("/__coverage__/test/start", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleStart(ex); }
        });
        server.createContext("/__coverage__/test/stop", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleStop(ex); }
        });
        server.createContext("/__coverage__/trace/map", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException { handleTraceMap(ex); }
        });
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pjacoco-control");
                t.setDaemon(true);
                return t;
            }
        });
        server.setExecutor(executor);
        // Start from a short-lived daemon thread: the JDK server's internal HTTP-Dispatcher thread
        // inherits daemon status from its creator. Started from premain (non-daemon) it would pin
        // the JVM forever — the shutdown hook that stops this server only runs once shutdown has
        // already begun, a cycle observed as a hang of any agent-attached JVM whose main returns.
        // Failure must propagate: the socket is already bound (HttpServer.create), so a swallowed
        // start() failure would leave a port that accepts into the backlog but never services —
        // and Bootstrap would advertise pjacoco.control-port for a dead endpoint.
        final HttpServer s = server;
        final java.util.concurrent.atomic.AtomicReference<Throwable> startFailure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread starter = new Thread(new Runnable() {
            public void run() {
                try {
                    s.start();
                } catch (Throwable t) {
                    startFailure.set(t);
                }
            }
        }, "pjacoco-control-start");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IOException("interrupted while starting the control endpoint");
        }
        Throwable failure = startFailure.get();
        if (failure != null) {
            stop();
            throw new IOException("control endpoint failed to start: " + failure, failure);
        }
        return server.getAddress().getPort();
    }

    private void warnIfNotLoopback(InetAddress bound) {
        if (bound != null && !bound.isLoopbackAddress()) {
            log.warn("bind", "control endpoint bound to non-loopback address " + bound.getHostAddress()
                    + " — it has no authentication; anyone who can reach it can start/stop/flush"
                    + " coverage collection");
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) {
            // Graceful first: shutdownNow() would interrupt a handler mid-persist and truncate the
            // .exec being written (Files/channel writes throw ClosedByInterruptException).
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return true when the request method is POST; otherwise responds 405 (all control-plane
     *  operations mutate state, and accepting GET made accidental/scripted state changes easy). */
    private boolean requirePost(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) return true;
        ex.getResponseHeaders().set("Allow", "POST");
        respond(ex, 405, "method not allowed (use POST)");
        return false;
    }

    private void handleStart(HttpExchange ex) throws IOException {
        if (!requirePost(ex)) return;
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }
        registry.start(testId, q.get("shardId"), q.get("commitSha"));
        respond(ex, 200, "started " + testId);
    }

    private void handleStop(HttpExchange ex) throws IOException {
        if (!requirePost(ex)) return;
        Map<String, String> q = query(ex);
        String testId = q.get("testId");
        if (testId == null) { respond(ex, 400, "missing testId"); return; }

        String format = q.getOrDefault("format", "text");
        // Validate BEFORE closing the store: a typo (e.g. "bianry") must not consume the
        // in-flight store, and the caller must learn its request was not understood.
        if (!"text".equalsIgnoreCase(format) && !"binary".equalsIgnoreCase(format)) {
            respond(ex, 400, "unsupported format '" + format + "' (use text or binary)");
            return;
        }
        boolean persist = parseBoolean(q.get("persist"), options.persistOnStop());

        TestStoreRegistry.StopResult closed = registry.closeForStop(testId, q.get("result"));
        if (closed == null) {
            // 404 on BOTH paths: the text path's old "200 stopped" for a never-started testId hid
            // stop failures from harnesses (the binary path already 404'd — asymmetric contract).
            respond(ex, 404, "unknown testId");
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
        if (!requirePost(ex)) return;
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
