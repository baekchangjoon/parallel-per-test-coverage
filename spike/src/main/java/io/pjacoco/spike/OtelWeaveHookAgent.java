package io.pjacoco.spike;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * SPIKE-ONLY java-agent for GA-3 / GA-1 (OTel backend) — the OTel analogue of {@link
 * BraveScopeHookAgent}.
 *
 * <p>Central question (GA-1 OTel verdict gap, GA-3 Q1): with the OpenTelemetry javaagent attached,
 * can a second ByteBuddy java-agent match + retransform the OTel-agent-shaded context-storage class
 * (the concrete {@code ContextStorage} the agent uses internally) and fire ENTER on
 * {@code attach(Context)} / EXIT on the returned {@code Scope.close()}, reading the trace id
 * reflectively from the shaded {@code Context} arg — on the request thread AND an executor handoff
 * thread, same traceId?
 *
 * <p>The agent shades the public OTel context API to {@code
 * io.opentelemetry.javaagent.shaded.io.opentelemetry.context.*}. The concrete storage is the enum
 * singleton {@code ThreadLocalContextStorage.INSTANCE}; its {@code attach(Context)} is the universal
 * choke point through which {@code AgentContextStorage.attach()} (and the executor instrumentation's
 * re-attach on handoff threads) make a context current. We weave:
 * <ul>
 *   <li>ENTER: body-only advice on {@code ThreadLocalContextStorage#attach(Context)} — read the trace
 *       id reflectively from the shaded {@code Context} arg, register returned {@code Scope} identity
 *       → traceId.</li>
 *   <li>EXIT: body-only advice on {@code ThreadLocalContextStorage$ScopeImpl#close()} — paired to the
 *       ENTER by scope-instance identity.</li>
 * </ul>
 *
 * <p>No compile-time dependency on OTel: args are {@code Object}, the shaded {@code Span.fromContext}
 * chain is invoked reflectively in {@link OtelWeaveBridge}.
 *
 * <p>These shaded classes are loaded by the OTel {@code AgentClassLoader} in {@code premain} BEFORE
 * pjacoco's agent runs, so they are already-loaded; we weave them with {@code
 * RedefinitionStrategy.RETRANSFORMATION} + {@code disableClassFormatChanges} (body-only advice).
 */
public final class OtelWeaveHookAgent {

    static final String STORAGE =
            "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage";
    static final String SCOPE_IMPL =
            "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage$ScopeImpl";

    private OtelWeaveHookAgent() {}

    public static void premain(String arg, Instrumentation inst) {
        install(arg, inst);
    }

    public static void agentmain(String arg, Instrumentation inst) {
        install(arg, inst);
    }

    private static void install(String arg, Instrumentation inst) {
        System.err.println("[OTEL-WEAVE] agent installing (premain/agentmain) arg=" + arg);

        injectBridgeIntoBootstrap(inst);

        // Diagnostic: are the shaded classes already loaded, and are they modifiable (retransformable)?
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.equals(STORAGE) || n.equals(SCOPE_IMPL)) {
                System.err.println("[OTEL-WEAVE] already-loaded " + n
                        + " loader=" + describeLoader(c.getClassLoader())
                        + " modifiable=" + inst.isModifiableClass(c));
            }
        }

        // The shaded context classes are bootstrap-loaded from the OTel agent jar; ByteBuddy's default
        // TypePool reads supertype descriptions through the target's classloader (bootstrap) and CANNOT
        // locate the shaded ContextStorage interface bytes there → NoSuchTypeException, transform aborts.
        // Supply a PoolStrategy whose locator compounds the OTel agent jar so the pool can resolve the
        // shaded hierarchy (ContextStorage, ThreadLocalContextStorage, ...).
        final net.bytebuddy.dynamic.ClassFileLocator otelJarLocator = otelJarLocator(arg, inst);
        AgentBuilder.PoolStrategy poolStrategy = new AgentBuilder.PoolStrategy() {
            @Override
            public net.bytebuddy.pool.TypePool typePool(
                    net.bytebuddy.dynamic.ClassFileLocator classFileLocator, ClassLoader classLoader) {
                net.bytebuddy.dynamic.ClassFileLocator effective = otelJarLocator == null
                        ? classFileLocator
                        : new net.bytebuddy.dynamic.ClassFileLocator.Compound(
                                classFileLocator, otelJarLocator);
                return net.bytebuddy.pool.TypePool.Default.of(effective);
            }

            @Override
            public net.bytebuddy.pool.TypePool typePool(
                    net.bytebuddy.dynamic.ClassFileLocator classFileLocator,
                    ClassLoader classLoader, String name) {
                return typePool(classFileLocator, classLoader);
            }
        };

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(poolStrategy)
                // Default ignore matcher skips net.bytebuddy.* etc.; the OTel shaded context package is
                // NOT in the default ignore set, but be explicit that we want it considered.
                .ignore(net.bytebuddy.matcher.ElementMatchers.<net.bytebuddy.description.type.TypeDescription>none())
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(named(STORAGE))
                .transform((builder, type, cl, module, pd) -> {
                    System.err.println("[OTEL-WEAVE] weaving storage " + type.getName()
                            + " loader=" + describeLoader(cl));
                    return builder.visit(Advice.to(OtelAttachAdvice.class)
                            .on(named("attach").and(takesArguments(1))));
                })
                .type(named(SCOPE_IMPL))
                .transform((builder, type, cl, module, pd) -> {
                    System.err.println("[OTEL-WEAVE] weaving scope " + type.getName()
                            + " loader=" + describeLoader(cl));
                    return builder.visit(Advice.to(OtelCloseAdvice.class)
                            .on(named("close").and(takesArguments(0))));
                })
                .installOn(inst);

        System.err.println("[OTEL-WEAVE] agent installed");

        // Q1 proof of the already-loaded RETRANSFORM path: in this minimal app the shaded storage
        // loads lazily AFTER both premains, so it is caught at initial load. To prove pjacoco can also
        // RETRANSFORM the shaded classes once they are already loaded (the real-app case where OTel
        // loads them during premain), spin a watcher that, after the classes load, logs isModifiableClass
        // and explicitly retransforms them (a no-op re-weave) — success proves modifiability+retransform.
        startRetransformProbe(inst);
    }

    private static void startRetransformProbe(final Instrumentation inst) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                Class<?> storage = findLoaded(inst, STORAGE);
                Class<?> scope = findLoaded(inst, SCOPE_IMPL);
                if (storage != null && scope != null) {
                    try {
                        System.err.println("[OTEL-WEAVE retransform-probe] already-loaded " + STORAGE
                                + " loader=" + describeLoader(storage.getClassLoader())
                                + " modifiable=" + inst.isModifiableClass(storage)
                                + "; " + SCOPE_IMPL + " modifiable=" + inst.isModifiableClass(scope));
                        inst.retransformClasses(storage, scope);
                        System.err.println("[OTEL-WEAVE retransform-probe] RETRANSFORM OK (already-loaded "
                                + "bootstrap shaded classes re-woven)");
                    } catch (Throwable e) {
                        System.err.println("[OTEL-WEAVE retransform-probe] RETRANSFORM FAILED: " + e);
                    }
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
            System.err.println("[OTEL-WEAVE retransform-probe] shaded classes never loaded within window");
        }, "otel-weave-retransform-probe");
        t.setDaemon(true);
        t.start();
    }

    private static Class<?> findLoaded(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Q2: place the shared bridge + {@link CoverageContext} on the BOOTSTRAP classloader so they are
     * reachable from the OTel {@code AgentClassLoader}-resident woven storage (whose parent chain
     * reaches bootstrap, NOT the system CL where this agent jar lives). We write the two classes into a
     * temp jar and {@code appendToBootstrapClassLoaderSearch}. The advice references these classes by
     * fully-qualified name; once on bootstrap they resolve from every classloader.
     */
    private static void injectBridgeIntoBootstrap(Instrumentation inst) {
        try {
            java.io.File jar = java.io.File.createTempFile("pjacoco-otel-bridge", ".jar");
            jar.deleteOnExit();
            String[] classes = {
                "io/pjacoco/spike/OtelWeaveBridge.class",
                "io/pjacoco/spike/CoverageContext.class"
            };
            try (java.util.jar.JarOutputStream jos =
                    new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jar))) {
                for (String res : classes) {
                    try (java.io.InputStream in =
                            OtelWeaveHookAgent.class.getClassLoader().getResourceAsStream(res)) {
                        if (in == null) {
                            throw new IllegalStateException("resource not found: " + res);
                        }
                        jos.putNextEntry(new java.util.zip.ZipEntry(res));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            jos.write(buf, 0, n);
                        }
                        jos.closeEntry();
                    }
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar));
            // Force-load via bootstrap (null parent) to confirm placement.
            Class<?> bridge = Class.forName("io.pjacoco.spike.OtelWeaveBridge", false, null);
            System.err.println("[OTEL-WEAVE] bridge injected into bootstrap; OtelWeaveBridge loader="
                    + describeLoader(bridge.getClassLoader()));
        } catch (Throwable t) {
            System.err.println("[OTEL-WEAVE] bootstrap injection FAILED: " + t);
        }
    }

    /**
     * Locate the OTel agent jar (where the shaded context class bytes live) and wrap it as a {@link
     * net.bytebuddy.dynamic.ClassFileLocator}. Path comes from the agent arg if given, else discovered
     * from the JVM's {@code -javaagent:} options.
     */
    private static net.bytebuddy.dynamic.ClassFileLocator otelJarLocator(
            String arg, Instrumentation inst) {
        try {
            String path = (arg != null && !arg.trim().isEmpty()) ? arg.trim() : discoverOtelJar();
            if (path == null) {
                System.err.println("[OTEL-WEAVE] could not discover OTel agent jar; TypePool resolution may fail");
                return null;
            }
            java.io.File jar = new java.io.File(path);
            if (!jar.isFile()) {
                System.err.println("[OTEL-WEAVE] OTel agent jar not found at " + path);
                return null;
            }
            System.err.println("[OTEL-WEAVE] resolving shaded types from OTel jar: " + jar.getAbsolutePath());
            return net.bytebuddy.dynamic.ClassFileLocator.ForJarFile.of(jar);
        } catch (Throwable t) {
            System.err.println("[OTEL-WEAVE] otelJarLocator FAILED: " + t);
            return null;
        }
    }

    private static String discoverOtelJar() {
        for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-javaagent:") && a.contains("opentelemetry-javaagent")) {
                String spec = a.substring("-javaagent:".length());
                int eq = spec.indexOf('=');
                return eq >= 0 ? spec.substring(0, eq) : spec;
            }
        }
        return null;
    }

    static String describeLoader(ClassLoader cl) {
        if (cl == null) {
            return "bootstrap";
        }
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }
}
