package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.TraceCoverageMerger;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceMapEndpointIT {

    private static int post(int port, String pathAndQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + pathAndQuery).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static void writeExec(Path dir, String key) throws Exception {
        Files.createDirectories(dir);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(9L, "com/x/Svc", new boolean[] { true }));
        }
    }

    @Test
    void registeredShown(@TempDir Path dir) throws Exception {                  // REQ-011 acceptance #1
        TestIdMappingRegistry mapping = new TestIdMappingRegistry(100);
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 1000, System::currentTimeMillis);
        ControlEndpoint ep = new ControlEndpoint(reg, mapping, new ExecWriter(), AgentOptions.empty(),
                "127.0.0.1", 0);
        int port = ep.start();
        try {
            // 1) register a mapping THROUGH the HTTP control plane (%23 -> '#')
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=4bf92f&testId=com.x.T%23m"));
            assertEquals("com.x.T#m", mapping.testIdFor("4bf92f"));            // registered + normalized
            assertEquals(400, post(port, "/__coverage__/trace/map?traceId=onlytrace"));  // missing testId
        } finally {
            ep.stop();
        }
        // 2) report-time: a per-trace .exec keyed by the raw traceId, merged with the registered mapping,
        //    surfaces under the FQCN#method testId (full register -> merge -> report path, REQ-011).
        Path in = dir.resolve("traces"), out = dir.resolve("report");
        writeExec(in, "4bf92f");
        new TraceCoverageMerger().merge(in, mapping, out, new Metrics());
        assertTrue(Files.exists(out.resolve("com.x.T#m.exec")), "merged report keyed by registered testId");
    }

    @Test
    void boundedEvictionThroughEndpoint(@TempDir Path dir) throws Exception {   // REQ-011 acceptance #2
        TestIdMappingRegistry mapping = new TestIdMappingRegistry(2);           // cap = 2
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(),
                new AgentLog(), false, 1000, System::currentTimeMillis);
        ControlEndpoint ep = new ControlEndpoint(reg, mapping, new ExecWriter(), AgentOptions.empty(),
                "127.0.0.1", 0);
        int port = ep.start();
        try {
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t1&testId=com.x.T%23a"));
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t2&testId=com.x.T%23b"));
            assertEquals(200, post(port, "/__coverage__/trace/map?traceId=t3&testId=com.x.T%23c"));  // evicts t1
            assertEquals(2, mapping.size(), "store stays within the bound under sustained registration");
            assertNull(mapping.testIdFor("t1"), "eldest (t1) evicted");
            assertEquals("com.x.T#c", mapping.testIdFor("t3"));
        } finally {
            ep.stop();
        }
    }
}
