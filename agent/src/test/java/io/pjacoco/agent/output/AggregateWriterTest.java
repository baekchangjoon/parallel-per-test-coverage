package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.data.ISessionInfoVisitor;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregateWriterTest {

    // AggTarget is a TOP-LEVEL class (defined at the bottom of this file), so its compiled resource is
    // /io/pjacoco/agent/output/AggTarget.class — matching NAME. A nested class would compile to
    // AggregateWriterTest$AggTarget.class and readBytes(NAME) would NPE on a null stream.
    static final String NAME = "io.pjacoco.agent.output.AggTarget";

    @Test
    void writesAValidNonEmptyExecFromRuntimeData(@TempDir Path dir) throws Exception {
        // Instrument AggTarget into a child loader and run it so RuntimeData accumulates probes.
        byte[] original = readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        MemoryLoader loader = new MemoryLoader(getClass().getClassLoader());
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);
        // AggTarget is loaded by MemoryLoader (a distinct runtime package), so package-private reflective
        // access from this test is denied across class loaders — make the ctor/method accessible.
        java.lang.reflect.Constructor<?> ctor = target.getDeclaredConstructor();
        ctor.setAccessible(true);
        java.lang.reflect.Method classify = target.getMethod("classify", int.class);
        classify.setAccessible(true);
        classify.invoke(ctor.newInstance(), 5);

        Path exec = dir.resolve("aggregate.exec");
        new AggregateWriter().write(dir, "aggregate.exec", data);
        runtime.shutdown();

        assertTrue(Files.exists(exec) && Files.size(exec) > 0, "aggregate.exec must be written and non-empty");

        // Re-read it: exactly the one class, with at least one covered probe.
        Map<Long, boolean[]> read = new HashMap<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(exec))) {
            ExecutionDataReader r = new ExecutionDataReader(in);
            r.setSessionInfoVisitor(new ISessionInfoVisitor() {
                public void visitSessionInfo(SessionInfo info) { }
            });
            r.setExecutionDataVisitor(new IExecutionDataVisitor() {
                public void visitClassExecution(ExecutionData d) { read.put(d.getId(), d.getProbes()); }
            });
            r.read();
        }
        assertEquals(1, read.size(), "exactly AggTarget recorded");
        boolean any = false;
        for (boolean[] p : read.values()) for (boolean b : p) any |= b;
        assertTrue(any, "at least one probe must be covered");
    }

    @Test
    void resolvesRelativeUnderOutputDirAndAbsoluteAsIs(@TempDir Path dir) {
        assertEquals(dir.resolve("aggregate.exec"), AggregateWriter.resolve(dir, "aggregate.exec"));
        Path abs = dir.resolve("sub").resolve("x.exec").toAbsolutePath();
        assertEquals(abs, AggregateWriter.resolve(dir, abs.toString()));
    }

    private static byte[] readBytes(String fqcn) throws Exception {
        try (InputStream in = AggregateWriterTest.class.getResourceAsStream("/" + fqcn.replace('.', '/') + ".class")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static final class MemoryLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<>();
        MemoryLoader(ClassLoader parent) { super(parent); }
        void add(String name, byte[] bytes) { defs.put(name, bytes); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] b = defs.get(name);
            if (b != null) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = defineClass(name, b, 0, b.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}

/** Top-level SUT for AggregateWriterTest — compiles to io/pjacoco/agent/output/AggTarget.class. */
class AggTarget {
    public int classify(int n) {
        if (n < 0) {
            return -1;
        }
        return 1;
    }
}
