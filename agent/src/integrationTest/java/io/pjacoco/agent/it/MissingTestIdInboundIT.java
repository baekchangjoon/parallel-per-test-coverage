package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissingTestIdInboundIT {
    @AfterEach
    void reset() {
        CoverageContext.clear();
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
    @DisplayName("CLS-REQ-001: no-baggage request during a collection window counts each time, warns once")
    void missingId_increments_and_warnsOnce(@TempDir Path dir) throws Exception {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T1", null, null);            // active store -> collection window open
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);

        PrintStream origErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, "UTF-8"));
        try {
            CoverageContext.clear();
            ServletAdvice.activate(req);
            ServletAdvice.activate(req);
        } finally {
            System.setErr(origErr);
        }
        assertEquals(2L, m.missingTestIdInbound.get());          // counter every time
        String err = buf.toString("UTF-8");
        int occurrences = err.split("no test.id", -1).length - 1;
        assertEquals(1, occurrences, "WARN must be logged exactly once: " + err);
    }

    @Test
    @DisplayName("CLS-REQ-001: request with valid baggage produces no missing-id signal")
    void withBaggage_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);
        registry.start("T_BAG", null, null);
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T_BAG");
        CoverageContext.clear();
        ServletAdvice.activate(req);
        assertEquals(0L, m.missingTestIdInbound.get());
    }

    @Test
    @DisplayName("CLS-REQ-001: no active store (startup) -> no missing-id signal")
    void noActiveStore_noSignal(@TempDir Path dir) {
        Metrics m = new Metrics();
        TestStoreRegistry registry = reg(dir, m);       // no start() -> no active store
        ServletAdvice.registry = registry;
        ServletAdvice.metrics = m;
        ServletAdvice.log = new AgentLog();

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        CoverageContext.clear();
        ServletAdvice.activate(req);
        assertEquals(0L, m.missingTestIdInbound.get());
    }
}
