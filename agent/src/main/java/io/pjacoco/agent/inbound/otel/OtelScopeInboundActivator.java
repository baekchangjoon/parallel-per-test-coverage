package io.pjacoco.agent.inbound.otel;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.pjacoco.agent.inbound.InboundActivator;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Installs the OTel shaded-context-storage scope weave: body-only ByteBuddy advice on
 * {@code ThreadLocalContextStorage#attach(Context)} and
 * {@code ThreadLocalContextStorage$ScopeImpl#close()}.
 *
 * <h2>Classloader architecture</h2>
 * <p>The OTel javaagent loads its shaded context classes on the BOOTSTRAP classloader. Advice
 * inlined into those classes can only reach code that is also bootstrap-visible. pjacoco's main
 * jar lives on the SYSTEM classloader, which bootstrap cannot see. The solution is a thin
 * bootstrap gateway ({@code TraceWeaveGateway}) that is injected to bootstrap via
 * {@code Instrumentation.appendToBootstrapClassLoaderSearch} before any weaving takes place.
 *
 * <p>The static {@code TraceWeaveGateway.handler} field is typed as {@code Object} (not the
 * {@code TraceWeaveHandler} interface) to avoid a classloader mismatch: the bootstrap copy of
 * {@code TraceWeaveGateway} and the system-CL copy loaded from the pjacoco jar are different
 * {@code Class} objects.  After bootstrap injection we wire the bridge via a reflective call to
 * the bootstrap copy's {@code setHandler(Object)} — the bootstrap copy then resolves the
 * {@code TraceWeaveHandler} interface from the SYSTEM classloader (its child) and caches the
 * dispatch {@code Method} objects.
 *
 * <h2>Best-effort / never-crash contract</h2>
 * <p>If no OTel javaagent is present, the install silently returns — that is NOT a failure.
 * Any exception during bootstrap injection or {@code installOn} is caught and recorded in
 * {@link Metrics#scopeHookInjectionFailures} (REQ-003).
 */
public final class OtelScopeInboundActivator implements InboundActivator {

    static final String STORAGE =
            "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage";
    static final String SCOPE_IMPL =
            "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ThreadLocalContextStorage$ScopeImpl";

    private final TraceScopeBridge bridge;
    private final Metrics metrics;

    public OtelScopeInboundActivator(TraceScopeBridge bridge, Metrics metrics) {
        this.bridge = bridge;
        this.metrics = metrics;
    }

    @Override
    public void install(Instrumentation inst) {
        // ----------------------------------------------------------------
        // Step 1: inject TraceWeaveGateway to bootstrap
        // ----------------------------------------------------------------
        if (!injectBootstrapClasses(inst)) {
            // Bootstrap injection failed; OTel weave is not safe to install.
            metrics.scopeHookInjectionFailures.incrementAndGet();
            return;
        }

        // ----------------------------------------------------------------
        // Step 2: wire the bridge into the BOOTSTRAP copy of TraceWeaveGateway
        //         using reflection — the bootstrap and system-CL copies are
        //         different Class objects, so direct field assignment sets the
        //         WRONG (system-CL) copy's field.
        // ----------------------------------------------------------------
        if (!wireBootstrapHandler(bridge)) {
            metrics.scopeHookInjectionFailures.incrementAndGet();
            return;
        }

        // ----------------------------------------------------------------
        // Step 3: discover the OTel agent jar (needed for the TypePool)
        // ----------------------------------------------------------------
        String otelJarPath = discoverOtelJar();
        if (otelJarPath == null) {
            // No OTel javaagent on the command-line — this is a normal no-op, not a failure.
            return;
        }

        // ----------------------------------------------------------------
        // Step 4: install ByteBuddy weave using the proven spike pattern
        // ----------------------------------------------------------------
        try {
            final ClassFileLocator otelLocator = buildOtelLocator(otelJarPath);

            AgentBuilder.PoolStrategy poolStrategy = new AgentBuilder.PoolStrategy() {
                @Override
                public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
                    ClassFileLocator effective = otelLocator == null
                            ? classFileLocator
                            : new ClassFileLocator.Compound(classFileLocator, otelLocator);
                    return TypePool.Default.of(effective);
                }

                @Override
                public TypePool typePool(ClassFileLocator classFileLocator,
                        ClassLoader classLoader, String name) {
                    return typePool(classFileLocator, classLoader);
                }
            };

            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(poolStrategy)
                    .ignore(net.bytebuddy.matcher.ElementMatchers
                            .<net.bytebuddy.description.type.TypeDescription>none())
                    .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                    .type(named(STORAGE))
                    .transform((builder, type, cl, module, pd) ->
                            builder.visit(Advice.to(OtelAttachAdvice.class)
                                    .on(named("attach").and(takesArguments(1)))))
                    .type(named(SCOPE_IMPL))
                    .transform((builder, type, cl, module, pd) ->
                            builder.visit(Advice.to(OtelCloseAdvice.class)
                                    .on(named("close").and(takesArguments(0)))))
                    .installOn(inst);
        } catch (Throwable t) {
            metrics.scopeHookInjectionFailures.incrementAndGet();
        }
    }

    // -----------------------------------------------------------------------
    // Bootstrap handler wiring (reflective)
    // -----------------------------------------------------------------------

    /**
     * After {@code appendToBootstrapClassLoaderSearch} completes, the bootstrap classloader has
     * its own copy of {@code TraceWeaveGateway}. We must call {@code setHandler} on THAT copy
     * (not on the system-CL copy already loaded from the pjacoco shaded jar) so the woven
     * advice — which runs in the bootstrap context — reads the correct field.
     */
    private static boolean wireBootstrapHandler(Object bridgeObj) {
        try {
            // Load TraceWeaveGateway from the BOOTSTRAP classloader (null parent).
            Class<?> bootGateway = Class.forName(
                    "io.pjacoco.agent.trace.boot.TraceWeaveGateway", true, null);
            Method setHandler = bootGateway.getMethod("setHandler", Object.class);
            setHandler.invoke(null, bridgeObj);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Bootstrap injection (mirrors spike OtelWeaveHookAgent#injectBridgeIntoBootstrap)
    // -----------------------------------------------------------------------

    /**
     * Builds a temporary jar containing the {@code TraceWeaveGateway} class bytes and appends it
     * to the bootstrap classloader search path.  Only {@code TraceWeaveGateway} needs to be
     * bootstrap-resident — it stores the handler as {@code Object} and dispatches reflectively,
     * so no interface types need to be co-located on bootstrap.
     *
     * @return {@code true} on success, {@code false} if the injection could not be completed
     */
    private static boolean injectBootstrapClasses(Instrumentation inst) {
        try {
            File jar = File.createTempFile("pjacoco-otel-gateway", ".jar");
            jar.deleteOnExit();

            // Only inject TraceWeaveGateway — it dispatches reflectively, needing no extra types.
            String[] resources = {
                "io/pjacoco/agent/trace/boot/TraceWeaveGateway.class"
            };

            ClassLoader srcCl = OtelScopeInboundActivator.class.getClassLoader();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                for (String res : resources) {
                    InputStream in = srcCl.getResourceAsStream(res);
                    if (in == null) {
                        // Class resource not found — abort injection.
                        return false;
                    }
                    try {
                        jos.putNextEntry(new ZipEntry(res));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            jos.write(buf, 0, n);
                        }
                        jos.closeEntry();
                    } finally {
                        in.close();
                    }
                }
            }

            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // OTel jar discovery (mirrors spike OtelWeaveHookAgent#discoverOtelJar)
    // -----------------------------------------------------------------------

    /**
     * Scans the JVM's command-line arguments for the OTel javaagent jar and returns its path, or
     * {@code null} if none is present. Identification is by {@link #isOtelAgentJar} — a filename
     * fast-path plus a structural check (the jar contains the shaded {@link #STORAGE} class) — so it
     * works regardless of how the agent jar is named at deploy time (e.g. {@code /opt/otel/otel.jar}).
     */
    static String discoverOtelJar() {
        return discoverOtelJar(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    static String discoverOtelJar(java.util.List<String> inputArgs) {
        for (String arg : inputArgs) {
            if (!arg.startsWith("-javaagent:")) {
                continue;
            }
            String spec = arg.substring("-javaagent:".length());
            int eq = spec.indexOf('=');
            String path = eq >= 0 ? spec.substring(0, eq) : spec;
            if (isOtelAgentJar(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Identifies the OTel javaagent jar WITHOUT relying on a filename convention. Real deployments
     * mount the agent under arbitrary names (e.g. {@code -javaagent:/opt/otel/otel.jar}), so the
     * authoritative test is structural: a jar that contains the shaded {@link #STORAGE} class we
     * weave IS the OTel javaagent. pjacoco's own agent jar (also a {@code -javaagent}) does not
     * contain that shaded class, so it is correctly ignored.
     *
     * <p>A fast-path filename check avoids opening the jar in the conventional case.
     */
    private static boolean isOtelAgentJar(String path) {
        if (path.contains("opentelemetry-javaagent")) {
            return true;
        }
        try (JarFile jf = new JarFile(path)) {
            return jf.getEntry(STORAGE.replace('.', '/') + ".class") != null;
        } catch (Throwable t) {
            // Unreadable / missing jar — not identifiable as the OTel agent (best-effort, REQ-003).
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // OTel jar locator for the TypePool (mirrors spike otelJarLocator)
    // -----------------------------------------------------------------------

    private static ClassFileLocator buildOtelLocator(String jarPath) {
        try {
            File jar = new File(jarPath);
            if (!jar.isFile()) {
                return null;
            }
            return ClassFileLocator.ForJarFile.of(jar);
        } catch (Throwable t) {
            return null;
        }
    }
}
