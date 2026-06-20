package io.pjacoco.agent.probe;

import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
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
                // ProbeInserter can load as a side effect while ByteBuddy is mid-weaving ClassInstrumenter
                // (same thread, circularity lock held) which would skip advising it. Disabling the lock is
                // safe here because the advised classes (jacoco internals) never trigger our own
                // instrumentation recursively.
                .with(AgentBuilder.CircularityLock.Inactive.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // Suffix match so this resolves both the relocated (io.pjacoco.shaded.jacoco.*) classes
                // in the agent jar and the un-relocated (org.jacoco.*) ones in the in-process ITs.
                .type(nameEndsWith("jacoco.core.internal.instr.ProbeInserter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                        .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(nameEndsWith("jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((b, t, cl, m, pd) -> b
                        .visit(Advice.to(VisitTotalProbeCountAdvice.class).on(named("visitTotalProbeCount"))))
                .installOn(inst);
    }

    /**
     * @return the global {@link RuntimeData} the instrumented classes write to. Previously a throwaway
     *     local; now RETAINED so {@code Bootstrap} can dump the whole-run aggregate at shutdown
     *     (jacoco's always-populated base layer). Per-test routing is unaffected.
     */
    public static RuntimeData install(Instrumentation inst, AgentOptions options) throws Exception {
        installHookOnly(inst);

        // LoggerRuntime: in-process data channel for the instrumented classes' $jacocoInit. The global
        // RuntimeData accumulates EVERY probe (jacoco's base layer); the per-test bridge records the
        // additive per-test layer on top. Matches the validated spike.
        IRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);

        Instrumenter instrumenter = new Instrumenter(runtime);
        // Force ProbeInserter to load + be advised now (clean context). If it first loads later inside
        // our own transform(), ByteBuddy skips advising it and per-test routing silently no-ops.
        warmUp(instrumenter);
        inst.addTransformer(new JacocoTransformer(instrumenter, options), false);
        return data;
    }

    private static void warmUp(Instrumenter instrumenter) {
        try {
            java.io.InputStream in = ProbeInstrumentation.class.getResourceAsStream(
                    "/io/pjacoco/agent/probe/WarmupTarget.class");
            if (in == null) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            instrumenter.instrument(bos.toByteArray(), "io/pjacoco/agent/probe/WarmupTarget");
        } catch (Throwable ignored) { /* warmup best-effort */ }
    }

    /** Instruments matching app classes with jacoco's Instrumenter. Never breaks class loading. */
    static final class JacocoTransformer implements ClassFileTransformer {
        /** Java 9+ platform classloader (loads JDK runtime modules: jdk.httpserver, java.sql, ...);
         *  null on Java 8 where those classes are bootstrap-loaded instead. Resolved reflectively so the
         *  agent still compiles/runs on Java 8. */
        private static final ClassLoader PLATFORM_LOADER = platformLoader();

        private final Instrumenter instrumenter;
        private final WildcardMatcher includes;
        private final WildcardMatcher excludes;   // null => exclude nothing
        private final boolean inclBootstrap;      // false (default) => skip bootstrap/JDK classes

        private static ClassLoader platformLoader() {
            try {
                return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
            } catch (Throwable t) {
                return null;   // Java 8: no platform classloader
            }
        }

        JacocoTransformer(Instrumenter instrumenter, AgentOptions options) {
            this.instrumenter = instrumenter;
            this.includes = new WildcardMatcher(options.includes());        // default "*"
            this.excludes = options.excludes().isEmpty() ? null : new WildcardMatcher(options.excludes());
            this.inclBootstrap = options.inclBootstrapClasses();
        }

        @Override
        public byte[] transform(ClassLoader loader, String vmName, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buffer) {
            if (vmName == null || beingRedefined != null) return null;       // first load only
            // Never instrument JDK runtime classes unless explicitly opted in. They are loaded by the
            // bootstrap loader (loader == null: java.*, sun.*) or — on Java 9+ — the platform loader
            // (jdk.*, com.sun.*, java.sql, ...). Under the default includes=* the wildcard matches them
            // too, and instrumenting them during premain either trips the native JPLIS
            // "*** java.lang.instrument ASSERTION FAILED ***" (bootstrap classes) or injects a $jacocoInit
            // that crosses JPMS module read edges and throws IllegalAccessError (e.g. com.sun.net.httpserver
            // .HttpServer, which the agent's own control endpoint uses) — both abort premain. App classes
            // never use the bootstrap/platform loader, so this never skips application code (incl. javax.*).
            // jacoco's CoverageTransformer skips bootstrap classes by default (inclbootstrapclasses=false).
            if (!inclBootstrap && (loader == null || (PLATFORM_LOADER != null && loader == PLATFORM_LOADER))) {
                return null;
            }
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
