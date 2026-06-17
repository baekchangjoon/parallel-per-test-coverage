package io.pjacoco.agent;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.inbound.servlet.ServletInboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.AggregateWriter;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.Json;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jacoco.core.runtime.RuntimeData;

/** Java agent entry point: wires the registry, control endpoint, probe + inbound instrumentation. */
public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();
        AgentLog log = new AgentLog();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        final Path outDir = Paths.get(options.outputDir());
        final TestStoreRegistry registry = new TestStoreRegistry(
                outDir, new ExecWriter(), metrics, log,
                options.autoRegister(), options.maxStores(), new java.util.function.LongSupplier() {
                    public long getAsLong() { return System.currentTimeMillis(); }
                });

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

        // Retain the global RuntimeData so the shutdown hook can dump the whole-run aggregate.
        final RuntimeData runtimeData = ProbeInstrumentation.install(inst, options);

        // Best-effort control endpoint (the in-process path never calls it; a bind clash is harmless there).
        final ControlEndpoint[] endpointRef = new ControlEndpoint[1];
        try {
            ControlEndpoint endpoint = new ControlEndpoint(registry, options.controlHost(), options.controlPort());
            int port = endpoint.start();
            endpointRef[0] = endpoint;
            log.info("control endpoint on " + options.controlHost() + ":" + port);
        } catch (Exception e) {
            log.warn("control", "failed to start control endpoint: " + e);
        }

        // Shutdown hook registered UNCONDITIONALLY (independent of endpoint start) so the default-on
        // aggregate dump and the partial-store dump always run — even when a port-bind clash skips the
        // endpoint. Order: partial dump -> aggregate -> stop endpoint (if started).
        final AgentOptions opts = options;
        final Metrics m = metrics;
        final AgentLog l = log;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
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
                l.info(m.summary());
            }
        }));

        // Inbound activation: resolve TestStore from the registry per request, set/clear CoverageContext.
        new ServletInboundActivator(registry, metrics, log).install(inst);

        // (Task 11 inserts the JUnit 4 activator install here.)

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.autoRegister() ? "auto-register" : "strict") + ")");
    }
}
