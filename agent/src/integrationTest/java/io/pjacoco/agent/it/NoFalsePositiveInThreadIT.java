package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.DropAttributor;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoFalsePositiveInThreadIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
        CoverageBridge.bindMetrics(null);
        CoverageBridge.bindAttributor(null);
        ServletAdvice.registry = null;
        ServletAdvice.metrics = null;
        ServletAdvice.log = null;
        ServletAdvice.resetWarnGuardForTest();
    }

    private static TestStoreRegistry reg(Path dir, Metrics m) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), m, new AgentLog(),
                false, 100, new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    @Test
    @DisplayName("CLS-REQ-006: production code run on the test thread (context set) emits no drop signal")
    void directCall_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        CoverageBridge.bindMetrics(m);
        CoverageBridge.bindAttributor(new DropAttributor(registry, m));
        registry.start("T1", null, null);
        CoverageContext.set(registry.peek("T1"));     // in-thread: context present
        CoverageBridge.recordCoverage(String.class, 1L, 0);
        assertEquals(0L, m.droppedNoContext.get());
    }

    @Test
    @DisplayName("CLS-REQ-006: MockMvc-equivalent servlet dispatch on the test thread (context set) emits no missing-id")
    void contextSetServletDispatch_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T1", null, null);
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();
        TestStore store = registry.peek("T1");
        CoverageContext.set(store);                   // MockMvc dispatch runs on the test thread w/ context

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        ServletAdvice.activate(req);                  // context!=null -> gated out
        assertEquals(0L, m.missingTestIdInbound.get());
    }
}
