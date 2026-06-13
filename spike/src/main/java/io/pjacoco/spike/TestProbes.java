package io.pjacoco.spike;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-test accumulated coverage: classId -&gt; probe boolean[] (jacoco probe scheme). */
public final class TestProbes {
    private final ConcurrentHashMap<Long, boolean[]> probesByClass = new ConcurrentHashMap<Long, boolean[]>();
    private final Map<Long, String> nameByClass = new ConcurrentHashMap<Long, String>();

    public void record(long classId, String className, int probeId, int probeCount) {
        boolean[] arr = probesByClass.get(classId);
        if (arr == null) {
            boolean[] created = new boolean[probeCount];
            boolean[] prev = probesByClass.putIfAbsent(classId, created);
            arr = (prev != null) ? prev : created;
            nameByClass.put(classId, className);
        }
        if (probeId >= 0 && probeId < arr.length) {
            arr[probeId] = true; // benign race, identical to jacoco's own probe write
        }
    }

    public boolean[] probes(long classId) { return probesByClass.get(classId); }
    public String name(long classId) { return nameByClass.get(classId); }
    public Set<Long> classIds() { return probesByClass.keySet(); }
}
