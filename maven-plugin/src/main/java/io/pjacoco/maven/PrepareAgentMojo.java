package io.pjacoco.maven;

import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Resolves the pjacoco agent and prepares the {@code -javaagent} JVM argument for per-test coverage,
 * mirroring {@code jacoco:prepare-agent}. Sets a project property (default {@code pjacoco.argLine})
 * that you reference from surefire/failsafe {@code argLine} (forked test JVM) or from a server-launch
 * plugin's {@code jvmArguments} (separate process). The property also carries
 * {@code -Dpjacoco.control-url} so the pjacoco testkit activates in the test JVM.
 */
@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    static final String AGENT_ARTIFACT = "io.pjacoco:pjacoco-agent";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** The plugin's own dependencies, keyed {@code groupId:artifactId} — used to locate the agent jar. */
    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
    Map<String, Artifact> pluginArtifactMap;

    /** Control-endpoint port (also where the agent binds). */
    @Parameter(property = "pjacoco.port", defaultValue = "6310")
    int port;

    /** Output <em>directory</em> for per-test {@code .exec} files (default under {@code target/}). */
    @Parameter(property = "pjacoco.destfile", defaultValue = "${project.build.directory}/pjacoco")
    String destfile;

    /** Name of the project property to set with the composed argument. */
    @Parameter(property = "pjacoco.propertyName", defaultValue = "pjacoco.argLine")
    String propertyName;

    /** jacoco-style include patterns (WildcardMatcher). Empty → agent default {@code *}. */
    @Parameter
    List<String> includes;

    /** jacoco-style exclude patterns. */
    @Parameter
    List<String> excludes;

    /** Skip wiring the agent. */
    @Parameter(property = "pjacoco.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("[pjacoco] skipped (pjacoco.skip=true)");
            return;
        }
        Artifact agent = pluginArtifactMap.get(AGENT_ARTIFACT);
        if (agent == null || agent.getFile() == null) {
            throw new MojoExecutionException("Could not locate " + AGENT_ARTIFACT
                    + " in the plugin's dependencies. Ensure it is published (e.g. mavenLocal).");
        }

        // Quote the -javaagent arg (like jacoco:prepare-agent) so a space in the jar path or destfile
        // does not make surefire split it into multiple JVM args.
        String agentArg = "\"-javaagent:" + agent.getFile().getAbsolutePath() + "=" + options() + "\"";
        String controlUrlArg = "-Dpjacoco.control-url=http://127.0.0.1:" + port;
        String composed = agentArg + " " + controlUrlArg;

        String existing = project.getProperties().getProperty(propertyName);
        String value = (existing == null || existing.trim().isEmpty()) ? composed : composed + " " + existing;
        project.getProperties().setProperty(propertyName, value);
        getLog().info("[pjacoco] " + propertyName + " set (port=" + port + ", destfile=" + destfile + ")");
    }

    private String options() {
        StringBuilder opts = new StringBuilder();
        opts.append("destfile=").append(destfile);
        opts.append(",port=").append(port);
        if (includes != null && !includes.isEmpty()) {
            opts.append(",includes=").append(join(includes));
        }
        if (excludes != null && !excludes.isEmpty()) {
            opts.append(",excludes=").append(join(excludes));
        }
        return opts.toString();
    }

    private static String join(List<String> patterns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(patterns.get(i));
        }
        return sb.toString();
    }
}
