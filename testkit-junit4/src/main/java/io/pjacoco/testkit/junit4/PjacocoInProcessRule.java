package io.pjacoco.testkit.junit4;

import io.pjacoco.testkit.inprocess.InProcessBridge;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * JUnit 4 rule for IN-PROCESS per-test coverage: brackets each test method with
 * {@code CoverageControl.activate}/{@code deactivate} (via {@link InProcessBridge}) on the test
 * thread. The explicit alternative to the agent-side zero-touch path (the {@code ParentRunner.runLeaf}
 * weave); use this when {@code junit4Auto=false} or for explicit control. Reports pass/fail/skipped
 * (the agent-side path reports {@code unknown}). Distinct from {@code PjacocoRule} (HTTP/servlet path).
 *
 * <pre>{@code @Rule public final PjacocoInProcessRule pjacoco = new PjacocoInProcessRule();}</pre>
 */
public final class PjacocoInProcessRule extends TestWatcher {

    private volatile String result = "skipped";

    @Override
    protected void starting(Description description) {
        result = "skipped";
        InProcessBridge.activate(testId(description), null);
    }

    @Override
    protected void succeeded(Description description) {
        result = "passed";
    }

    @Override
    protected void failed(Throwable e, Description description) {
        result = "failed";
    }

    @Override
    protected void finished(Description description) {
        InProcessBridge.deactivate(testId(description), result);
    }

    private static String testId(Description description) {
        Class<?> testClass = description.getTestClass();
        String className = testClass != null ? testClass.getName() : description.getClassName();
        return className + "#" + description.getMethodName();
    }
}
