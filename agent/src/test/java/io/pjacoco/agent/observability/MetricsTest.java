package io.pjacoco.agent.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.pjacoco.agent.inbound.brave.BraveScopeInboundActivator;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void countersAndSummary() {
        Metrics m = new Metrics();
        m.testsCompleted.incrementAndGet();
        m.testsCompleted.incrementAndGet();
        m.swallowedExceptions.incrementAndGet();
        m.rejectedUnregistered.incrementAndGet();
        m.partialDumps.incrementAndGet();
        String s = m.summary();
        assertTrue(s.contains("completed=2"), s);
        assertTrue(s.contains("partial=1"), s);
        assertTrue(s.contains("swallowed=1"), s);
        assertTrue(s.contains("rejected=1"), s);
    }

    // REQ-019: scopeHookInjectionFailures counter starts at 0 and is incrementable
    @Test
    void scopeHookInjectionFailuresStartsAtZero() {
        Metrics m = new Metrics();
        assertEquals(0L, m.scopeHookInjectionFailures.get());
    }

    // REQ-019: fallbackActivations counter starts at 0 and is incrementable
    @Test
    void fallbackActivationsStartsAtZero() {
        Metrics m = new Metrics();
        assertEquals(0L, m.fallbackActivations.get());
    }

    // REQ-019: summary includes scopeHookInjectionFailures and fallbackActivations
    @Test
    void summaryIncludesNewCounters() {
        Metrics m = new Metrics();
        m.scopeHookInjectionFailures.incrementAndGet();
        m.scopeHookInjectionFailures.incrementAndGet();
        m.fallbackActivations.incrementAndGet();

        String s = m.summary();
        assertTrue(s.contains("scopeHookInjectFail=2"), "Expected scopeHookInjectFail=2 in: " + s);
        assertTrue(s.contains("fallbackActivations=1"), "Expected fallbackActivations=1 in: " + s);
        // Pre-existing fields must still be present
        assertTrue(s.contains("completed="), "Expected completed= in: " + s);
        assertTrue(s.contains("partial="), "Expected partial= in: " + s);
        assertTrue(s.contains("swallowed="), "Expected swallowed= in: " + s);
        assertTrue(s.contains("rejected="), "Expected rejected= in: " + s);
        assertTrue(s.contains("retries="), "Expected retries= in: " + s);
    }

    // REQ-019 / REQ-003: install failure increments scopeHookInjectionFailures and does NOT propagate
    @Test
    void installFailureIncrementsCounterAndDoesNotPropagate() {
        Metrics metrics = new Metrics();
        TraceScopeBridge bridge = mock(TraceScopeBridge.class);
        BraveScopeInboundActivator activator = new BraveScopeInboundActivator(bridge, metrics);

        // Build a hostile Instrumentation that throws on addTransformer (called by AgentBuilder.installOn)
        Instrumentation hostile = mock(Instrumentation.class);
        doThrow(new RuntimeException("simulated install failure"))
                .when(hostile).addTransformer(any(ClassFileTransformer.class), any(boolean.class));

        assertDoesNotThrow(() -> activator.install(hostile),
                "install() must not propagate exceptions (REQ-003)");
        assertEquals(1L, metrics.scopeHookInjectionFailures.get(),
                "scopeHookInjectionFailures must be 1 after a failed install");
    }

    // REQ-019: unmappedTraceIds counter starts at 0 and is incrementable
    @Test
    void unmappedTraceIdsStartsAtZeroAndCounts() {
        Metrics m = new Metrics();
        assertEquals(0L, m.unmappedTraceIds.get());
        m.unmappedTraceIds.incrementAndGet();
        assertTrue(m.summary().contains("unmapped=1"));
    }

    // REQ-019: evictedInFlightTraces counter starts at 0 and is incrementable
    @Test
    void evictedInFlightTracesStartsAtZeroAndCounts() {
        Metrics m = new Metrics();
        assertEquals(0L, m.evictedInFlightTraces.get());
        m.evictedInFlightTraces.incrementAndGet();
        assertTrue(m.summary().contains("evictedInFlight=1"));
    }

    // CLS-REQ-007: summary exposes the three loss counters with their values
    @Test
    @org.junit.jupiter.api.DisplayName("CLS-REQ-007: summary includes loss counters with values")
    void summary_includesLossCounters() {
        Metrics m = new Metrics();
        m.missingTestIdInbound.incrementAndGet();
        m.droppedNoContext.incrementAndGet();
        m.droppedNoContext.incrementAndGet();
        m.unattributedDrops.incrementAndGet();
        String s = m.summary();
        assertTrue(s.contains("missingTestIdInbound=1"), s);
        assertTrue(s.contains("droppedNoContext=2"), s);
        assertTrue(s.contains("unattributedDrops=1"), s);
    }
}
