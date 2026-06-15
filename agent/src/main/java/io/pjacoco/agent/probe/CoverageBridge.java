package io.pjacoco.agent.probe;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The surface instrumented bytecode calls. Signature is fixed by the descriptor the advice emits
 * ({@code (Ljava/lang/Class;JI)V}); do not change it. Validated in {@code spike/}.
 */
public final class CoverageBridge {
    private static final Map<String, Integer> PROBE_COUNTS = new ConcurrentHashMap<String, Integer>();
    private static volatile Metrics metrics;
    private CoverageBridge() {}

    public static void bindMetrics(Metrics m) { metrics = m; }

    /** Instrument time: authoritative probe count per class (VM/slash name). */
    public static void setTotalProbeCount(String className, int count) {
        PROBE_COUNTS.put(className, count);
    }

    /** Hot path (per probe hit). MUST be cheap and MUST NEVER throw into application code. */
    public static void recordCoverage(Class<?> clazz, long classId, int probeId) {
        try {
            TestStore store = CoverageContext.get();
            if (store == null) return;                       // untagged / unregistered (resolved at activation)
            String name = clazz.getName().replace('.', '/');
            Integer count = PROBE_COUNTS.get(name);
            store.record(classId, name, probeId, count != null ? count.intValue() : probeId + 1);
        } catch (Throwable t) {
            Metrics m = metrics;
            if (m != null) m.swallowedExceptions.incrementAndGet();
            // swallow: coverage loss is acceptable, an app crash is not
        }
    }

    public static void clear() { CoverageContext.clear(); }
}
