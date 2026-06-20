package io.pjacoco.agent.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Rate-limited stderr logger so a hot-path failure cannot flood output. */
public final class AgentLog {
    private static final long MAX_PER_KEY = 20;
    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<String, AtomicLong>();

    public void warn(String key, String message) {
        long n = counts.computeIfAbsent(key, new java.util.function.Function<String, AtomicLong>() {
            public AtomicLong apply(String k) { return new AtomicLong(); }
        }).incrementAndGet();
        if (n <= MAX_PER_KEY) {
            System.err.println("[pjacoco][WARN] " + message
                    + (n == MAX_PER_KEY ? " (further '" + key + "' messages suppressed)" : ""));
        }
    }

    public void info(String message) { System.out.println("[pjacoco] " + message); }

    /** Self-identifying error line on stderr, rate-limited per key like {@link #warn} so a hot-path
     *  failure cannot flood output. Used so a premain init failure surfaces as a diagnosable
     *  {@code [pjacoco][ERROR] ...} line rather than a cryptic {@code Exit Code 134}. (REQ-U03) */
    public void error(String key, String message) {
        long n = counts.computeIfAbsent(key, new java.util.function.Function<String, AtomicLong>() {
            public AtomicLong apply(String k) { return new AtomicLong(); }
        }).incrementAndGet();
        if (n <= MAX_PER_KEY) {
            System.err.println("[pjacoco][ERROR] " + message
                    + (n == MAX_PER_KEY ? " (further '" + key + "' messages suppressed)" : ""));
        }
    }
}
