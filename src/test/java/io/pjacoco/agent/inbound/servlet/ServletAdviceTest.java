package io.pjacoco.agent.inbound.servlet;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServletAdviceTest {
    @AfterEach void clear() { CoverageContext.clear(); ServletAdvice.registry = null; }

    private TestStoreRegistry reg(Path dir, boolean lenient) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                lenient, 100, new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                });
    }

    @Test
    void activatesResolvedStoreFromBaggage(@TempDir Path dir) {
        TestStoreRegistry r = reg(dir, false);
        r.start("T1", null, "sha");
        ServletAdvice.registry = r;
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T1");

        ServletAdvice.activate(req);
        assertSame(r.active("T1"), CoverageContext.get());
        ServletAdvice.deactivate();
        assertNull(CoverageContext.get());
    }

    @Test
    void strictUnregisteredLeavesContextUnset(@TempDir Path dir) {
        ServletAdvice.registry = reg(dir, false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=GHOST");
        ServletAdvice.activate(req);
        assertNull(CoverageContext.get());
    }

    @Test
    void noHeaderOrNonHttpIgnored(@TempDir Path dir) {
        ServletAdvice.registry = reg(dir, false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);
        ServletAdvice.activate(req);
        assertNull(CoverageContext.get());
        ServletAdvice.activate(new Object());           // not an HttpServletRequest
        assertNull(CoverageContext.get());
    }
}
