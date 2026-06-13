package io.pjacoco.agent.probe;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.pjacoco.agent.AgentOptions;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.jacoco.core.runtime.WildcardMatcher;

/**
 * Wires the validated probe-routing mechanism into the agent:
 * <ol>
 *   <li>body-only ByteBuddy advice on the embedded jacoco internals (additive recordCoverage);</li>
 *   <li>a jacoco {@link IRuntime} so instrumented classes' {@code $jacocoInit} resolves;</li>
 *   <li>a {@link ClassFileTransformer} that instruments app classes with jacoco's Instrumenter.</li>
 * </ol>
 * The advice/bridge are validated in {@code spike/}; (2)+(3) are standard jacoco-agent plumbing.
 */
public final class ProbeInstrumentation {
    private ProbeInstrumentation() {}

    /** Just the body-only advice on jacoco internals (used by in-process ITs and by {@link #install}). */
    public static void installHookOnly(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.jacoco.core.internal.instr.ProbeInserter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                        .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(named("org.jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitTotalProbeCountAdvice.class).on(named("visitTotalProbeCount"))))
                .installOn(inst);
    }

    public static void install(Instrumentation inst, AgentOptions options) throws Exception {
        installHookOnly(inst);

        // LoggerRuntime: in-process data channel for the instrumented classes' $jacocoInit. We don't
        // consume jacoco's global data (our bridge records per-test) — the runtime just satisfies the
        // instrumented code. Matches the validated spike.
        IRuntime runtime = new LoggerRuntime();
        runtime.startup(new RuntimeData());

        Instrumenter instrumenter = new Instrumenter(runtime);
        inst.addTransformer(new JacocoTransformer(instrumenter, options), false);
    }

    /** Instruments matching app classes with jacoco's Instrumenter. Never breaks class loading. */
    static final class JacocoTransformer implements ClassFileTransformer {
        private final Instrumenter instrumenter;
        private final WildcardMatcher includes;
        private final WildcardMatcher excludes;   // null => exclude nothing

        JacocoTransformer(Instrumenter instrumenter, AgentOptions options) {
            this.instrumenter = instrumenter;
            this.includes = new WildcardMatcher(options.includes());        // default "*"
            this.excludes = options.excludes().isEmpty() ? null : new WildcardMatcher(options.excludes());
        }

        @Override
        public byte[] transform(ClassLoader loader, String vmName, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buffer) {
            if (vmName == null || beingRedefined != null) return null;       // first load only
            String dotted = vmName.replace('/', '.');
            if (dotted.startsWith("io.pjacoco.") || dotted.startsWith("org.jacoco.")
                    || dotted.startsWith("net.bytebuddy.") || dotted.startsWith("org.objectweb.asm.")) {
                return null;                                                 // never instrument self/embedded libs
            }
            if (!includes.matches(dotted) || (excludes != null && excludes.matches(dotted))) return null;
            if (buffer.length < 8) return null;
            int major = ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
            if (major < 49) return null;                                     // Java <1.5: cannot push Type constants
            try {
                return instrumenter.instrument(buffer, vmName);
            } catch (Throwable t) {
                return null;                                                 // coverage loss >> breaking the app
            }
        }
    }
}
