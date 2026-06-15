package io.pjacoco.agent.context;

import io.pjacoco.agent.store.TestStore;

/**
 * The active {@link TestStore} for the current thread, resolved once per request by the inbound
 * activator. The hot path ({@code CoverageBridge.recordCoverage}) only reads this — no registry
 * lookup per probe.
 */
public final class CoverageContext {
    private static final ThreadLocal<TestStore> ACTIVE = new ThreadLocal<TestStore>();
    private CoverageContext() {}

    public static void set(TestStore store) { ACTIVE.set(store); }
    public static TestStore get() { return ACTIVE.get(); }
    public static void clear() { ACTIVE.remove(); }
}
