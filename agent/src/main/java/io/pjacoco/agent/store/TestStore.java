package io.pjacoco.agent.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-testId accumulated coverage: classId -&gt; ClassProbes (jacoco probe scheme). */
public final class TestStore {
    private final String testId;
    private final long startedAtMillis;
    private final String shardId;            // nullable
    private volatile int retryCount;
    // classId -> ClassProbes. boolean[] writes are benign races (same property JaCoCo relies on).
    private final ConcurrentHashMap<Long, ClassProbes> byClass = new ConcurrentHashMap<Long, ClassProbes>();
    private long writes;                      // plain long: clock-free hot-path activity signal
    private volatile long lastActivityMillis; // reaper-written, enforceCap-read; init = startedAtMillis

    public TestStore(String testId, long startedAtMillis, String shardId) {
        this.testId = testId;
        this.startedAtMillis = startedAtMillis;
        this.shardId = shardId;
        this.lastActivityMillis = startedAtMillis;
    }

    public void record(long classId, String className, int probeId, int probeCount) {
        ClassProbes cp = byClass.get(classId);
        if (cp == null) {
            ClassProbes created = new ClassProbes(className, new boolean[probeCount]);
            ClassProbes prev = byClass.putIfAbsent(classId, created);
            cp = (prev != null) ? prev : created;
        }
        boolean[] p = cp.probes();
        if (probeId >= 0 && probeId < p.length) {
            p[probeId] = true;
        }
        writes++;
    }

    /** Deep copy so a late write during flush cannot corrupt serialization. */
    public Map<Long, ClassProbes> snapshot() {
        Map<Long, ClassProbes> out = new LinkedHashMap<Long, ClassProbes>();
        for (Map.Entry<Long, ClassProbes> e : byClass.entrySet()) {
            boolean[] src = e.getValue().probes();
            out.put(e.getKey(), new ClassProbes(e.getValue().className(), src.clone()));
        }
        return out;
    }

    public int classCount() { return byClass.size(); }
    public String testId() { return testId; }
    public long startedAtMillis() { return startedAtMillis; }
    public String shardId() { return shardId; }
    public int retryCount() { return retryCount; }
    public void incrementRetry() { retryCount++; }
    public long writes() { return writes; }
    public long lastActivityMillis() { return lastActivityMillis; }
    public void lastActivityMillis(long millis) { this.lastActivityMillis = millis; }
}
