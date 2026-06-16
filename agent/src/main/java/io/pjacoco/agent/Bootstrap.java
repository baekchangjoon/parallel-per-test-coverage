package io.pjacoco.agent;

import io.pjacoco.agent.control.ControlEndpoint;
import io.pjacoco.agent.inbound.servlet.ServletInboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.Json;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.probe.ProbeInstrumentation;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Java agent entry point: wires the registry, control endpoint, probe + inbound instrumentation. */
public final class Bootstrap {
    private Bootstrap() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        AgentOptions options = AgentOptions.parse(args);
        Metrics metrics = new Metrics();
        AgentLog log = new AgentLog();

        String commitSha = options.commitSha() != null ? options.commitSha()
                : System.getenv("PJACOCO_COMMIT");

        Path outDir = Paths.get(options.outputDir());
        TestStoreRegistry registry = new TestStoreRegistry(
                outDir, new ExecWriter(), metrics, log,
                options.autoRegister(), options.maxStores(), new java.util.function.LongSupplier() {
                    public long getAsLong() { return System.currentTimeMillis(); }
                });

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

        try {
            final ControlEndpoint endpoint = new ControlEndpoint(registry, options.controlHost(), options.controlPort());
            int port = endpoint.start();
            log.info("control endpoint on " + options.controlHost() + ":" + port);
            final TestStoreRegistry reg = registry;
            final Metrics m = metrics;
            final AgentLog l = log;
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    reg.dumpRemainingAsPartial();
                    endpoint.stop();
                    l.info(m.summary());
                }
            }));
        } catch (Exception e) {
            log.warn("control", "failed to start control endpoint: " + e);
        }

        // Probe instrumentation: jacoco-internal body-only advice + jacoco Instrumenter transformer + runtime.
        ProbeInstrumentation.install(inst, options);

        // Inbound activation: resolve TestStore from the registry per request, set/clear CoverageContext.
        new ServletInboundActivator(registry, metrics, log).install(inst);

        log.info("agent installed (output=" + options.outputDir()
                + ", mode=" + (options.autoRegister() ? "auto-register" : "strict") + ")");
    }
}
