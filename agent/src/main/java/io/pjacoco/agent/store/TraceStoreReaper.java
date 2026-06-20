package io.pjacoco.agent.store;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Idle-flush reaper for traceId-keyed stores (REQ-016).
 *
 * <p>Driven by deterministic {@link #tick()} calls from the caller (Bootstrap schedules it on a
 * daemon thread). Uses an injected {@code clock} so tests can control time precisely.
 *
 * <p>State machine per store key:
 * <ol>
 *   <li><b>First observation</b> — record {@code lastWrites}, set {@code lastActivityMillis=now},
 *       {@code flushedAt=-1}. No idle decision yet.
 *   <li><b>Activity detected</b> ({@code writes != lastWrites}) — update {@code lastActivityMillis},
 *       {@code lastWrites}. If already flushed ({@code flushedAt!=-1}), re-flush immediately
 *       (late-write capture) and reset {@code flushedAt=-1}.
 *   <li><b>Idle</b> ({@code writes == lastWrites} and {@code now - lastActivityMillis >= idleFlushMillis}):
 *       <ul>
 *         <li>{@code flushedAt==-1}: flush and set {@code flushedAt=now} (enter grace).
 *         <li>{@code flushedAt!=-1} and {@code now - flushedAt >= graceMillis}: final flush-on-evict,
 *             then evict.
 *       </ul>
 * </ol>
 *
 * <p>Each store's processing is wrapped in {@code try/catch(Throwable)} so one broken store never
 * kills the reaper daemon (REQ-003, best-effort).
 */
public final class TraceStoreReaper {

    private static final int IDX_LAST_WRITES  = 0;
    private static final int IDX_FLUSHED_AT   = 1;

    private final TestStoreRegistry reg;
    private final LongSupplier clock;
    private final long idleFlushMillis;
    private final long graceMillis;

    /** Reaper-local per-key state: long[]{lastWrites, flushedAtOrMinus1}. */
    private final HashMap<String, long[]> local = new HashMap<String, long[]>();

    public TraceStoreReaper(TestStoreRegistry reg, LongSupplier clock,
                            long idleFlushMillis, long graceMillis) {
        this.reg            = reg;
        this.clock          = clock;
        this.idleFlushMillis = idleFlushMillis;
        this.graceMillis    = graceMillis;
    }

    /**
     * Performs one reaper pass. Call periodically from a scheduler; the period is independent of
     * {@code idleFlushMillis} / {@code graceMillis} (those are wall-clock thresholds).
     */
    public void tick() {
        Map<String, TestStore> snap = reg.snapshotStores();
        long now = clock.getAsLong();

        // Prune stale local state for keys that have already been removed from the registry
        // (e.g. via stop/discard/evict). Prevents unbounded memory growth.
        local.keySet().retainAll(snap.keySet());

        for (Map.Entry<String, TestStore> entry : snap.entrySet()) {
            String    key   = entry.getKey();
            TestStore store = entry.getValue();
            try {
                processStore(key, store, now);
            } catch (Throwable t) {
                // best-effort: one store must never kill the reaper daemon
            }
        }
    }

    private void processStore(String key, TestStore store, long now) {
        long w = store.writes();

        if (!local.containsKey(key)) {
            // First observation: seed local state and stamp activity so the idle timer starts NOW.
            store.lastActivityMillis(now);
            local.put(key, new long[]{w, -1L});
            return;
        }

        long[] state     = local.get(key);
        long lastWrites  = state[IDX_LAST_WRITES];
        long flushedAt   = state[IDX_FLUSHED_AT];

        if (w != lastWrites) {
            // Activity / late write detected.
            store.lastActivityMillis(now);
            state[IDX_LAST_WRITES] = w;
            if (flushedAt != -1L) {
                // Re-flush to capture writes that arrived after the idle flush.
                reg.flushStore(key);
                state[IDX_FLUSHED_AT] = -1L;
            }
            return;
        }

        // No new writes — check idle threshold.
        if (now - store.lastActivityMillis() >= idleFlushMillis) {
            if (flushedAt == -1L) {
                // First idle flush: flush but keep store alive for the grace period.
                reg.flushStore(key);
                state[IDX_FLUSHED_AT] = now;
            } else if (now - flushedAt >= graceMillis) {
                // Grace period elapsed: final flush-on-evict, then evict.
                reg.flushStore(key);
                reg.evictWithoutFlush(key);
                local.remove(key);
            }
        }
    }
}
