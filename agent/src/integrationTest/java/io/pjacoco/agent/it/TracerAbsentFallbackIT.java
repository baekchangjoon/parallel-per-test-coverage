package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-007 + REQ-019: when no tracer is active (OTel absent from classpath, Brave has no current
 * Tracing instance), activation falls back to the {@code baggage} header's {@code test.id} and
 * increments {@link Metrics#fallbackActivations}.
 *
 * <p>No ByteBuddy self-attach is needed — we call {@link ServletAdvice#activate} directly, which
 * is the same code path the woven advice executes.  OTel is NOT on the integrationTest classpath
 * (NoTracerAttachIT documents this); Brave IS but has no current Tracing instance.
 */
class TracerAbsentFallbackIT {

    @AfterEach
    void reset() {
        CoverageContext.clear();
        ServletAdvice.registry = null;
        ServletAdvice.metrics  = null;
    }

    private static TestStoreRegistry reg(Path dir) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(
                dir, new ExecWriter(), new Metrics(), new AgentLog(),
                /* lenient= */ false, /* maxStores= */ 100,
                new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    /**
     * REQ-007 + REQ-019: with no tracer context active, a baggage-carrying request binds the
     * correct store and increments {@code fallbackActivations} exactly once.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("REQ-007 + REQ-019: no-tracer baggage fallback binds store and increments counter")
    void noTracerBaggageFallbackBindsStoreAndIncrementsCounter(@TempDir Path dir) {
        TestStoreRegistry registry = reg(dir);
        registry.start("T_BAG", null, null);

        Metrics metrics = new Metrics();
        ServletAdvice.registry = registry;
        ServletAdvice.metrics  = metrics;

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T_BAG");

        ServletAdvice.activate(req);

        TestStore store = CoverageContext.get();
        assertNotNull(store, "context must be bound via baggage fallback when no tracer is active");
        assertEquals("T_BAG", store.testId(), "store testId must match the baggage value");
        assertEquals(1L, metrics.fallbackActivations.get(),
                "fallbackActivations must be incremented exactly once for a baggage fallback");

        ServletAdvice.deactivate();
        assertNull(CoverageContext.get(), "deactivate must clear the context unconditionally");
    }

    /**
     * REQ-007: a request with no baggage header and no tracer context does NOT bind a store and
     * does NOT increment {@code fallbackActivations}.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("REQ-007: no-baggage no-tracer request leaves context unset and counter unchanged")
    void noBaggageNoTracerDoesNotBindAndDoesNotIncrement(@TempDir Path dir) {
        TestStoreRegistry registry = reg(dir);
        registry.start("T_BAG", null, null);

        Metrics metrics = new Metrics();
        ServletAdvice.registry = registry;
        ServletAdvice.metrics  = metrics;

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn(null);

        ServletAdvice.activate(req);

        assertNull(CoverageContext.get(), "context must remain unset when there is no baggage and no tracer");
        assertEquals(0L, metrics.fallbackActivations.get(),
                "fallbackActivations must not be incremented when no key is resolved");
    }
}
