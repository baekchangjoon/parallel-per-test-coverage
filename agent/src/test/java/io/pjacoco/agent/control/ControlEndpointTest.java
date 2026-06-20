package io.pjacoco.agent.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlEndpointTest {
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
        endpoint = new ControlEndpoint(registry, new TestIdMappingRegistry(1000), "127.0.0.1", 0);   // 0 = ephemeral
        port = endpoint.start();
    }

    @AfterEach
    void tearDown() { endpoint.stop(); }

    private int post(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(new byte[0]);
        os.close();
        return c.getResponseCode();
    }

    @Test
    void startThenStopRoundTrip() throws Exception {
        assertEquals(200, post("/__coverage__/test/start?testId=T1&shardId=s1&commitSha=sha"));
        assertNotNull(registry.active("T1"));
        assertEquals(200, post("/__coverage__/test/stop?testId=T1&result=passed"));
        assertNull(registry.active("T1"));
    }

    @Test
    void missingTestIdIs400() throws Exception {
        assertEquals(400, post("/__coverage__/test/start"));
    }
}
