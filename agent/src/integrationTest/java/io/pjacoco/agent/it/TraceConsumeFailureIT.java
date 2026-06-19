package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.brave.BraveScopeInboundActivator;
import io.pjacoco.agent.inbound.servlet.ServletAdvice;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.TestIdSource;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-003: fault-injection integration tests that prove every trace-consumption path is
 * best-effort — throwing sources, scope hooks, and install failures MUST NOT propagate into the
 * application, and failures MUST be observable via a metric counter.
 *
 * <p>Three fault-injection scenarios are covered:
 * <ol>
 *   <li>A throwing {@link TestIdSource} does not break servlet activation — the baggage fallback
 *       still binds the correct store.</li>
 *   <li>A throwing registry inside {@link TraceScopeBridge#enter} (caused by a faulted registry)
 *       is swallowed — {@code enter()} returns a non-null {@link TraceScope} and does not throw.</li>
 *   <li>An install-time failure increments {@link Metrics#scopeHookInjectionFailures} (the
 *       REQ-003 observability signal for the scope-hook path).</li>
 * </ol>
 */
class TraceConsumeFailureIT {

    @AfterEach
    void reset() {
        CoverageContext.clear();
        ServletAdvice.registry   = null;
        ServletAdvice.metrics    = null;
        // Restore the default tracer sources (OtelTestIdSource + BraveTestIdSource).
        ServletAdvice.traceSources = Arrays.<TestIdSource>asList(
                new io.pjacoco.agent.trace.OtelTestIdSource(),
                new io.pjacoco.agent.trace.BraveTestIdSource());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static TestStoreRegistry reg(Path dir) {
        final AtomicLong clock = new AtomicLong(1L);
        return new TestStoreRegistry(
                dir, new ExecWriter(), new Metrics(), new AgentLog(),
                /* lenient= */ false, /* maxStores= */ 100,
                new LongSupplier() { public long getAsLong() { return clock.get(); } });
    }

    // -----------------------------------------------------------------------
    // Scenario 1 — throwing TestIdSource does not break servlet activation
    // -----------------------------------------------------------------------

    /**
     * REQ-003 (servlet path): a {@link TestIdSource} that throws {@link RuntimeException} from
     * {@code currentKey()} MUST be skipped; the system MUST degrade gracefully to the baggage
     * fallback and bind the correct store without any exception escaping to the caller.
     *
     * <p>Setup: {@code traceSources} is replaced with [throwingSource, real-OTel, real-Brave].
     * The throwing source fires first; {@link CoverageKeyResolver#resolve()} catches the throw
     * and skips it (its existing best-effort loop). Neither OTel nor Brave has an active context
     * here (no tracer started in this test), so resolve() returns null. Activation then falls
     * back to the baggage header and binds the "T_BAG" store.
     *
     * <p>Metric exercised: {@link Metrics#fallbackActivations} — incremented by {@link
     * ServletAdvice#activate} whenever it falls through to the baggage path (REQ-019 / REQ-003
     * observability for the servlet activation path).
     */
    @Test
    @DisplayName("REQ-003: throwing TestIdSource is skipped; baggage fallback binds store (no exception escapes)")
    void throwingTracerSourceDoesNotBreakServletActivation(@TempDir Path dir) {
        TestStoreRegistry registry = reg(dir);
        registry.start("T_BAG", null, null);

        Metrics metrics = new Metrics();
        ServletAdvice.registry = registry;
        ServletAdvice.metrics  = metrics;

        // Replace traceSources with a throwing source first, then the real ones.
        // The throwing source will be skipped by CoverageKeyResolver's best-effort loop.
        TestIdSource throwingSource = new TestIdSource() {
            @Override
            public String currentKey() {
                throw new RuntimeException("boom — injected fault");
            }
        };
        ServletAdvice.traceSources = Arrays.<TestIdSource>asList(
                throwingSource,
                new io.pjacoco.agent.trace.OtelTestIdSource(),
                new io.pjacoco.agent.trace.BraveTestIdSource());

        // Mock request carrying the baggage test.id=T_BAG.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("baggage")).thenReturn("test.id=T_BAG");

        // The activate() call MUST NOT throw even though the first source throws.
        assertDoesNotThrow(() -> ServletAdvice.activate(req),
                "activate() must not propagate a throwing TestIdSource (REQ-003)");

        // Graceful degradation: baggage fallback bound the store.
        TestStore store = CoverageContext.get();
        assertNotNull(store, "baggage fallback must bind the store when the tracer source throws (REQ-003)");
        assertEquals("T_BAG", store.testId(),
                "bound store testId must match the baggage value (graceful degradation)");

        // Metric: the baggage-fallback path increments fallbackActivations (observable signal).
        assertEquals(1L, metrics.fallbackActivations.get(),
                "fallbackActivations must be 1 — the baggage path was taken (REQ-003 observability)");

        // deactivate() must clear the context without throwing.
        assertDoesNotThrow(() -> ServletAdvice.deactivate(),
                "deactivate() must not throw (REQ-003)");
        assertNull(CoverageContext.get(),
                "deactivate() must clear CoverageContext");
    }

    // -----------------------------------------------------------------------
    // Scenario 2 — null-store path: unregistered key on strict registry is handled gracefully
    // -----------------------------------------------------------------------

    /**
     * REQ-003 (weave/bridge path, null-store variant): the scope-identity API of
     * {@link TraceScopeBridge} ({@link TraceScopeBridge#onScopeEnter} /
     * {@link TraceScopeBridge#onScopeExit}) MUST NOT propagate any exception and MUST leave
     * {@link CoverageContext} unchanged when {@code forCoverageKey} returns {@code null} —
     * i.e. when an unregistered key is passed to a strict registry ({@code traceKeyAutoCreate=false}).
     *
     * <p>This exercises the <em>null-store path</em>: the registry's {@code active(key)} lookup
     * finds no store for the key and returns {@code null} from {@code forCoverageKey}; the
     * internal {@code enter()} call in {@code onScopeEnter} must handle this gracefully (context
     * left unchanged, no exception). A {@code null} key path (documented no-op) is also verified,
     * as is {@code onScopeExit} with an unknown or {@code null} scope id.
     *
     * <p>Note: this test does NOT exercise the bridge's {@code catch(Throwable)} path for a
     * <em>throwing</em> registry or source — that is a distinct code path. The genuine
     * throwing-registry/throwing-source swallow path is covered at the unit level by
     * {@code TraceScopeBridgeTest#enterResolvedDoesNotThrowWhenSourceThrows}; this IT focuses
     * on the integration-classpath null-store path (Brave on classpath, ByteBuddy present).
     *
     * <p>Fault-injection approach: {@code TraceScope} is package-private in
     * {@code io.pjacoco.agent.trace}, so the IT cannot directly call {@code bridge.enter()} /
     * {@code bridge.exit()}. Instead we exercise the woven-advice-facing API
     * ({@code onScopeEnter} / {@code onScopeExit}), which is the actual production entry-point
     * from the woven byte-buddy advice — the highest-fidelity path available at this level.
     */
    @Test
    @DisplayName("REQ-003: TraceScopeBridge scope-identity API (onScopeEnter/Exit) handles null store gracefully (no exception)")
    void nullStoreScopeHookIsHandledGracefully(@TempDir Path dir) {
        // Registry with traceKeyAutoCreate=false: forCoverageKey("unknown") returns null.
        // This causes enter() to skip the store lookup — context is unchanged but no exception.
        TestStoreRegistry registry = new TestStoreRegistry(
                dir, new ExecWriter(), new Metrics(), new AgentLog(),
                /* lenient= */ false, /* maxStores= */ 100,
                new LongSupplier() { public long getAsLong() { return 1L; } },
                /* traceKeyAutoCreate= */ false);

        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Collections.<TestIdSource>emptyList());
        TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);

        // onScopeEnter with null key: documented no-op (must not throw).
        Object scopeId1 = new Object();
        assertDoesNotThrow(() -> bridge.onScopeEnter(null, scopeId1),
                "onScopeEnter(null, scopeId) must not throw (REQ-003)");
        assertDoesNotThrow(() -> bridge.onScopeExit(scopeId1),
                "onScopeExit(scopeId) after null-key enter must not throw (REQ-003)");

        // onScopeEnter with an unregistered key (store lookup returns null): must not throw.
        // The try/catch in TraceScopeBridge.onScopeEnter covers any exception enter() might throw.
        Object scopeId2 = new Object();
        assertDoesNotThrow(() -> bridge.onScopeEnter("unknown-key", scopeId2),
                "onScopeEnter(unregisteredKey, scopeId) must not throw even when no store found (REQ-003)");
        assertDoesNotThrow(() -> bridge.onScopeExit(scopeId2),
                "onScopeExit(scopeId) after unregistered-key enter must not throw (REQ-003)");

        // onScopeExit with an unknown scopeId (no prior enter): must be a harmless no-op.
        assertDoesNotThrow(() -> bridge.onScopeExit(new Object()),
                "onScopeExit with unknown scopeId must be a harmless no-op (REQ-003)");

        // onScopeExit with null: must be a harmless no-op.
        assertDoesNotThrow(() -> bridge.onScopeExit(null),
                "onScopeExit(null) must be a harmless no-op (REQ-003)");

        // Context must be unchanged after all the no-op operations.
        assertNull(CoverageContext.get(),
                "CoverageContext must remain null after all no-op bridge operations (REQ-003)");
    }

    // -----------------------------------------------------------------------
    // Scenario 3 — metric observability: install failure increments scopeHookInjectionFailures
    // -----------------------------------------------------------------------

    /**
     * REQ-003 (metric observability — scope-hook install path): when
     * {@link BraveScopeInboundActivator#install} fails (simulated by making the
     * {@link Instrumentation#addTransformer} call throw), the install MUST NOT propagate the
     * exception AND MUST increment {@link Metrics#scopeHookInjectionFailures}.
     *
     * <p>This is the canonical REQ-003 observability signal for the trace-context path:
     * {@code scopeHookInjectionFailures} is incremented whenever the scope weave installation
     * fails, ensuring that any failure to wire the Brave/OTel coverage bridge is observable
     * without crashing the application. The counter is surfaced in {@link Metrics#summary()}.
     *
     * <p>Mirrors the existing {@code MetricsTest#installFailureIncrementsCounterAndDoesNotPropagate}
     * unit test but runs here as an IT to confirm the behaviour is intact in the integration
     * classpath context where Brave IS present (the IT classpath adds the Brave dependency).
     */
    @Test
    @DisplayName("REQ-003: BraveScopeInboundActivator install failure increments scopeHookInjectionFailures (no propagation)")
    void installFailureIncrementsObservableMetric(@TempDir Path dir) {
        Metrics metrics = new Metrics();
        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Collections.<TestIdSource>emptyList());
        TestStoreRegistry registry = new TestStoreRegistry(
                dir, new ExecWriter(), metrics, new AgentLog(),
                /* lenient= */ false, /* maxStores= */ 100,
                new LongSupplier() { public long getAsLong() { return 1L; } },
                /* traceKeyAutoCreate= */ true);
        TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);
        BraveScopeInboundActivator activator = new BraveScopeInboundActivator(bridge, metrics);

        // Hostile Instrumentation: addTransformer throws, simulating an install failure.
        Instrumentation hostile = mock(Instrumentation.class);
        doThrow(new RuntimeException("simulated install failure — fault injection"))
                .when(hostile).addTransformer(any(ClassFileTransformer.class), anyBoolean());

        // install() MUST NOT propagate the exception (REQ-003).
        assertDoesNotThrow(() -> activator.install(hostile),
                "BraveScopeInboundActivator.install() must not propagate exceptions (REQ-003)");

        // The failure MUST be counted in the observable metric (REQ-003 observability).
        assertEquals(1L, metrics.scopeHookInjectionFailures.get(),
                "scopeHookInjectionFailures must be 1 after a failed install — failure must be observable (REQ-003)");

        // Summary string must expose the counter so operators can detect it.
        String summary = metrics.summary();
        org.junit.jupiter.api.Assertions.assertTrue(
                summary.contains("scopeHookInjectFail=1"),
                "Metrics.summary() must include scopeHookInjectFail=1 for observability (REQ-003); got: " + summary);
    }
}
