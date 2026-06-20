package io.pjacoco.agent;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.inbound.brave.BraveScopeInboundActivator;
import io.pjacoco.agent.inbound.junit4.JUnit4InboundActivator;
import io.pjacoco.agent.inbound.otel.OtelScopeInboundActivator;
import io.pjacoco.agent.inbound.servlet.ServletInboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.AggregateWriter;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.Json;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.store.TraceStoreReaper;
import io.pjacoco.agent.trace.BraveTestIdSource;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.OtelTestIdSource;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jacoco.core.runtime.RuntimeData;

/** Java agent entry point: wires the registry, control endpoint, probe + inbound instrumentation. */
public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        AgentLog log = new AgentLog();
        try {
            install(args, inst, log);
        } catch (Throwable t) {
            // REQ-U03: a catchable premain failure surfaces a self-identifying line on stderr (instead of
            // a bare "Exit Code 134"), then fails fast — swallowing risks a half-initialized agent
            // (registry bound but instrumentation/shutdown-hook missing) producing silently wrong coverage.
            log.error("init", "agent initialization failed: " + t + " — coverage disabled");
            if (t instanceof Error) throw (Error) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw (Exception) t;
        }
    }

    private static void install(String args, Instrumentation inst, AgentLog log) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        final Path outDir = Paths.get(options.outputDir());
        final java.util.function.LongSupplier clockSupplier = new java.util.function.LongSupplier() {
            public long getAsLong() { return System.currentTimeMillis(); }
        };
        final TestStoreRegistry registry = new TestStoreRegistry(
                outDir, new ExecWriter(), metrics, log,
                options.autoRegister(), options.maxStores(), clockSupplier,
                options.traceKeyAutoCreate(), options.inFlightGuardMillis());

        final TestIdMappingRegistry mapping = new TestIdMappingRegistry(options.maxTraceMappings());

        // In-JVM activation API: the testkit reaches this reflectively; the JUnit 4 runLeaf advice calls it.
        CoverageControl.bindRegistry(registry);

        // Global meta header, written once at startup (persists commitSha, no per-stop contention).
        try {
            Files.createDirectories(outDir);
            String header = new Json()
                    .put("schemaVersion", 1)
                    .put("jacocoVersion", "0.8.12")
                    .put("commitSha", commitSha)        // null -> omitted
                    .put("precision", "line")
                    .toString();
            Files.write(outDir.resolve("manifest.json"), header.getBytes("UTF-8"));
        } catch (Exception e) {
            log.warn("manifest", "could not write manifest header: " + e);
        }

        CoverageBridge.bindMetrics(metrics);
        CoverageBridge.bindAttributor(new io.pjacoco.agent.probe.DropAttributor(registry, metrics));

        // Retain the global RuntimeData so the shutdown hook can dump the whole-run aggregate.
        final RuntimeData runtimeData = ProbeInstrumentation.install(inst, options);

        // Best-effort control endpoint (the in-process path never calls it; a bind clash is harmless there).
        // REQ-U01: control=false skips it entirely (pure aggregate/in-process users avoid the bind cost and
        // port conflicts); port=0 binds an ephemeral port, surfaced via the pjacoco.control-port property.
        final ControlEndpoint[] endpointRef = new ControlEndpoint[1];
        if (options.control()) {
            try {
                ControlEndpoint endpoint = new ControlEndpoint(registry, mapping, options.controlHost(), options.controlPort());
                int port = endpoint.start();
                endpointRef[0] = endpoint;
                System.setProperty("pjacoco.control-port", String.valueOf(port));
                log.info("control endpoint on " + options.controlHost() + ":" + port);
            } catch (Exception e) {
                log.warn("control", "failed to start control endpoint: " + e);
            }
        } else {
            log.info("control endpoint disabled (control=false)");
        }

        // Scheduler holder: populated only when traceKeyAutoCreate is on (reaper daemon).
        final ScheduledExecutorService[] schedulerRef = new ScheduledExecutorService[1];

        // Shutdown hook registered UNCONDITIONALLY (independent of endpoint start) so the default-on
        // aggregate dump and the partial-store dump always run — even when a port-bind clash skips the
        // endpoint. Order: reaper stop -> partial dump -> aggregate -> endpoint stop -> trace-map dump -> summary.
        final AgentOptions opts = options;
        final Metrics m = metrics;
        final AgentLog l = log;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                if (schedulerRef[0] != null) {
                    schedulerRef[0].shutdownNow();
                }
                registry.dumpRemainingAsPartial();
                if (opts.aggregate()) {
                    try {
                        new AggregateWriter().write(outDir, opts.aggregateFile(), runtimeData);
                    } catch (Exception e) {
                        l.warn("aggregate", "failed to write whole-run aggregate: " + e);
                    }
                }
                if (endpointRef[0] != null) {
                    endpointRef[0].stop();
                }
                mapping.writeTo(outDir.resolve("trace-map.properties"));
                l.info(m.summary());
            }
        }));

        // Inbound activation: resolve TestStore from the registry per request, set/clear CoverageContext.
        new ServletInboundActivator(registry, metrics, log).install(inst);

        if (options.junit4Auto()) {
            new JUnit4InboundActivator().install(inst);
        }

        // Brave + OTel scope weave: woven advice drives TraceScopeBridge so async-handoff threads are
        // attributed to the same test as the request thread (REQ-004, REQ-005, REQ-006). Gated on
        // traceKeyAutoCreate so that the default (no-tracer) hot-path is completely unchanged when off.
        if (options.traceKeyAutoCreate()) {
            CoverageKeyResolver resolver = new CoverageKeyResolver(
                    Arrays.<io.pjacoco.agent.trace.TestIdSource>asList(
                            new OtelTestIdSource(), new BraveTestIdSource()));
            TraceScopeBridge traceBridge = new TraceScopeBridge(registry, resolver);
            new BraveScopeInboundActivator(traceBridge, metrics).install(inst);
            // OTel weave: reuses the same traceBridge; best-effort (a missing OTel javaagent is a no-op).
            new OtelScopeInboundActivator(traceBridge, metrics).install(inst);

            // Idle reaper daemon: flushes finished trace stores without JVM shutdown (REQ-016).
            final TraceStoreReaper reaper = new TraceStoreReaper(
                    registry, clockSupplier,
                    options.traceIdleFlushMillis(), options.traceLateWriteGraceMillis());
            final long reaperInterval = options.traceReaperIntervalMillis();
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "pjacoco-reaper");
                            t.setDaemon(true);
                            return t;
                        }
                    });
            scheduler.scheduleWithFixedDelay(new Runnable() {
                public void run() { reaper.tick(); }
            }, reaperInterval, reaperInterval, TimeUnit.MILLISECONDS);
            schedulerRef[0] = scheduler;
        }

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.autoRegister() ? "auto-register" : "strict") + ")");
    }
}
