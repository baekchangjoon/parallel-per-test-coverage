package io.pjacoco.agent.inbound.junit4;

import io.pjacoco.agent.api.CoverageControl;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code org.junit.runners.ParentRunner.runLeaf(Statement, Description, RunNotifier)} — the
 * single choke point that runs one leaf test (its {@code @Before}/{@code @Test}/{@code @After}/rules)
 * via {@code statement.evaluate()} INLINE on the calling thread. The {@code Description} is read
 * reflectively (no JUnit 4 dependency on the agent, like {@code ServletAdvice} reading the baggage
 * header). {@code activate}/{@code deactivate} are extracted as static methods for unit testing.
 *
 * <p>Result is the fixed string {@code "unknown"}: {@code runLeaf} catches all test exceptions
 * internally, so the advice has no pass/fail signal (the {@code @Rule} path does and writes
 * passed/failed). {@code @Test(timeout)}/{@code @Rule Timeout} run the body on a NEW thread, so such
 * tests are silently empty under this path (documented limitation).
 *
 * <p>Cross-path limitation: if a test under this zero-touch path makes a SYNCHRONOUS in-process servlet
 * call on the test thread, the servlet advice clears the per-thread coverage context on servlet exit, so
 * the remainder of that test records nothing. Set {@code junit4Auto=false} for such suites, or keep the
 * servlet (black-box) path in a separate task.
 */
public final class RunLeafAdvice {

    private RunLeafAdvice() {}

    public static void activate(Object description) {
        try {
            String id = testId(description);
            if (id != null) {
                CoverageControl.activate(id, null);
            }
        } catch (Throwable ignored) {
            // never disturb the test runner
        }
    }

    public static void deactivate(Object description) {
        try {
            String id = testId(description);
            if (id != null) {
                CoverageControl.deactivate(id, "unknown");
            }
        } catch (Throwable ignored) {
            // never disturb the test runner
        }
    }

    private static String testId(Object description) {
        if (description == null) {
            return null;
        }
        try {
            Method gc = description.getClass().getMethod("getClassName");
            Method gm = description.getClass().getMethod("getMethodName");
            Object cn = gc.invoke(description);
            Object mn = gm.invoke(description);
            if (cn == null) {
                return null;
            }
            return cn + "#" + mn;
        } catch (Throwable t) {
            return null;   // not a Description / no such accessors
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(1) Object description) {
        activate(description);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Advice.Argument(1) Object description) {
        deactivate(description);
    }
}
