package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;

/**
 * In-process: install the body-only advice, instrument TargetService into a fresh MemoryClassLoader
 * (load-time — no retransformation of an already-loaded class, which would fail on the added
 * {@code $jacocoData} field), run it under an active context, assert the bridge captured probes.
 */
class ProbeRoutingIT {
    static final String NAME = "com.example.app.TargetService";

    @Test
    void firedProbesLandInActiveStore() throws Exception {
        ProbeInstrumentation.installHookOnly(ByteBuddyAgent.install());

        byte[] original = readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        runtime.startup(new RuntimeData());

        MemoryClassLoader loader = new MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        TestStore store = new TestStore("T1", 1L, null);
        CoverageContext.set(store);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
        CoverageContext.clear();

        assertTrue(store.classCount() >= 1, "instrumentation did not route any probe for TargetService");
    }

    static byte[] readBytes(String fqcn) throws Exception {
        InputStream in = ProbeRoutingIT.class.getResourceAsStream("/" + fqcn.replace('.', '/') + ".class");
        assertNotNull(in, "class resource not found for " + fqcn);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs = new HashMap<String, byte[]>();

        MemoryClassLoader() { super(ProbeRoutingIT.class.getClassLoader()); }

        void add(String name, byte[] bytes) { defs.put(name, bytes); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
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
