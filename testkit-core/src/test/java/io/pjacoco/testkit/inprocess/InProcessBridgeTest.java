package io.pjacoco.testkit.inprocess;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class InProcessBridgeTest {

    @Test
    void notAvailableWhenAgentClassAbsent() {
        // CoverageControl is not on the testkit's test classpath.
        assertFalse(InProcessBridge.available(), "no agent on classpath -> not available");
    }

    @Test
    void activateAndDeactivateAreNoOpAndNeverThrowWhenAbsent() {
        assertDoesNotThrow(() -> {
            InProcessBridge.activate("com.x.T#m", null);
            InProcessBridge.deactivate("com.x.T#m", "passed");
        });
    }
}
