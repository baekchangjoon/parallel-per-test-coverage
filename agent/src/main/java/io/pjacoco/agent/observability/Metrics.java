package io.pjacoco.agent.observability;

import java.util.concurrent.atomic.AtomicLong;

/** Agent-wide counters. Surfaced as a one-line summary at JVM shutdown. */
public final class Metrics {
    public final AtomicLong testsCompleted = new AtomicLong();
    public final AtomicLong partialDumps = new AtomicLong();
    public final AtomicLong swallowedExceptions = new AtomicLong();
    public final AtomicLong rejectedUnregistered = new AtomicLong();
    public final AtomicLong retriesOverwritten = new AtomicLong();
    /** Incremented when installing the Brave scope weave (BraveScopeInboundActivator) fails. */
    public final AtomicLong scopeHookInjectionFailures = new AtomicLong();
    /** Incremented when activation falls back from a tracer source to the local/baggage source. */
    public final AtomicLong fallbackActivations = new AtomicLong();
    /** Subset of fallbackActivations resolved via the W3C {@code baggage} header (REQ-MM-004/007). */
    public final AtomicLong testIdFromW3cBaggage = new AtomicLong();
    /** Subset of fallbackActivations resolved via the Brave/Micrometer {@code test.id} field header
     *  (REQ-MM-001/004/007). */
    public final AtomicLong testIdFromFieldHeader = new AtomicLong();
    /** Subset of fallbackActivations resolved via the legacy Sleuth {@code baggage-test.id} field
     *  header (REQ-MM-002/004/007). */
    public final AtomicLong testIdFromLegacyFieldHeader = new AtomicLong();
    /** Incremented when a per-trace store is reported without a registered traceId->testId mapping
     *  (the raw traceId is used as the testId). */
    public final AtomicLong unmappedTraceIds = new AtomicLong();
    /** Incremented when the store cap forces eviction of a still-active (in-flight) trace store. */
    public final AtomicLong evictedInFlightTraces = new AtomicLong();
    /** Incremented when an inbound HTTP request reaches the instrumented server with no resolvable
     *  test.id (no tracer scope, no baggage) and no active CoverageContext, during a collection window. */
    public final AtomicLong missingTestIdInbound = new AtomicLong();
    /** Incremented when jacoco's Instrumenter throws for a class: that class silently gets NO
     *  coverage (all-or-nothing). This exact failure mode cost −22.7% total instructions in a real
     *  app (ASM version clash, 2026-06-21 feedback) and was only found by diffing against vanilla —
     *  the counter turns it into a first-class signal. */
    public final AtomicLong instrumentFailures = new AtomicLong();
    /** Incremented every time a probe fires on a thread with no active CoverageContext (coverage dropped). */
    public final AtomicLong droppedNoContext = new AtomicLong();
    /** Subset of droppedNoContext where no in-process test store was active → not attributable to any test. */
    public final AtomicLong unattributedDrops = new AtomicLong();
    /** Subset of droppedNoContext where ≥2 tests were concurrently active → ambiguous, not attributed to any
     *  one test (CLS-REQ-005, revised): a context-less drop can't be blamed on one of several parallel tests
     *  without trace context, so it is counted globally here instead of per-test over-flagging. */
    public final AtomicLong ambiguousDrops = new AtomicLong();

    public String summary() {
        // No "[pjacoco]" here — AgentLog.info prepends it (embedding it too printed the doubled
        // "[pjacoco] [pjacoco] summary:" prefix, BUG-8).
        return "summary: completed=" + testsCompleted.get()
                + " partial=" + partialDumps.get()
                + " swallowed=" + swallowedExceptions.get()
                + " rejected=" + rejectedUnregistered.get()
                + " retries=" + retriesOverwritten.get()
                + " scopeHookInjectFail=" + scopeHookInjectionFailures.get()
                + " fallbackActivations=" + fallbackActivations.get()
                + " testIdFromW3cBaggage=" + testIdFromW3cBaggage.get()
                + " testIdFromFieldHeader=" + testIdFromFieldHeader.get()
                + " testIdFromLegacyFieldHeader=" + testIdFromLegacyFieldHeader.get()
                + " unmapped=" + unmappedTraceIds.get()
                + " evictedInFlight=" + evictedInFlightTraces.get()
                + " instrumentFailures=" + instrumentFailures.get()
                + " missingTestIdInbound=" + missingTestIdInbound.get()
                + " droppedNoContext=" + droppedNoContext.get()
                + " unattributedDrops=" + unattributedDrops.get()
                + " ambiguousDrops=" + ambiguousDrops.get();
    }
}
