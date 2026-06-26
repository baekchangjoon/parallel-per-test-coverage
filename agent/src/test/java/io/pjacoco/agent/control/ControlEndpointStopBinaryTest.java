package io.pjacoco.agent.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlEndpointStopBinaryTest {
    private TestStoreRegistry registry;
    private ControlEndpoint endpoint;
    private int port;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        dir = tempDir;
        final AtomicLong clock = new AtomicLong(1000L);
        registry = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, clock::get);
        endpoint = new ControlEndpoint(registry, new TestIdMappingRegistry(1000),
                new ExecWriter(), AgentOptions.empty(), "127.0.0.1", 0);
        port = endpoint.start();
    }

    @AfterEach
    void tearDown() {
        endpoint.stop();
    }

    @Test
    void legacyTextStopStillWritesFiles() throws Exception {
        assertEquals(200, post("/__coverage__/test/start?testId=T1"));
        registry.active("T1").record(0x1L, "com/example/Foo", 0, 2);

        HttpResponse response = postFull("/__coverage__/test/stop?testId=T1&result=passed");
        assertEquals(200, response.code);
        assertEquals("stopped T1", response.body);

        Path exec = dir.resolve("T1.exec");
        assertTrue(Files.exists(exec));
        assertTrue(Files.size(exec) > 0);
        assertTrue(Files.exists(dir.resolve("T1.json")));
    }

    @Test
    void binaryStopReturnsExecBytes() throws Exception {
        assertEquals(200, post("/__coverage__/test/start?testId=T2"));
        TestStore store = registry.active("T2");
        store.record(0xABCDL, "com/example/Bar", 0, 3);
        store.record(0xABCDL, "com/example/Bar", 2, 3);

        HttpResponse response = postFull("/__coverage__/test/stop?testId=T2&format=binary&persist=false");
        assertEquals(200, response.code);
        assertEquals("application/octet-stream", response.contentType);

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(response.bodyStream());
        assertFalse(loader.getExecutionDataStore().getContents().isEmpty());
        assertFalse(Files.exists(dir.resolve("T2.exec")));
    }

    @Test
    void binaryStopEmptyStore204() throws Exception {
        assertEquals(200, post("/__coverage__/test/start?testId=T3"));

        HttpResponse response = postFull("/__coverage__/test/stop?testId=T3&format=binary");
        assertEquals(204, response.code);
        assertFalse(Files.exists(dir.resolve("T3.exec")));
    }

    @Test
    void binaryStopUnknown404() throws Exception {
        HttpResponse response = postFull("/__coverage__/test/stop?testId=unknown-id&format=binary");
        assertEquals(404, response.code);
        assertEquals("unknown testId", response.body);
    }

    private int post(String path) throws Exception {
        return postFull(path).code;
    }

    private HttpResponse postFull(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(new byte[0]);
        }
        int code = connection.getResponseCode();
        String contentType = connection.getContentType();
        InputStream bodyStream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] bodyBytes = bodyStream == null ? new byte[0] : readAllBytes(bodyStream);
        String body = new String(bodyBytes, "UTF-8");
        connection.disconnect();
        return new HttpResponse(code, contentType, body, bodyBytes);
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static final class HttpResponse {
        final int code;
        final String contentType;
        final String body;
        private final byte[] bodyBytes;

        private HttpResponse(int code, String contentType, String body, byte[] bodyBytes) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
            this.bodyBytes = bodyBytes;
        }

        InputStream bodyStream() {
            return new java.io.ByteArrayInputStream(bodyBytes);
        }
    }
}
