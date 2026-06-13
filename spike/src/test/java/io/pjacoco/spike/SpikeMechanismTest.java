package io.pjacoco.spike;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

class SpikeMechanismTest {

    static final String NAME = "io.pjacoco.spike.TargetService";
    static final String VM_NAME = "io/pjacoco/spike/TargetService";

    @Test
    void perTestProbesMatchVanillaJacoco() throws Exception {
        JacocoProbeHook.install();

        byte[] original = readBytes(NAME);

        // jacoco instruments via OUR embedded jacoco-core; advised ProbeInserter emits the bridge call.
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);

        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        MemoryClassLoader loader = new MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        // Execute under an active per-test context.
        TestProbes testProbes = CoverageBridge.start();
        Object instance = target.getDeclaredConstructor().newInstance();
        target.getMethod("classify", int.class).invoke(instance, 5);
        CoverageBridge.clear();

        // Vanilla view straight from jacoco's own runtime.
        ExecutionDataStore vanillaStore = new ExecutionDataStore();
        data.collect(vanillaStore, new SessionInfoStore(), false);
        runtime.shutdown();

        assertEquals(1, vanillaStore.getContents().size(), "exactly one instrumented class");
        ExecutionData vanillaEd = vanillaStore.getContents().iterator().next();
        long classId = vanillaEd.getId();
        boolean[] vanillaProbes = vanillaEd.getProbes();

        boolean[] ourProbes = testProbes.probes(classId);
        assertNotNull(ourProbes, "bridge must have recorded the class by jacoco classId");

        // KEYSTONE: per-test probe array is byte-identical to vanilla jacoco.
        assertArrayEquals(vanillaProbes, ourProbes,
                "per-test probes must equal vanilla jacoco probes");

        // sanity: classify(5) covers some-but-not-all probes (greet() never ran).
        assertTrue(anyTrue(ourProbes), "expected some covered probes");
        assertTrue(anyFalse(ourProbes), "expected some uncovered probes (greet path)");

        // BONUS: covered lines agree through jacoco's own Analyzer.
        ExecutionDataStore ourStore = new ExecutionDataStore();
        ourStore.put(new ExecutionData(classId, VM_NAME, ourProbes));
        assertEquals(coveredLines(original, vanillaStore), coveredLines(original, ourStore),
                "covered line sets must match");

        System.out.println("[spike] classId=" + classId
                + " probes=" + vanillaProbes.length
                + " coveredLines=" + coveredLines(original, ourStore));
    }

    @Test
    void parallelContextsAreIsolated() throws Exception {
        JacocoProbeHook.install();

        byte[] original = readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        MemoryClassLoader loader = new MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);
        Object instance = target.getDeclaredConstructor().newInstance();
        Method classify = target.getMethod("classify", int.class);

        // Two threads exercise different branches on the SAME instrumented class, concurrently.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<TestProbes> negative = () -> {
            TestProbes t = CoverageBridge.start();
            barrier.await();
            for (int i = 0; i < 1000; i++) {
                classify.invoke(instance, -5); // return -1 path
            }
            CoverageBridge.clear();
            return t;
        };
        Callable<TestProbes> positive = () -> {
            TestProbes t = CoverageBridge.start();
            barrier.await();
            for (int i = 0; i < 1000; i++) {
                classify.invoke(instance, 5); // return 1 path
            }
            CoverageBridge.clear();
            return t;
        };
        Future<TestProbes> fNeg = pool.submit(negative);
        Future<TestProbes> fPos = pool.submit(positive);
        TestProbes tNeg = fNeg.get();
        TestProbes tPos = fPos.get();
        pool.shutdown();
        runtime.shutdown();

        long classId = tNeg.classIds().iterator().next();
        boolean[] negProbes = tNeg.probes(classId);
        boolean[] posProbes = tPos.probes(classId);
        assertNotNull(negProbes);
        assertNotNull(posProbes);

        // ISOLATION: concurrent tests captured different coverage despite sharing the instrumented class.
        assertFalse(Arrays.equals(negProbes, posProbes),
                "two concurrent tests on different branches must yield different probe arrays");

        ExecutionDataStore negStore = new ExecutionDataStore();
        negStore.put(new ExecutionData(classId, VM_NAME, negProbes));
        ExecutionDataStore posStore = new ExecutionDataStore();
        posStore.put(new ExecutionData(classId, VM_NAME, posProbes));
        SortedSet<Integer> negLines = coveredLines(original, negStore);
        SortedSet<Integer> posLines = coveredLines(original, posStore);

        assertNotEquals(negLines, posLines, "per-test covered line sets must differ");
        System.out.println("[spike] isolation negLines=" + negLines + " posLines=" + posLines);
    }

    private static SortedSet<Integer> coveredLines(byte[] original, ExecutionDataStore store)
            throws IOException {
        CoverageBuilder cb = new CoverageBuilder();
        new Analyzer(store, cb).analyzeClass(original, NAME);
        SortedSet<Integer> lines = new TreeSet<Integer>();
        for (IClassCoverage c : cb.getClasses()) {
            for (int l = c.getFirstLine(); l <= c.getLastLine(); l++) {
                int s = c.getLine(l).getStatus();
                if (s == ICounter.FULLY_COVERED || s == ICounter.PARTLY_COVERED) {
                    lines.add(l);
                }
            }
        }
        return lines;
    }

    private static boolean anyTrue(boolean[] a) {
        for (boolean b : a) if (b) return true;
        return false;
    }

    private static boolean anyFalse(boolean[] a) {
        for (boolean b : a) if (!b) return true;
        return false;
    }

    private static byte[] readBytes(String fqcn) throws IOException {
        String res = "/" + fqcn.replace('.', '/') + ".class";
        try (InputStream in = SpikeMechanismTest.class.getResourceAsStream(res)) {
            assertNotNull(in, "class resource not found: " + res);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Loads the instrumented target locally, delegates everything else to the test classloader. */
    static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<String, byte[]>();

        MemoryClassLoader() {
            super(SpikeMechanismTest.class.getClassLoader());
        }

        void add(String name, byte[] bytes) {
            definitions.put(name, bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}
