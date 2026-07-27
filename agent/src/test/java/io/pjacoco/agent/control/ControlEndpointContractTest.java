package io.pjacoco.agent.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the 2026-07-27 dogfooding findings, each encoding the way the
 * defect was discovered:
 * <ul>
 *   <li>BUG-1: a plain app JVM with only the agent attached hung after main returned;
 *       jstack showed a non-daemon {@code HTTP-Dispatcher} thread from this endpoint.</li>
 *   <li>BUG-5: {@code POST /test/stop?testId=NEVER} (no prior start) returned a false
 *       {@code 200 "stopped"} on the text path while the binary path correctly 404s.</li>
 *   <li>BUG-6: a typoed {@code format=bianry} was silently treated as the text path.</li>
 *   <li>U7: mutating endpoints accepted GET (no request-method validation).</li>
 * </ul>
 */
class ControlEndpointContractTest {
    private TestStoreRegistry registry;
    private ControlEndpoint endpoint;
    private int port;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        final AtomicLong clock = new AtomicLong(1000L);
        registry = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                });
        endpoint = new ControlEndpoint(registry, new TestIdMappingRegistry(1000),
                new ExecWriter(), AgentOptions.empty(), "127.0.0.1", 0);
        port = endpoint.start();
    }

    @AfterEach
    void tearDown() { endpoint.stop(); }

    private int request(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if ("POST".equals(method)) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(new byte[0]);
            os.close();
        }
        return c.getResponseCode();
    }

    /** BUG-1: every live thread belonging to the control server must be daemon, or a plain
     *  {@code java -javaagent:... MainThatReturns} JVM never exits (the shutdown hook that would
     *  stop the server only runs once shutdown has STARTED — a cycle). Discovery method: jstack
     *  of the hung JVM showed {@code "HTTP-Dispatcher"} as the only non-daemon thread left. */
    @Test
    void controlServerThreadsAreDaemon() {
        boolean foundDispatcher = false;
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String name = t.getName();
            if (name.contains("HTTP-Dispatcher") || name.startsWith("pjacoco-control")) {
                foundDispatcher = true;
                assertTrue(t.isDaemon(), "control server thread '" + name
                        + "' must be daemon so an agent-only JVM can exit (BUG-1)");
            }
        }
        assertTrue(foundDispatcher, "expected to find the control server's dispatcher/executor thread");
    }

    /** BUG-5: text stop for a testId that was never started must be 404, matching the binary
     *  path — a false 200 hides stop failures from harnesses. */
    @Test
    void textStopForUnknownTestIdIs404() throws Exception {
        assertEquals(404, request("POST", "/__coverage__/test/stop?testId=NEVER-STARTED"));
    }

    /** BUG-6: an unsupported format value (e.g. the typo "bianry") must be rejected with 400,
     *  not silently treated as the text path — and must not consume the in-flight store. */
    @Test
    void unknownStopFormatIs400AndKeepsStoreActive() throws Exception {
        assertEquals(200, request("POST", "/__coverage__/test/start?testId=T1"));
        assertEquals(400, request("POST", "/__coverage__/test/stop?testId=T1&format=bianry"));
        assertNotNull(registry.active("T1"), "a rejected stop must not close the store");
        // 204: the store is still open (empty, no probes recorded) — the correctly spelled
        // format can still close it after the rejected attempt.
        assertEquals(204, request("POST", "/__coverage__/test/stop?testId=T1&format=binary"));
    }

    /** U7(2): the endpoint has no authentication, so binding beyond loopback must at least warn
     *  (discovered as a silent {@code address=0.0.0.0} risk during the endpoint security review). */
    @Test
    void nonLoopbackBindWarnsAboutMissingAuth() throws Exception {
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
        ControlEndpoint open = new ControlEndpoint(registry, new TestIdMappingRegistry(1000),
                new ExecWriter(), AgentOptions.empty(), "0.0.0.0", 0);
        try {
            System.setErr(new java.io.PrintStream(errBuf, true));
            open.start();
        } finally {
            System.setErr(err);
            open.stop();
        }
        assertTrue(errBuf.toString().contains("non-loopback"),
                "binding a non-loopback address must warn that the endpoint is unauthenticated");
    }

    /** U7: mutating endpoints must reject non-POST methods with 405 and have no side effect
     *  (discovered by observing that GET /test/start returned 200 and opened a store). */
    @Test
    void getMethodIs405AndHasNoSideEffect() throws Exception {
        assertEquals(405, request("GET", "/__coverage__/test/start?testId=G1"));
        assertNull(registry.active("G1"), "a rejected GET start must not open a store");
        assertEquals(405, request("GET", "/__coverage__/test/stop?testId=G1"));
        assertEquals(405, request("GET", "/__coverage__/trace/map?traceId=t&testId=G1"));
    }
}
