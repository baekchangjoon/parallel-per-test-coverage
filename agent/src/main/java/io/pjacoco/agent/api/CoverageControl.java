package io.pjacoco.agent.api;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;

/**
 * Stable, reflectively-invoked in-JVM activation API. The testkit's {@code InProcessBridge} resolves
 * this class by its exact FQN across the classloader boundary, so the method names and signatures
 * here are a CONTRACT: do not change them without a version bump. NOT relocated by the shadow plugin
 * (see {@code agent/build.gradle.kts}); a build guard asserts its presence in the shaded jar.
 *
 * <p>All methods are best-effort and never throw into test code (mirrors {@code ServletAdvice}).
 * Activation sets the per-thread {@link CoverageContext}; the caller must run
 * activate / test body / deactivate on the SAME thread.
 */
public final class CoverageControl {

    /** Bound once at premain by {@code Bootstrap}; null until then (and in out-of-process JVMs). */
    private static volatile TestStoreRegistry registry;

    private CoverageControl() {}

    /** Wiring: called once from {@code Bootstrap.premain}. */
    public static void bindRegistry(TestStoreRegistry reg) {
        registry = reg;
    }

    /** @return true once the agent has wired the registry (i.e. the agent is installed in this JVM). */
    public static boolean isReady() {
        return registry != null;
    }

    /** Register the per-test store and set it as the active context on the calling thread. */
    public static void activate(String testId, String shardId) {
        try {
            TestStoreRegistry reg = registry;
            if (reg == null || testId == null) {
                return;
            }
            reg.start(testId, shardId, null);
            TestStore store = reg.active(testId);
            if (store != null) {
                CoverageContext.set(store);
            }
            // else: start() was swallowed by the best-effort path; leave context unset (mirrors ServletAdvice).
        } catch (Throwable ignored) {
            // never disturb the test
        }
    }

    /** Clear the thread context and flush via the registry (race-safe empty-store guard lives there). */
    public static void deactivate(String testId, String result) {
        try {
            CoverageContext.clear();
            TestStoreRegistry reg = registry;
            if (reg == null || testId == null) {
                return;
            }
            reg.stopUnlessEmpty(testId, result);
        } catch (Throwable ignored) {
            // never disturb the test
        }
    }
}
