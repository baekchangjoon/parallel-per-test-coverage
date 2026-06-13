package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStore;
import java.util.SortedSet;
import java.util.TreeSet;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;

/** Keystone: in one instrument+run, our per-test probe array equals jacoco's own global array
 *  byte-for-byte (validated as the spike's {@code perTestProbesMatchVanillaJacoco}). */
class GoldenEquivalenceIT {
    static final String NAME = "io.pjacoco.agent.it.TargetService";
    static final String VM_NAME = "io/pjacoco/agent/it/TargetService";

    @Test
    void perTestProbesMatchVanillaJacoco() throws Exception {
        ProbeInstrumentation.installHookOnly(ByteBuddyAgent.install());

        byte[] original = ProbeRoutingIT.readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        ProbeRoutingIT.MemoryClassLoader loader = new ProbeRoutingIT.MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        TestStore store = new TestStore("T1", 1L, null);
        CoverageContext.set(store);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
        CoverageContext.clear();

        ExecutionDataStore vanilla = new ExecutionDataStore();
        data.collect(vanilla, new SessionInfoStore(), false);
        runtime.shutdown();

        assertEquals(1, vanilla.getContents().size());
        ExecutionData vanillaEd = vanilla.getContents().iterator().next();
        long classId = vanillaEd.getId();
        boolean[] vanillaProbes = vanillaEd.getProbes();
        boolean[] ourProbes = store.snapshot().get(classId).probes();

        // KEYSTONE: byte-identical to vanilla jacoco.
        assertArrayEquals(vanillaProbes, ourProbes);

        // bonus: covered lines agree through jacoco's Analyzer.
        ExecutionDataStore ourStore = new ExecutionDataStore();
        ourStore.put(new ExecutionData(classId, VM_NAME, ourProbes));
        assertEquals(coveredLines(original, vanilla), coveredLines(original, ourStore));
    }

    private static SortedSet<Integer> coveredLines(byte[] original, ExecutionDataStore store) throws Exception {
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(store, cb).analyzeClass(original, NAME);
        SortedSet<Integer> lines = new TreeSet<Integer>();
        for (IClassCoverage c : cb.getClasses()) {
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                int s = c.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) lines.add(l);
            }
        }
        return lines;
    }
}
