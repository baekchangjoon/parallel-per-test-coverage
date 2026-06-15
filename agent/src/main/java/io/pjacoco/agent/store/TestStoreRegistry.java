package io.pjacoco.agent.store;

import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Path;
import java.util.Iterator;
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
    private volatile String commitSha;

    private final ConcurrentHashMap<String, TestStore> stores = new ConcurrentHashMap<String, TestStore>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<String, Integer>();

    public TestStoreRegistry(Path outputDir, ExecWriter writer, Metrics metrics, AgentLog log,
                             boolean lenient, int maxStores, LongSupplier clock) {
        this.outputDir = outputDir;
        this.writer = writer;
        this.metrics = metrics;
        this.log = log;
        this.lenient = lenient;
        this.maxStores = maxStores;
        this.clock = clock;
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

    public synchronized void stop(String testId, String result) {
        TestStore s = stores.remove(testId);
        if (s == null) {
            log.warn("stop-missing", "stop for unknown testId=" + testId);
            return;
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

    private void enforceCap() {
        while (stores.size() > maxStores) {
            String oldest = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, TestStore> e : stores.entrySet()) {
                if (e.getValue().startedAtMillis() < min) {
                    min = e.getValue().startedAtMillis();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) break;
            TestStore s = stores.remove(oldest);
            log.warn("cap", "store cap " + maxStores + " exceeded; evicting testId=" + oldest + " as partial");
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
