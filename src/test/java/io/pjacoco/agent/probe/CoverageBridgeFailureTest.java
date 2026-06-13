package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoverageBridgeFailureTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void recordNeverThrowsWithoutContext() {
        assertDoesNotThrow(new org.junit.jupiter.api.function.Executable() {
            public void execute() { CoverageBridge.recordCoverage(String.class, 1L, 0); }
        });
    }

    @Test
    void recordNeverThrowsOnOutOfRangeProbe() {
        CoverageBridge.setTotalProbeCount("java/lang/String", 2);
        CoverageContext.set(new TestStore("T1", 1L, null));
        assertDoesNotThrow(new org.junit.jupiter.api.function.Executable() {
            public void execute() { CoverageBridge.recordCoverage(String.class, 1L, 99); }
        });
    }
}
