package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanProbeCounterIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    private static TestStoreRegistry reg(Path dir, Metrics m) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-002: no-context drop increments droppedNoContext; unattributed when no active store")
    void noContextThread_incrementsDroppedNoContext(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        CoverageContext.clear();

        // no active store -> droppedNoContext + unattributedDrops
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(1L, m.droppedNoContext.get());
        assertEquals(1L, m.unattributedDrops.get());

        // active store present, but this thread has no context -> attributed to the store, not unattributed
        registry.start("T1", null, null);
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(2L, m.droppedNoContext.get());
        assertEquals(1L, m.unattributedDrops.get());
        assertEquals(1L, registry.peek("T1").droppedProbes());
    }
}
