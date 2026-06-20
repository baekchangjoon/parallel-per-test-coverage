package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.store.TestStore;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecWriterTest {
    @Test
    void writesExecReadableByJacocoAndSidecar(@TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T1", 1000L, "shard-3");
        store.record(99L, "com/example/Foo", 0, 2);
        store.record(99L, "com/example/Foo", 1, 2);

        new ExecWriter().write(dir, store, "passed", "abc123", 2000L);

        Path sidecar = dir.resolve("T1.json");
        assertTrue(Files.exists(sidecar));
        String json = new String(Files.readAllBytes(sidecar), "UTF-8");
        assertTrue(json.contains("\"testId\":\"T1\""));
        assertTrue(json.contains("\"result\":\"passed\""));
        assertTrue(json.contains("\"shardId\":\"shard-3\""));
        assertTrue(json.contains("\"classCount\":1"));
        assertTrue(json.contains("\"status\":\"complete\""));

        Path exec = dir.resolve("T1.exec");
        assertTrue(Files.exists(exec));
        ExecutionDataStore store2 = new ExecutionDataStore();
        SessionInfoStore sessions = new SessionInfoStore();
        InputStream in = Files.newInputStream(exec);
        try {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setExecutionDataVisitor(store2);
            r.setSessionInfoVisitor(sessions);
            r.read();
        } finally {
            in.close();
        }
        ExecutionData ed = store2.get(99L);
        assertNotNull(ed);
        assertEquals("com/example/Foo", ed.getName());
        assertArrayEquals(new boolean[]{true, true}, ed.getProbes());
        assertEquals("T1", sessions.getInfos().get(0).getId());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-008: no drop -> attribution fields omitted")
    void noDrop_omitsAttributionFields(@TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T_ND", 1000L, null);
        store.record(1L, "com/example/Foo", 0, 1);
        new ExecWriter().write(dir, store, "passed", null, 2000L);
        String json = new String(Files.readAllBytes(dir.resolve("T_ND.json")), "UTF-8");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("incompleteAttribution"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("droppedProbes"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"attribution\""), json);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-008: drop>0 -> attribution fields emitted")
    void withDrop_emitsAttributionFields(@TempDir Path dir) throws Exception {
        TestStore store = new TestStore("T_D", 1000L, null);
        store.recordDrop();
        store.recordDrop();
        new ExecWriter().write(dir, store, "passed", null, 2000L);
        String json = new String(Files.readAllBytes(dir.resolve("T_D.json")), "UTF-8");
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
        assertTrue(json.contains("\"droppedProbes\":2"), json);
        assertTrue(json.contains("\"attribution\":\"exact\""), json);
    }
}
