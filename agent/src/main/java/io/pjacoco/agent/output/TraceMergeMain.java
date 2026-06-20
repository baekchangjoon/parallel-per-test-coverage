package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entrypoint for the distributed-merge step (REQ-015).
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... io.pjacoco.agent.output.TraceMergeMain \
 *       --map &lt;trace-map.properties&gt; \
 *       --report &lt;report-dir&gt; \
 *       [--drain-wait-ms &lt;ms&gt;]         # default 15000
 *       (--shared &lt;shared-volume-dir&gt; | --service-dir name=path [--service-dir ...])
 * </pre>
 *
 * <p>Exit codes: 0 = success, 2 = argument/usage error, 1 = execution error.
 */
public final class TraceMergeMain {

    private TraceMergeMain() {}

    /**
     * Standard main: delegates to {@link #run}, then calls {@code System.exit} with the result code.
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Parses {@code args}, loads the trace mapping, and invokes the appropriate merge mode.
     *
     * @return 0 on success; 2 on argument/usage error; 1 on execution error.
     */
    public static int run(String[] args) {
        // ---- Argument parsing ----
        String mapArg = null;
        String reportArg = null;
        long drainWaitMs = 15_000L;
        String sharedArg = null;
        List<String> serviceDirArgs = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--map".equals(a)) {
                if (i + 1 >= args.length) return usage("--map requires a value");
                mapArg = args[++i];
            } else if ("--report".equals(a)) {
                if (i + 1 >= args.length) return usage("--report requires a value");
                reportArg = args[++i];
            } else if ("--drain-wait-ms".equals(a)) {
                if (i + 1 >= args.length) return usage("--drain-wait-ms requires a value");
                try {
                    drainWaitMs = Long.parseLong(args[++i]);
                } catch (NumberFormatException e) {
                    return usage("--drain-wait-ms must be a number: " + args[i]);
                }
            } else if ("--shared".equals(a)) {
                if (i + 1 >= args.length) return usage("--shared requires a value");
                sharedArg = args[++i];
            } else if ("--service-dir".equals(a)) {
                if (i + 1 >= args.length) return usage("--service-dir requires a value");
                serviceDirArgs.add(args[++i]);
            } else {
                return usage("Unknown argument: " + a);
            }
        }

        // Required args
        if (mapArg == null) return usage("--map is required");
        if (reportArg == null) return usage("--report is required");

        // Exactly one input mode
        boolean hasShared = sharedArg != null;
        boolean hasServiceDirs = !serviceDirArgs.isEmpty();

        if (hasShared && hasServiceDirs) {
            return usage("--shared and --service-dir are mutually exclusive; specify exactly one");
        }
        if (!hasShared && !hasServiceDirs) {
            return usage("Specify exactly one input mode: --shared <dir> or --service-dir name=path");
        }

        // ---- Load trace mapping ----
        TraceMapping mapping;
        try {
            mapping = TestIdMappingRegistry.loadFrom(Paths.get(mapArg));
        } catch (Exception e) {
            System.err.println("[pjacoco] failed to load trace map '" + mapArg + "': " + e);
            return 1;
        }

        Metrics metrics = new Metrics();

        // ---- Dispatch to the appropriate merge mode ----
        try {
            if (hasShared) {
                new DistributedCollector().collectAfterDrain(
                    Paths.get(sharedArg),
                    mapping,
                    Paths.get(reportArg),
                    drainWaitMs,
                    DistributedCollector.THREAD_SLEEP,
                    metrics);
            } else {
                // Build service map from repeated --service-dir name=path
                Map<String, java.nio.file.Path> serviceDirs =
                    new LinkedHashMap<String, java.nio.file.Path>();
                for (String spec : serviceDirArgs) {
                    int eq = spec.indexOf('=');
                    if (eq < 1) return usage("--service-dir value must be name=path; got: " + spec);
                    String name = spec.substring(0, eq);
                    String path = spec.substring(eq + 1);
                    serviceDirs.put(name, Paths.get(path));
                }
                new DistributedCoverageMerger().merge(serviceDirs, mapping, Paths.get(reportArg), metrics);
            }
        } catch (Exception e) {
            System.err.println("[pjacoco] merge failed: " + e);
            return 1;
        }

        return 0;
    }

    /** Prints a usage message to stderr and returns exit code 2. */
    private static int usage(String message) {
        System.err.println("[pjacoco] " + message);
        System.err.println("Usage: TraceMergeMain --map <props> --report <dir> [--drain-wait-ms <ms>]");
        System.err.println("       (--shared <dir> | --service-dir name=path [--service-dir ...])");
        return 2;
    }
}
