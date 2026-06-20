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
    /** Incremented when a per-trace store is reported without a registered traceId->testId mapping
     *  (the raw traceId is used as the testId). */
    public final AtomicLong unmappedTraceIds = new AtomicLong();

    public String summary() {
        return "[pjacoco] summary: completed=" + testsCompleted.get()
                + " partial=" + partialDumps.get()
                + " swallowed=" + swallowedExceptions.get()
                + " rejected=" + rejectedUnregistered.get()
                + " retries=" + retriesOverwritten.get()
                + " scopeHookInjectFail=" + scopeHookInjectionFailures.get()
                + " fallbackActivations=" + fallbackActivations.get()
                + " unmapped=" + unmappedTraceIds.get();
    }
}
