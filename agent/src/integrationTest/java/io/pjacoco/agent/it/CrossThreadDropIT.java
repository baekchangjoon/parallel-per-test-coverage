package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class CrossThreadDropIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
    }

    @Test
    @DisplayName("CLS-REQ-003: probe on a delegated worker thread (no servlet) is caught by droppedNoContext")
    void asyncWorker_incrementsDroppedNoContext(@TempDir Path dir) throws Exception {
        Metrics m = new Metrics();
        final AtomicLong clock = new AtomicLong(1L);
        TestStoreRegistry registry = new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        registry.start("T1", null, null);

        // simulate @Async/Executor: work runs on a child thread that never had a CoverageContext set
        Thread worker = new Thread(() -> {
            CoverageContext.clear();                  // worker has no context
            CoverageBridge.recordCoverage(String.class, 7L, 0);
        });
        worker.start();
        worker.join();

        assertEquals(1L, m.droppedNoContext.get());
        assertTrue(registry.peek("T1").droppedProbes() >= 1);
    }
}
