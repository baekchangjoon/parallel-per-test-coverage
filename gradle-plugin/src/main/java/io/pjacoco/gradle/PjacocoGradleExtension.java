package io.pjacoco.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * DSL for the {@code io.pjacoco.gradle} plugin:
 *
 * <pre>{@code
 * pjacoco {
 *     agentVersion.set("1.1.0")                    // default: PjacocoPlugin.DEFAULT_AGENT_VERSION
 *     port.set(6310)
 *     includes.set(listOf("com.example.*"))
 *     excludes.set(emptyList())
 *     destfile.set(layout.buildDirectory.dir("pjacoco"))
 *     attachTo.set(listOf("integrationTest"))      // task names to auto-inject the agent into
 * }
 * }</pre>
 *
 * The plugin also populates the read-only providers {@link #getAgentJvmArg()} and
 * {@link #getControlUrlArg()} for wiring the agent onto a separately-launched server.
 */
public abstract class PjacocoGradleExtension {

    /** Version of {@code io.pjacoco:pjacoco-agent} to resolve. */
    public abstract Property<String> getAgentVersion();

    /** Control-endpoint port (also where the agent binds). Default 6310. */
    public abstract Property<Integer> getPort();

    /** jacoco-style include patterns (WildcardMatcher). Empty → agent default {@code *}. */
    public abstract ListProperty<String> getIncludes();

    /** jacoco-style exclude patterns. */
    public abstract ListProperty<String> getExcludes();

    /** Output <em>directory</em> for per-test {@code .exec} files. Default {@code build/pjacoco}. */
    public abstract DirectoryProperty getDestfile();

    /** Names of {@code Test}/{@code JavaExec} tasks to auto-inject the agent (and control-url) into. */
    public abstract ListProperty<String> getAttachTo();

    /** Inject {@code junit.jupiter.extensions.autodetection.enabled=true} so the in-process JUnit 5
     *  extension auto-applies suite-wide (no {@code @ExtendWith}). Default true; set false to opt out. */
    public abstract Property<Boolean> getAutoDetectExtensions();

    /** Write the whole-run aggregate {@code .exec} at shutdown. Default true. */
    public abstract Property<Boolean> getAggregate();

    /** Aggregate file name (or absolute path). Unset → agent default {@code aggregate.exec}. */
    public abstract Property<String> getAggregateFile();

    /** Weave JUnit 4's {@code runLeaf} for zero-touch per-test activation. Default true. */
    public abstract Property<Boolean> getJunit4Auto();

    /** Read-only (plugin-populated): the composed {@code -javaagent:...} argument. */
    public abstract Property<String> getAgentJvmArg();

    /** Read-only (plugin-populated): the {@code -Dpjacoco.control-url=...} argument the testkit needs. */
    public abstract Property<String> getControlUrlArg();
}
