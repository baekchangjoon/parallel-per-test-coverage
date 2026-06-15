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

    // our model (destfile reinterpreted as a directory)
    public String outputDir()   { return get("destfile", "coverage"); }
    public boolean lenient()    { return Boolean.parseBoolean(get("lenient", "false")); }
    public String controlHost() { return get("address", "127.0.0.1"); }
    public int controlPort()    { return Integer.parseInt(get("port", "6310")); }
    public int maxStores()      { return Integer.parseInt(get("maxstores", "1000")); }
    public String commitSha()   { return raw.get("commitSha"); }

    // passed through to jacoco-core instrumentation
    public String includes()    { return get("includes", "*"); }
    public String excludes()    { return get("excludes", ""); }
}
