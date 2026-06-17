package io.pjacoco.gradle;

import java.util.List;

/**
 * Pure helpers that compose the agent JVM arguments — kept free of Gradle types so they are trivially
 * unit-testable. The agent's {@code destfile} option is re-interpreted as an output <em>directory</em>
 * (unlike standard jacoco's single-file {@code destfile}).
 */
final class PjacocoArgs {

    private PjacocoArgs() {}

    /** Backward-compatible 5-arg form: aggregate on, default aggregate file, junit4Auto on. */
    static String javaagent(String agentJarPath, int port, String destfile,
                            List<String> includes, List<String> excludes) {
        return javaagent(agentJarPath, port, destfile, includes, excludes, true, null, true);
    }

    static String javaagent(String agentJarPath, int port, String destfile,
                            List<String> includes, List<String> excludes,
                            boolean aggregate, String aggregateFile, boolean junit4Auto) {
        StringBuilder opts = new StringBuilder();
        opts.append("destfile=").append(destfile);
        opts.append(",port=").append(port);
        if (includes != null && !includes.isEmpty()) {
            opts.append(",includes=").append(join(includes));
        }
        if (excludes != null && !excludes.isEmpty()) {
            opts.append(",excludes=").append(join(excludes));
        }
        // Aggregate defaults ON in the agent; only append overrides to keep the arg short.
        if (!aggregate) {
            opts.append(",aggregate=false");
        }
        if (aggregateFile != null && !aggregateFile.isEmpty()) {
            opts.append(",aggregateFile=").append(aggregateFile);
        }
        if (!junit4Auto) {
            opts.append(",junit4Auto=false");
        }
        return "-javaagent:" + agentJarPath + "=" + opts;
    }

    /** @return the {@code -Dpjacoco.control-url=...} system-property arg the testkit activates on. */
    static String controlUrlArg(int port) {
        return "-Dpjacoco.control-url=http://127.0.0.1:" + port;
    }

    /** jacoco-style include/exclude lists are colon-separated WildcardMatcher patterns. */
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
