package io.pjacoco.agent.store;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** testId -&gt; TestStore lifecycle: start/stop, strict/lenient, retry overwrite, count cap, partial dump. */
public final class TestStoreRegistry {
    private final Path outputDir;
    private final ExecWriter writer;
    private final Metrics metrics;
    private final AgentLog log;
    private final boolean lenient;
    private final int maxStores;
    private final LongSupplier clock;
    private final boolean traceKeyAutoCreate;
    private final long inFlightGuardMillis;
    private volatile String commitSha;

    private final ConcurrentHashMap<String, TestStore> stores = new ConcurrentHashMap<String, TestStore>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<String, Integer>();

    public TestStoreRegistry(Path outputDir, ExecWriter writer, Metrics metrics, AgentLog log,
                             boolean lenient, int maxStores, LongSupplier clock) {
        this(outputDir, writer, metrics, log, lenient, maxStores, clock, false);
    }

    public TestStoreRegistry(Path outputDir, ExecWriter writer, Metrics metrics, AgentLog log,
                             boolean lenient, int maxStores, LongSupplier clock,
                             boolean traceKeyAutoCreate) {
        this(outputDir, writer, metrics, log, lenient, maxStores, clock, traceKeyAutoCreate, 0L);
    }

    public TestStoreRegistry(Path outputDir, ExecWriter writer, Metrics metrics, AgentLog log,
                             boolean lenient, int maxStores, LongSupplier clock,
                             boolean traceKeyAutoCreate, long inFlightGuardMillis) {
        this.outputDir = outputDir;
        this.writer = writer;
        this.metrics = metrics;
        this.log = log;
        this.lenient = lenient;
        this.maxStores = maxStores;
        this.clock = clock;
        this.traceKeyAutoCreate = traceKeyAutoCreate;
        this.inFlightGuardMillis = inFlightGuardMillis;
    }

    public synchronized void start(String testId, String shardId, String commitSha) {
        if (commitSha != null) this.commitSha = commitSha;
        boolean isRetry = stores.containsKey(testId);
        TestStore store = new TestStore(testId, clock.getAsLong(), shardId);
        if (isRetry) {
            Integer prev = retryCounts.get(testId);
            int n = (prev == null ? 0 : prev) + 1;
            retryCounts.put(testId, n);
            for (int i = 0; i < n; i++) store.incrementRetry();
            metrics.retriesOverwritten.incrementAndGet();
            log.warn("retry", "retry overwrite testId=" + testId + " retryCount=" + n);
        }
        stores.put(testId, store);
        enforceCap();
    }

    /** Data-plane lookup (called once per request at activation). Strict: null if unregistered. */
    public TestStore active(String testId) {
        TestStore s = stores.get(testId);
        if (s != null) return s;
        if (lenient) {
            return stores.computeIfAbsent(testId, new java.util.function.Function<String, TestStore>() {
                public TestStore apply(String k) { return new TestStore(k, clock.getAsLong(), null); }
            });
        }
        metrics.rejectedUnregistered.incrementAndGet();
        log.warn("unregistered", "request for unregistered testId=" + testId + " (strict mode, not recorded)");
        return null;
    }

    /** Non-removing, side-effect-free lookup (for the empty-store guard in CoverageControl). */
    public TestStore peek(String testId) {
        return stores.get(testId);
    }

    /** Snapshot of currently-active stores (safe to iterate after return). Used by DropAttributor. */
    public Collection<TestStore> activeSnapshot() { return new ArrayList<TestStore>(stores.values()); }
    /** True if any test store is currently active (collection window open). */
    public boolean hasActive() { return !stores.isEmpty(); }

    /**
     * Tracer-mode lookup: if {@code traceKeyAutoCreate} is enabled and the key is not yet registered,
     * lazily creates and registers a store (via {@link #start}) then returns it. If the flag is off,
     * delegates to {@link #active} (existing strict/lenient behavior, may return null).
     */
    public synchronized TestStore forCoverageKey(String key) {
        if (traceKeyAutoCreate) {
            if (!stores.containsKey(key)) {
                start(key, null, null);
            }
            return peek(key);
        }
        return active(key);
    }

    /** Remove a store WITHOUT flushing (empty-store guard: an activation that recorded nothing). */
    public synchronized void discard(String testId) {
        stores.remove(testId);
    }

    public synchronized void stop(String testId, String result) {
        TestStore s = stores.remove(testId);
        if (s == null) {
            log.warn("stop-missing", "stop for unknown testId=" + testId);
            return;
        }
        flush(s, result, "complete");
        metrics.testsCompleted.incrementAndGet();
    }

    /** Race-safe stop: under the registry lock, remove then re-read droppedProbes; discard only a
     *  truly empty store (no class probes AND no attributed drops), else flush so the loss is visible. */
    public synchronized void stopUnlessEmpty(String testId, String result) {
        TestStore s = stores.remove(testId);
        if (s == null) {
            log.warn("stop-missing", "stopUnlessEmpty for unknown testId=" + testId);
            return;
        }
        if (s.classCount() == 0 && s.droppedProbes() == 0) {
            return;                                  // truly empty -> discard (no garbage file)
        }
        flush(s, result, "complete");
        metrics.testsCompleted.incrementAndGet();
    }

    /** Called from the shutdown hook for any un-stopped stores. */
    public synchronized void dumpRemainingAsPartial() {
        for (Iterator<Map.Entry<String, TestStore>> it = stores.entrySet().iterator(); it.hasNext(); ) {
            TestStore s = it.next().getValue();
            it.remove();
            flush(s, null, "partial");
            metrics.partialDumps.incrementAndGet();
        }
    }

    // ---- Reaper cooperator accessors (package-visible) --------------------------------

    /** Returns a snapshot of the current store map for the reaper to iterate safely. */
    synchronized Map<String, TestStore> snapshotStores() {
        return new LinkedHashMap<String, TestStore>(stores);
    }

    /**
     * Flush a store by key without removing it from the map (grace-period: store is kept alive
     * so late writes after flush are still captured until eviction).
     * Cap-race no-op: if the store has already been removed, silently returns.
     */
    synchronized void flushStore(String key) {
        TestStore s = stores.get(key);
        if (s == null) return;
        flush(s, null, "idle");
    }

    /** Remove a store by key WITHOUT flushing (called by reaper after final flush-on-evict). */
    synchronized void evictWithoutFlush(String key) {
        stores.remove(key);
    }

    // ---- internal -----------------------------------------------------------------

    private void enforceCap() {
        while (stores.size() > maxStores) {
            String victim = null; long oldestActivity = Long.MAX_VALUE; long victimStart = Long.MAX_VALUE;
            for (Map.Entry<String, TestStore> e : stores.entrySet()) {
                long la = e.getValue().lastActivityMillis();
                long st = e.getValue().startedAtMillis();
                // idle-first; deterministic tie-break: older lastActivity, then older start, then key
                if (la < oldestActivity
                        || (la == oldestActivity && st < victimStart)
                        || (la == oldestActivity && st == victimStart && (victim == null || e.getKey().compareTo(victim) < 0))) {
                    oldestActivity = la; victimStart = st; victim = e.getKey();
                }
            }
            if (victim == null) break;
            TestStore s = stores.remove(victim);
            if (inFlightGuardMillis > 0 && clock.getAsLong() - oldestActivity < inFlightGuardMillis) {
                metrics.evictedInFlightTraces.incrementAndGet();
                log.warn("cap", "store cap " + maxStores + " forced in-flight eviction key=" + victim);
            } else {
                log.warn("cap", "store cap " + maxStores + " exceeded; evicting idle key=" + victim + " as partial");
            }
            flush(s, null, "partial");
            metrics.partialDumps.incrementAndGet();
        }
    }

    private void flush(TestStore s, String result, String status) {
        try {
            writer.write(outputDir, s, result, commitSha, clock.getAsLong(), status);
        } catch (Exception e) {
            metrics.swallowedExceptions.incrementAndGet();
            log.warn("flush-error", "flush failed testId=" + s.testId() + ": " + e);
        }
    }
}
