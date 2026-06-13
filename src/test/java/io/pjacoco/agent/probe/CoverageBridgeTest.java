package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoverageBridgeTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void recordsIntoActiveStoreSizedByProbeCount() {
        TestStore store = new TestStore("T1", 1L, null);
        CoverageBridge.setTotalProbeCount("java/lang/String", 3);
        CoverageContext.set(store);

        CoverageBridge.recordCoverage(String.class, 42L, 1);

        assertEquals(1, store.classCount());
        assertArrayEquals(new boolean[]{false, true, false}, store.snapshot().get(42L).probes());
    }

    @Test
    void noContextIsNoOp() {
        CoverageBridge.recordCoverage(String.class, 42L, 1); // no active store -> must not throw
    }

    @Test
    void neverThrowsOnBadInput() {
        CoverageContext.set(new TestStore("T1", 1L, null));
        assertDoesNotThrow(new org.junit.jupiter.api.function.Executable() {
            public void execute() { CoverageBridge.recordCoverage(String.class, 7L, 4); }
        });
    }
}
