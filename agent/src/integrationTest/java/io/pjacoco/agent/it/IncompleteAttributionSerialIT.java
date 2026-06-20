package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncompleteAttributionSerialIT {
    private static TestStoreRegistry reg(Path dir) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-004: worker-only test (classCount=0, droppedProbes>0) is flagged exact, not discarded")
    void workerOnlyTest_flaggedExact_notDiscarded(@TempDir Path dir) throws Exception {
        TestStoreRegistry registry = reg(dir);
        CoverageControl.bindRegistry(registry);
        registry.start("T1", null, null);
        registry.peek("T1").recordDrop();             // worker dropped; test thread recorded nothing

        CoverageControl.deactivate("T1", "passed");

        Path j = dir.resolve("T1.json");
        assertTrue(Files.exists(j), "sidecar must be written for a drop-only test");
        String json = new String(Files.readAllBytes(j), "UTF-8");
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
        assertTrue(json.contains("\"attribution\":\"exact\""), json);
    }

    @Test
    @DisplayName("CLS-REQ-004: truly empty store (no class, no drop) is discarded")
    void trulyEmpty_discarded(@TempDir Path dir) {
        TestStoreRegistry registry = reg(dir);
        CoverageControl.bindRegistry(registry);
        registry.start("T2", null, null);
        CoverageControl.deactivate("T2", "passed");
        assertFalse(Files.exists(dir.resolve("T2.json")), "truly empty store must not write a sidecar");
    }
}
