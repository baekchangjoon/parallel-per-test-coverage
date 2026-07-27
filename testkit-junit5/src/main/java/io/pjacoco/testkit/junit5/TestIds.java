package io.pjacoco.testkit.junit5;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Shared testId derivation for BOTH JUnit 5 extensions (black-box {@link PjacocoExtension} and
 * in-process {@link PjacocoInProcessExtension}): {@code FQCN#method} for plain tests,
 * {@code FQCN#method[N]} for template invocations (parameterized/repeated).
 *
 * <p>Why the invocation suffix matters (BUG-2, 2026-07-27 dogfooding): all invocations of a
 * {@code @ParameterizedTest} used to share one testId, so under JUnit 5 parallel execution the
 * agent's {@code TestStoreRegistry.start()} treated a concurrently running sibling invocation as a
 * retry and overwrote its in-flight store — the per-test {@code .exec} was silently lost. One
 * testId per invocation removes the collision on both the in-process and the HTTP path.
 */
final class TestIds {

    private static final String INVOCATION_SEGMENT = "[test-template-invocation:#";

    private TestIds() {}

    static String from(ExtensionContext context) {
        String base = context.getRequiredTestClass().getName()
                + "#" + context.getRequiredTestMethod().getName();
        String uniqueId = context.getUniqueId();
        if (uniqueId == null) return base;   // defensive: routing must never break a test
        int at = uniqueId.lastIndexOf(INVOCATION_SEGMENT);
        if (at < 0) return base;
        int end = uniqueId.indexOf(']', at);
        if (end < 0) return base;
        return base + "[" + uniqueId.substring(at + INVOCATION_SEGMENT.length(), end) + "]";
    }
}
