package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestStoreRegistryTest {
    private TestStoreRegistry newRegistry(Path dir, boolean lenient) {
        final AtomicLong clock = new AtomicLong(1000L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                lenient, 100, new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                });
    }

    @Test
    void startThenStopWritesExec(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        r.start("T1", "shard-1", "sha");
        r.active("T1").record(1L, "A", 0, 1);
        r.stop("T1", "passed");
        assertTrue(Files.exists(dir.resolve("T1.exec")));
        assertNull(r.active("T1"));                 // evicted after stop (strict: now unregistered)
    }

    @Test
    void strictModeReturnsNullForUnregistered(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        assertNull(r.active("UNKNOWN"));
    }

    @Test
    void lenientModeAutoCreates(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, true);
        assertNotNull(r.active("AUTO"));
    }

    @Test
    void retryResetsAndBumpsCount(@TempDir Path dir) {
        TestStoreRegistry r = newRegistry(dir, false);
        r.start("T1", null, "sha");
        r.active("T1").record(1L, "A", 0, 1);
        r.start("T1", null, "sha");                 // retry
        assertEquals(0, r.active("T1").classCount());
        assertEquals(1, r.active("T1").retryCount());
    }
}
