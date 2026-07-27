package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Regression test for dogfooding BUG-2 (2026-07-27), encoding how it was discovered: with
 * JUnit 5 parallel execution enabled, a {@code @ParameterizedTest} with 3 invocations produced
 * NO per-test .exec in 2 of 3 suite runs. All invocations shared one testId
 * ({@code FQCN#method}), so concurrently running invocations hit
 * {@code TestStoreRegistry.start()}'s retry-overwrite path, which replaces the in-flight
 * store of a sibling invocation ({@code [pjacoco][WARN] retry overwrite ...} followed by
 * {@code stopUnlessEmpty for unknown testId ...}) — a race that silently loses data.
 *
 * <p>Fix under test: template invocations (parameterized/repeated tests) get an
 * invocation-unique testId {@code FQCN#method[N]} derived from the JUnit uniqueId's
 * {@code [test-template-invocation:#N]} segment, so no two concurrent invocations share a store.
 */
class InvocationUniqueTestIdTest {

    private static String deriveTestId(ExtensionContext context) throws Exception {
        Method m = PjacocoInProcessExtension.class.getDeclaredMethod("testId", ExtensionContext.class);
        m.setAccessible(true);
        return (String) m.invoke(null, context);
    }

    private static ExtensionContext contextFor(String uniqueId) throws Exception {
        ExtensionContext ctx = mock(ExtensionContext.class);
        when(ctx.getRequiredTestClass()).thenReturn((Class) SampleTarget.class);
        when(ctx.getRequiredTestMethod()).thenReturn(SampleTarget.class.getDeclaredMethod("target"));
        when(ctx.getUniqueId()).thenReturn(uniqueId);
        return ctx;
    }

    static class SampleTarget {
        void target() { }
    }

    @Test
    void plainTestKeepsClassHashMethodTestId() throws Exception {
        ExtensionContext ctx = contextFor(
                "[engine:junit-jupiter]/[class:x.SampleTarget]/[method:target()]");
        assertEquals(SampleTarget.class.getName() + "#target", deriveTestId(ctx));
    }

    @Test
    void templateInvocationsGetInvocationUniqueTestIds() throws Exception {
        ExtensionContext second = contextFor(
                "[engine:junit-jupiter]/[class:x.SampleTarget]/[test-template:target(int)]"
                        + "/[test-template-invocation:#2]");
        assertEquals(SampleTarget.class.getName() + "#target[2]", deriveTestId(second),
                "each parameterized invocation must get its own testId, or concurrent "
                        + "invocations overwrite each other's in-flight store (BUG-2)");
    }
}
