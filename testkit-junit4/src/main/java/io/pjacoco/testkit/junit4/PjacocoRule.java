package io.pjacoco.testkit.junit4;

import io.pjacoco.testkit.Pjacoco;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * JUnit 4 rule that opens/closes a per-test coverage boundary around each test method and makes the
 * test id ({@code ClassName#method}) available to client adapters via {@link Pjacoco}.
 *
 * <p>Opt-in: with no {@code -Dpjacoco.control-url} system property it is a cheap no-op, so the suite
 * runs unchanged. Enable by declaring it as a rule and pointing it at the agent:
 *
 * <pre>{@code
 * @Rule public final PjacocoRule pjacoco = new PjacocoRule();
 * }</pre>
 *
 * Pair it with a client adapter (e.g. {@code PjacocoRestAssured}) so outbound requests carry the
 * {@code baggage: test.id=...} header.
 */
public final class PjacocoRule extends TestWatcher {

    @Override
    protected void starting(Description description) {
        String testId = testId(description);
        Pjacoco.setCurrentTestId(testId);
        Pjacoco.start(testId, null);
    }

    @Override
    protected void succeeded(Description description) {
        Pjacoco.stop(testId(description), "passed");
    }

    @Override
    protected void failed(Throwable e, Description description) {
        Pjacoco.stop(testId(description), "failed");
    }

    @Override
    protected void finished(Description description) {
        Pjacoco.clearCurrentTestId();
    }

    private static String testId(Description description) {
        Class<?> testClass = description.getTestClass();
        String className = testClass != null ? testClass.getSimpleName() : description.getClassName();
        return className + "#" + description.getMethodName();
    }
}
