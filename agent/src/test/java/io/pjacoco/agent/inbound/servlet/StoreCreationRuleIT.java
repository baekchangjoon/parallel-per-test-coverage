package io.pjacoco.agent.inbound.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.BraveTestIdSource;
import io.pjacoco.agent.trace.OtelTestIdSource;
import io.pjacoco.agent.trace.TestIdSource;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-MM-006: with {@code traceKeyAutoCreate=true} (+ strict mode), only tracer-derived keys may
 * auto-create a store. Baggage-fallback-derived keys must follow the existing registry contract
 * (strict/lenient via {@link TestStoreRegistry#active(String)}) — no auto-create.
 */
class StoreCreationRuleIT {

    private static final java.util.List<TestIdSource> DEFAULT_TRACE_SOURCES =
            Arrays.<TestIdSource>asList(new OtelTestIdSource(), new BraveTestIdSource());

    @AfterEach
    void clear() {
        CoverageContext.clear();
        ServletAdvice.registry = null;
        ServletAdvice.metrics = null;
        ServletAdvice.traceSources = DEFAULT_TRACE_SOURCES;
    }

    /**
     * Shares {@code metrics} with the registry (rather than each constructing/holding its own) so
     * that tests can assert on registry-internal accounting (e.g. {@code rejectedUnregistered})
     * through the same instance bound to {@link ServletAdvice#metrics}.
     */
    private TestStoreRegistry strictAutoCreateRegistry(Path dir, Metrics metrics) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                /* lenient= */ false, 100, new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                }, /* traceKeyAutoCreate= */ true);
    }

    private static javax.servlet.http.HttpServletRequest requestWithHeaders(
            java.util.Map<String, String> headers) {
        javax.servlet.http.HttpServletRequest req =
                org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
        for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
            org.mockito.Mockito.when(req.getHeader(e.getKey())).thenReturn(e.getValue());
        }
        return req;
    }

    private static java.util.Map<String, String> map(String... kv) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Given traceKeyAutoCreate=true + strict, an un-started baggage-derived key must not auto-create. */
    @Test
    void baggageKeySkipsAutoCreate(@TempDir Path dir) {
        // Shared instance: the registry's strict-rejection accounting must be observable through
        // the same Metrics bound to ServletAdvice.metrics (REQ-MM-006's "기존 strict 거부 회계를 따른다").
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = strictAutoCreateRegistry(dir, metrics);
        ServletAdvice.registry = reg;
        ServletAdvice.metrics = metrics;
        // No tracer scope active in this unit test environment -> resolves via baggage fallback only.

        ServletAdvice.activate(requestWithHeaders(map("test.id", "GHOST")));

        assertNull(reg.peek("GHOST"), "un-started baggage-derived key must not be auto-created (strict contract)");
        assertNull(CoverageContext.get(), "no store should have been bound to the context");
        assertEquals(1, metrics.rejectedUnregistered.get(), "strict rejection must be accounted");
    }

    /** Given the same flag, the tracer-source branch of ServletAdvice.activate() keeps auto-create. */
    @Test
    void servletTracerKeyAutoCreates(@TempDir Path dir) {
        TestStoreRegistry reg = strictAutoCreateRegistry(dir, new Metrics());
        ServletAdvice.registry = reg;
        ServletAdvice.metrics = new Metrics();
        ServletAdvice.traceSources = Collections.<TestIdSource>singletonList(new TestIdSource() {
            public String currentKey() { return "TRACER-KEY"; }
        });

        ServletAdvice.activate(requestWithHeaders(map()));

        assertNotNull(reg.peek("TRACER-KEY"), "tracer-derived key must still auto-create a store");
    }

    /** Given the same flag, TraceScopeBridge's scope-enter path (async weave) keeps auto-create. */
    @Test
    void scopeBridgeKeyAutoCreates(@TempDir Path dir) {
        TestStoreRegistry reg = strictAutoCreateRegistry(dir, new Metrics());
        TraceScopeBridge bridge = new TraceScopeBridge(reg, new CoverageKeyResolver(DEFAULT_TRACE_SOURCES));

        Object scopeId = new Object();
        bridge.onScopeEnter("SCOPE-KEY", scopeId);
        try {
            assertNotNull(reg.peek("SCOPE-KEY"), "TraceScopeBridge scope-enter must still auto-create a store");
        } finally {
            bridge.onScopeExit(scopeId);
        }
    }
}
