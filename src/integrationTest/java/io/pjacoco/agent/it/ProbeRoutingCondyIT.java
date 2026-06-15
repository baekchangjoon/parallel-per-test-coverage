package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStore;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;

/**
 * Regression proof that per-test routing works for <strong>modern (Java 11+) bytecode</strong>:
 * instruments {@code CondyTarget} (class-file major 55) so jacoco selects
 * {@code CondyProbeArrayStrategy}, runs it under an active per-test context, and asserts the bridge
 * captured probes — exactly as {@link ProbeRoutingIT} does for the Java 8 class.
 *
 * <p>This refutes the once-assumed limitation that the hook only handled
 * {@code ClassFieldProbeArrayStrategy}: {@code CondyProbeArrayStrategy} declares the same
 * {@code className}/{@code classId} fields the hook reflects, so routing works across bytecode
 * versions. Keep this green to guard that property.
 */
class ProbeRoutingCondyIT {
    static final String NAME = "com.example.app.CondyTarget";

    @Test
    void condyClassIsActuallyJava11Bytecode() throws Exception {
        byte[] bytes = ProbeRoutingIT.readBytes(NAME);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertTrue(major >= 55, "fixture must be Java 11+ bytecode (major>=55) to exercise Condy; was " + major);
    }

    @Test
    void firedProbesLandInActiveStore() throws Exception {
        ProbeInstrumentation.installHookOnly(ByteBuddyAgent.install());

        byte[] original = ProbeRoutingIT.readBytes(NAME);
        IRuntime runtime = new LoggerRuntime();
        Instrumenter instrumenter = new Instrumenter(runtime);
        byte[] instrumented = instrumenter.instrument(original, NAME);
        runtime.startup(new RuntimeData());

        ProbeRoutingIT.MemoryClassLoader loader = new ProbeRoutingIT.MemoryClassLoader();
        loader.add(NAME, instrumented);
        Class<?> target = loader.loadClass(NAME);

        TestStore store = new TestStore("T1", 1L, null);
        CoverageContext.set(store);
        target.getMethod("classify", int.class).invoke(target.getDeclaredConstructor().newInstance(), 5);
        CoverageContext.clear();

        assertTrue(store.classCount() >= 1,
                "Condy (Java 11+ bytecode) probes were NOT routed to the active per-test store");
    }
}
