package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestStoreRegistryDiscardTest {

    private TestStoreRegistry newRegistry(Path dir) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 1000, () -> 1L);
    }

    @Test
    void peekReturnsRegisteredStoreWithoutSideEffects(@TempDir Path dir) {
        TestStoreRegistry reg = newRegistry(dir);
        reg.start("T1", null, null);
        assertNotNull(reg.peek("T1"), "peek must return the registered store");
        assertNull(reg.peek("MISSING"), "peek must return null for an unknown testId");
    }

    @Test
    void discardRemovesWithoutWritingAnyFile(@TempDir Path dir) {
        TestStoreRegistry reg = newRegistry(dir);
        reg.start("T1", null, null);
        reg.discard("T1");
        assertNull(reg.peek("T1"), "discard must remove the store");
        assertFalse(Files.exists(dir.resolve("T1.exec")), "discard must not write an .exec");
        assertFalse(Files.exists(dir.resolve("T1.json")), "discard must not write a sidecar");
    }
}
