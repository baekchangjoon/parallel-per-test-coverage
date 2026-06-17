package io.pjacoco.testkit.junit4;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Drives {@link PjacocoInProcessRule} via its JUnit 4 TestWatcher API (apply().evaluate()), exactly
 *  like the existing PjacocoRuleTest; assertions run on the JUnit 5 platform. Without an agent the
 *  InProcessBridge is a no-op, so the rule must bracket the body without throwing. (Per-test routing
 *  is verified end-to-end by the gradle-plugin functional test AC-IP2.) */
class PjacocoInProcessRuleTest {

    static class SampleSuite { }

    @Test
    void bracketsAPassingTestWithoutThrowing() throws Throwable {
        PjacocoInProcessRule rule = new PjacocoInProcessRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "doesThing");
        final boolean[] ran = { false };
        Statement base = new Statement() {
            public void evaluate() { ran[0] = true; }
        };
        rule.apply(base, desc).evaluate();
        assertTrue(ran[0], "rule must run the wrapped test body");
    }

    @Test
    void propagatesAFailingTest() {
        PjacocoInProcessRule rule = new PjacocoInProcessRule();
        Description desc = Description.createTestDescription(SampleSuite.class, "boom");
        Statement base = new Statement() {
            public void evaluate() { throw new AssertionError("boom"); }
        };
        assertThrows(AssertionError.class, () -> rule.apply(base, desc).evaluate());
    }
}
