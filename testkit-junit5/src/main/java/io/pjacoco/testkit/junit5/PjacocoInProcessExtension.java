package io.pjacoco.testkit.junit5;

import io.pjacoco.testkit.inprocess.InProcessBridge;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension for IN-PROCESS per-test coverage: brackets each test method with
 * {@code CoverageControl.activate}/{@code deactivate} (via {@link InProcessBridge}) on the test
 * thread, so a pure in-JVM test (SUT called directly, no servlet) gets a per-test {@code .exec}.
 *
 * <p>Register explicitly with {@code @ExtendWith(PjacocoInProcessExtension.class)}, or enable
 * suite-wide auto-registration (the {@code io.pjacoco.gradle} plugin sets
 * {@code junit.jupiter.extensions.autodetection.enabled=true} and this is the single service-registered
 * extension). Distinct from {@code PjacocoExtension} (the HTTP/servlet black-box path).
 *
 * <p>testId is the FULLY-QUALIFIED class name + method (no header-length constraint here), avoiding
 * collisions between same-named test classes in different packages.
 */
public final class PjacocoInProcessExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        InProcessBridge.activate(testId(context), null);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "failed" : "passed";
        InProcessBridge.deactivate(testId(context), result);
    }

    private static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }
}
