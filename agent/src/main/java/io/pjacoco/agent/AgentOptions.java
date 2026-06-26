package io.pjacoco.agent;

import java.util.HashMap;
import java.util.Map;

/** Parses {@code -javaagent:...=k=v,k=v}. Instrumentation-scope opts mirror jacoco; output/session
 *  opts are reinterpreted for the per-test model (see spec §6). */
public final class AgentOptions {
    private final Map<String, String> raw;

    private AgentOptions(Map<String, String> raw) { this.raw = raw; }

    public static AgentOptions parse(String args) {
        Map<String, String> m = new HashMap<String, String>();
        if (args != null && !args.isEmpty()) {
            for (String pair : args.split(",")) {
                int i = pair.indexOf('=');
                if (i > 0) m.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
            }
        }
        return new AgentOptions(m);
    }

    public static AgentOptions empty() { return parse(null); }

    private String get(String k, String def) {
        String v = raw.get(k);
        return v != null ? v : def;
    }

    // our model (destfile reinterpreted as a directory). `destdir` is the clearer alias (the value is a
    // directory, not a single file as in vanilla jacoco); precedence: non-empty destdir > non-empty
    // destfile > default. An empty alias value (e.g. `destdir=`) is treated as absent. (REQ-U04)
    public String outputDir() {
        String dir = raw.get("destdir");
        if (dir != null && !dir.isEmpty()) return dir;
        String file = raw.get("destfile");
        if (file != null && !file.isEmpty()) return file;
        return "coverage";
    }
    /** Record coverage for a testId that arrives without a prior control-plane start (auto-create its
     *  store). Default false (strict: such requests are ignored). Reads {@code autoRegister};
     *  {@code lenient} is accepted as a legacy alias. */
    public boolean autoRegister() {
        return Boolean.parseBoolean(get("autoRegister", get("lenient", "false")));
    }
    public String controlHost() { return get("address", "127.0.0.1"); }
    public int controlPort()    { return Integer.parseInt(get("port", "6310")); }
    public int maxStores()      { return Integer.parseInt(get("maxstores", "1000")); }
    public String commitSha()   { return raw.get("commitSha"); }
    /** Drop-ratio threshold above which a test's sidecar is flagged {@code incompleteAttribution} (CLS-REQ-009).
     *  ratio = droppedProbes / (droppedProbes + recordedProbes). Default 0.0 = flag on any attributed drop
     *  (backward-compatible); raise it (e.g. 0.05) to suppress minor background-thread noise on the
     *  single-active-test path. Clamped to [0,1]. */
    public double incompleteAttributionThreshold() {
        double t = Double.parseDouble(get("incompleteAttributionThreshold", "0.0"));
        return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    }

    /** Whether to dump the whole-run aggregate {@code .exec} at shutdown. Default true. */
    public boolean aggregate() { return Boolean.parseBoolean(get("aggregate", "true")); }
    /** Aggregate file name (relative to the output dir) or absolute path. Default {@code aggregate.exec}. */
    public String aggregateFile() { return get("aggregateFile", "aggregate.exec"); }
    /** Whether to weave JUnit 4's {@code ParentRunner.runLeaf} for zero-touch activation. Default true. */
    public boolean junit4Auto() { return Boolean.parseBoolean(get("junit4Auto", "true")); }
    /** Auto-create a store for an unregistered coverage key in tracer mode. Default false (strict). */
    public boolean traceKeyAutoCreate() { return Boolean.parseBoolean(get("traceKeyAutoCreate", "false")); }
    /** Bounded cap for the report-time traceId->testId map (REQ-011). Default 100000. */
    public int maxTraceMappings() { return Integer.parseInt(get("maxTraceMappings", "100000")); }
    /** Reaper daemon tick interval in ms. Default 10000. */
    public long traceReaperIntervalMillis() { return Long.parseLong(get("traceReaperIntervalMillis", "10000")); }
    /** Idle threshold before a store is flushed by the reaper (ms). Default 30000. */
    public long traceIdleFlushMillis() { return Long.parseLong(get("traceIdleFlushMillis", "30000")); }
    /** Grace period after idle flush before a store is evicted (ms). Default 10000. */
    public long traceLateWriteGraceMillis() { return Long.parseLong(get("traceLateWriteGraceMillis", "10000")); }
    /** In-flight guard window for the store cap eviction path (ms). Defaults to traceIdleFlushMillis if unset. */
    public long inFlightGuardMillis() { return Long.parseLong(get("inFlightGuardMillis", String.valueOf(traceIdleFlushMillis()))); }

    // passed through to jacoco-core instrumentation
    public String includes()    { return get("includes", "*"); }
    public String excludes()    { return get("excludes", ""); }
    /** Whether to instrument JDK runtime classes — those loaded by the bootstrap classloader
     *  (java.*, sun.*) or, on Java 9+, the platform classloader (jdk.*, com.sun.*, java.sql, ...).
     *  Default false. This follows jacoco's {@code inclbootstrapclasses} (which gates the bootstrap
     *  loader) and extends it to the platform loader, because instrumenting either set during premain
     *  aborts the VM — a native JPLIS {@code ASSERTION FAILED} for bootstrap classes, or a JPMS
     *  module-read {@code IllegalAccessError} for platform classes (e.g. com.sun.net.httpserver.HttpServer,
     *  which the agent's own control endpoint uses). Leave false unless you specifically need JDK-class
     *  coverage and accept the risk. */
    public boolean inclBootstrapClasses() { return Boolean.parseBoolean(get("inclbootstrapclasses", "false")); }

    /** Whether to start the loopback control endpoint. Default true (per-test/control-plane consumers).
     *  Set {@code control=false} for pure aggregate/in-process use to avoid the port bind cost and
     *  conflicts entirely. (REQ-U01) */
    public boolean control() { return Boolean.parseBoolean(get("control", "true")); }

    /** Whether {@code POST /__coverage__/test/stop} also writes {@code <testId>.exec} to disk.
     *  Default true for backward compatibility. Query param {@code persist=} overrides per request. */
    public boolean persistOnStop() { return Boolean.parseBoolean(get("persistOnStop", "true")); }
}
