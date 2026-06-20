package io.pjacoco.testkit.junit5;

import io.pjacoco.testkit.Pjacoco;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that opens/closes a per-test coverage boundary around each test method and makes
 * the test id ({@code ClassName#method}) available to client adapters via {@link Pjacoco}.
 *
 * <p>Opt-in: with no {@code -Dpjacoco.control-url} system property every callback is a cheap no-op,
 * so the suite runs unchanged. Enable by registering it and pointing it at the agent:
 *
 * <pre>{@code
 * @ExtendWith(PjacocoExtension.class)
 * class MyBlackBoxTest { ... }
 * }</pre>
 *
 * (or enable JUnit's auto-detection so it applies suite-wide). Pair it with a client adapter such as
 * {@code PjacocoRestAssured} so outbound requests carry the {@code baggage: test.id=...} header.
 */
public final class PjacocoExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        String testId = testId(context);
        Pjacoco.setCurrentTestId(testId);
        Pjacoco.start(testId, null);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "failed" : "passed";
        Pjacoco.stop(testId(context), result);
        Pjacoco.clearCurrentTestId();
    }

    private static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }
}
