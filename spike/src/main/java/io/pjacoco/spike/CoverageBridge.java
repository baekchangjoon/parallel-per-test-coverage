package io.pjacoco.spike;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime bridge that instrumented classes call. The {@link #recordCoverage} method is invoked
 * once per probe hit (hot path) via bytecode emitted by {@link InsertProbeAdvice}; probe counts are
 * captured at instrumentation time by {@link VisitTotalProbeCountAdvice}.
 *
 * <p>Mirrors Datadog's {@code CoveragePerTestBridge} contract:
 * {@code recordCoverage(Class, long classId, int probeId)} + {@code setTotalProbeCount(name, count)}.
 */
public final class CoverageBridge {
    private static final Map<String, Integer> PROBE_COUNTS = new ConcurrentHashMap<String, Integer>();
    private static final ThreadLocal<TestProbes> ACTIVE = new ThreadLocal<TestProbes>();

    private CoverageBridge() {}

    /** Instrument-time: total probe count per class (VM/slash name). */
    public static void setTotalProbeCount(String className, int count) {
        PROBE_COUNTS.put(className, count);
    }

    /** Runtime hot path. MUST be cheap and never throw into application code. */
    public static void recordCoverage(Class<?> clazz, long classId, int probeId) {
        TestProbes t = ACTIVE.get();
        if (t == null) {
            return; // untagged execution
        }
        String name = clazz.getName().replace('.', '/');
        Integer count = PROBE_COUNTS.get(name);
        t.record(classId, name, probeId, count != null ? count.intValue() : probeId + 1);
    }

    public static TestProbes start() {
        TestProbes t = new TestProbes();
        ACTIVE.set(t);
        return t;
    }

    public static void clear() { ACTIVE.remove(); }
}
