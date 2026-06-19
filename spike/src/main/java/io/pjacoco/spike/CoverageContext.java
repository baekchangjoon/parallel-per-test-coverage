package io.pjacoco.spike;

/**
 * SPIKE-ONLY stand-in for pjacoco's production shared {@code CoverageContext} — the ThreadLocal holder
 * that the woven trace-scope advice writes the active coverage key (= traceId) into. GA-3 Q2: this
 * class must live on a classloader reachable from BOTH the Brave-woven advice (runs in the app CL) AND
 * the OTel-woven advice (runs in / near the OTel {@code AgentClassLoader}). The system CL is NOT on
 * AgentClassLoader's parent chain, so this is injected into the BOOTSTRAP classloader (via {@code
 * Instrumentation.appendToBootstrapClassLoaderSearch}) so a single shared instance is visible to both.
 */
public final class CoverageContext {

    private static final ThreadLocal<String> ACTIVE_KEY = new ThreadLocal<String>();

    private CoverageContext() {}

    /** Push the active coverage key (traceId) for the current thread. */
    public static void set(String key) {
        ACTIVE_KEY.set(key);
    }

    /** The active coverage key for the current thread, or {@code null}. */
    public static String get() {
        return ACTIVE_KEY.get();
    }

    public static void clear() {
        ACTIVE_KEY.remove();
    }

    /** Reports the classloader that loaded this class — used as Q2 placement evidence. */
    public static String loaderDescription() {
        ClassLoader cl = CoverageContext.class.getClassLoader();
        return cl == null ? "bootstrap" : cl.getClass().getName();
    }
}
