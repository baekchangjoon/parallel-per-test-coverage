package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("CLS-REQ-005: drop while >=2 stores active -> all flagged conservative, no loss")
    void concurrentDrops_flaggedConservative_noLoss(@TempDir Path dir) {
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

        assertTrue(registry.peek("A").droppedProbes() >= 1 && registry.peek("A").attributionConservative(),
                "A must be flagged conservative");
        assertTrue(registry.peek("B").droppedProbes() >= 1 && registry.peek("B").attributionConservative(),
                "B must be flagged conservative");
    }
}
