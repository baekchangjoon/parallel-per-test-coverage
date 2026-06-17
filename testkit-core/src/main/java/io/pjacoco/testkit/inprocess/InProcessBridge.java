package io.pjacoco.testkit.inprocess;

import io.pjacoco.testkit.Pjacoco;
import java.lang.reflect.Method;

/**
 * Best-effort reflective bridge to the agent's {@code io.pjacoco.agent.api.CoverageControl}, resolved
 * across the classloader boundary (the agent is on the system classloader; test code is a child). Used
 * by {@code PjacocoInProcessExtension} (JUnit 5) and {@code PjacocoInProcessRule} (JUnit 4). Java 8,
 * zero third-party deps. Every call is a no-op when the agent is absent (out-of-process JVM, or no
 * {@code -javaagent}) and never throws into test code.
 *
 * <p>On the first activation that cannot reach a ready agent it logs ONE warning so an unexpectedly
 * empty {@code .exec} is diagnosable — UNLESS {@code pjacoco.control-url} is set, which means the user
 * is on the black-box (servlet) path and a local agent is expected to be absent.
 */
public final class InProcessBridge {

    private static final String CONTROL_CLASS = "io.pjacoco.agent.api.CoverageControl";

    private static volatile boolean resolved;
    private static Method isReadyM;
    private static Method activateM;
    private static Method deactivateM;
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private InProcessBridge() {}

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> c = load();
            if (c != null) {
                isReadyM = c.getMethod("isReady");
                activateM = c.getMethod("activate", String.class, String.class);
                deactivateM = c.getMethod("deactivate", String.class, String.class);
            }
        } catch (Throwable t) {
            isReadyM = null;
            activateM = null;
            deactivateM = null;
        } finally {
            // Set LAST: a concurrent caller that reads resolved==true (outside this monitor) is then
            // guaranteed to see the fully-assigned handles. (All readers also enter this synchronized
            // method first, so the monitor already establishes the happens-before; this is belt + braces.)
            resolved = true;
        }
    }

    private static Class<?> load() {
        ClassLoader[] loaders = {
            ClassLoader.getSystemClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            InProcessBridge.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(CONTROL_CLASS, false, cl);
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        try {
            return Class.forName(CONTROL_CLASS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** @return true only when the agent class is reachable AND its registry is bound (agent installed). */
    public static boolean available() {
        resolve();
        if (isReadyM == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isReadyM.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void activate(String testId, String shardId) {
        if (available()) {
            try {
                activateM.invoke(null, testId, shardId);
                return;
            } catch (Throwable ignored) {
                // fall through to warn
            }
        }
        warnOnce();
    }

    public static void deactivate(String testId, String result) {
        resolve();
        if (deactivateM == null) {
            return;
        }
        try {
            deactivateM.invoke(null, testId, result);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static void warnOnce() {
        if (!WARNED.compareAndSet(false, true)) {
            return;   // atomic: exactly one thread ever wins, even under parallel execution
        }
        if (Pjacoco.controlUrl() != null) {
            return;   // black-box path: a local agent is expected to be absent; the warning would mislead
        }
        System.err.println("[pjacoco] in-process agent not reachable; per-test coverage disabled");
    }
}
