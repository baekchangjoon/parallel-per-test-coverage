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

class IncompleteAttributionParallelIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    @Test
    @DisplayName("CLS-REQ-005: drop while >=2 stores active -> NOT per-test flagged, counted ambiguous")
    void concurrentDrops_notPerTestFlagged_countedAmbiguous(@TempDir Path dir) {
        Metrics m = new Metrics();
        final AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry registry = new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));

        registry.start("A", null, null);
        registry.start("B", null, null);              // two concurrently active stores
        CoverageContext.clear();                      // this thread has no context -> drop
        CoverageBridge.recordCoverage(String.class, 1L, 0);

        // Revised P2-4: an ambiguous drop (≥2 active) is NOT blamed on any one test...
        assertEquals(0L, registry.peek("A").droppedProbes(), "ambiguous drop must not be attributed to A");
        assertEquals(0L, registry.peek("B").droppedProbes(), "ambiguous drop must not be attributed to B");
        // ...but the loss is still visible globally (no silent loss).
        assertEquals(1L, m.droppedNoContext.get(), "global no-context drop counter still increments");
        assertEquals(1L, m.ambiguousDrops.get(), "the drop is counted as ambiguous (≥2 active)");
    }
}
