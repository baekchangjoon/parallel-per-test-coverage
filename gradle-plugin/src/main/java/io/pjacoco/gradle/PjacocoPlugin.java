package io.pjacoco.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.process.JavaForkOptions;

/**
 * Gradle plugin {@code io.pjacoco.gradle}. Resolves the pjacoco agent and wires {@code -javaagent}
 * for per-test coverage (convention + escape hatch — see the design spec §5.5):
 *
 * <ul>
 *   <li>creates the {@code pjacoco} extension ({@link PjacocoGradleExtension});</li>
 *   <li>creates a resolvable {@code pjacocoAgent} configuration and depends on
 *       {@code io.pjacoco:pjacoco-agent:<agentVersion>} (the shaded jar);</li>
 *   <li>populates {@code pjacoco.agentJvmArg} / {@code pjacoco.controlUrlArg} for manual wiring;</li>
 *   <li>for each task named in {@code attachTo}, lazily injects the agent AND
 *       {@code -Dpjacoco.control-url} into that JVM (so the testkit activates).</li>
 * </ul>
 */
public class PjacocoPlugin implements Plugin<Project> {

    /** Default agent version when {@code agentVersion} is not set. Single-sourced from the plugin's OWN
     *  version (generated resource {@code io/pjacoco/gradle/version.properties}), so the agent it resolves
     *  always matches the plugin release — no hardcoded constant to forget on a version bump (P1-1). */
    public static final String DEFAULT_AGENT_VERSION = loadOwnVersion();

    static final String CONFIGURATION_NAME = "pjacocoAgent";

    private static String loadOwnVersion() {
        try (java.io.InputStream in =
                     PjacocoPlugin.class.getResourceAsStream("/io/pjacoco/gradle/version.properties")) {
            if (in != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isEmpty() && !v.contains("${")) {
                    return v;
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // fall through to the explicit unknown marker — never let a static-init failure (e.g. a
            // malformed resource) become an ExceptionInInitializerError that breaks the whole plugin.
        }
        // Resource missing (e.g. running the plugin classes without processResources): fail LOUD at
        // resolve time ("pjacoco-agent:0.0.0-UNKNOWN not found") rather than silently using a stale
        // version. Normal builds/jars always carry the generated resource.
        return "0.0.0-UNKNOWN";
    }

    @Override
    public void apply(Project project) {
        PjacocoGradleExtension ext = project.getExtensions().create("pjacoco", PjacocoGradleExtension.class);
        ext.getAgentVersion().convention(DEFAULT_AGENT_VERSION);
        ext.getPort().convention(6310);
        ext.getDestfile().convention(project.getLayout().getBuildDirectory().dir("pjacoco"));
        ext.getAutoDetectExtensions().convention(true);
        ext.getAggregate().convention(true);
        ext.getJunit4Auto().convention(true);

        Configuration agentConf = project.getConfigurations().create(CONFIGURATION_NAME, c -> {
            c.setVisible(false);
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setDescription("The pjacoco -javaagent jar.");
        });
        agentConf.getDependencies().addLater(ext.getAgentVersion().map(v ->
                project.getDependencies().create("io.pjacoco:pjacoco-agent:" + v)));

        // Lazy providers: resolution happens when .get() is called (task execution), not at config time.
        Provider<String> agentJarPath = project.provider(() -> agentConf.getSingleFile().getAbsolutePath());
        Provider<String> destfilePath = ext.getDestfile().map(d -> d.getAsFile().getAbsolutePath());

        Provider<String> agentJvmArg = project.provider(() ->
                PjacocoArgs.javaagent(agentJarPath.get(), ext.getPort().get(), destfilePath.get(),
                        ext.getIncludes().getOrElse(java.util.Collections.emptyList()),
                        ext.getExcludes().getOrElse(java.util.Collections.emptyList()),
                        ext.getAggregate().getOrElse(true),
                        ext.getAggregateFile().getOrElse(""),
                        ext.getJunit4Auto().getOrElse(true)));
        Provider<String> controlUrlArg = ext.getPort().map(PjacocoArgs::controlUrlArg);

        ext.getAgentJvmArg().set(agentJvmArg);
        ext.getAgentJvmArg().disallowChanges();
        ext.getControlUrlArg().set(controlUrlArg);
        ext.getControlUrlArg().disallowChanges();

        // Auto-inject into each named task once the build is configured (attachTo is known by then).
        project.afterEvaluate(p -> {
            for (String taskName : ext.getAttachTo().getOrElse(java.util.Collections.emptyList())) {
                p.getTasks().named(taskName).configure(task -> {
                    if (task instanceof JavaForkOptions) {
                        ((JavaForkOptions) task).getJvmArgumentProviders().add(
                                new AgentArgs(agentJvmArg, controlUrlArg, ext.getAutoDetectExtensions()));
                    } else {
                        p.getLogger().warn("[pjacoco] attachTo task '{}' is not a JVM-forking task; skipped.", taskName);
                    }
                });
            }
        });
    }

    /** Lazily yields the agent + control-url (+ optional JUnit 5 autodetection) JVM args at execution time. */
    static final class AgentArgs implements CommandLineArgumentProvider {
        private final Provider<String> agentJvmArg;
        private final Provider<String> controlUrlArg;
        private final Provider<Boolean> autoDetectExtensions;

        AgentArgs(Provider<String> agentJvmArg, Provider<String> controlUrlArg,
                  Provider<Boolean> autoDetectExtensions) {
            this.agentJvmArg = agentJvmArg;
            this.controlUrlArg = controlUrlArg;
            this.autoDetectExtensions = autoDetectExtensions;
        }

        @Override
        public Iterable<String> asArguments() {
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add(agentJvmArg.get());
            args.add(controlUrlArg.get());
            if (Boolean.TRUE.equals(autoDetectExtensions.getOrElse(true))) {
                args.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
            }
            return args;
        }
    }
}
