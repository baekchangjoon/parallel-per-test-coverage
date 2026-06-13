package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestStoreTest {
    @Test
    void recordsProbesPerClass() {
        TestStore s = new TestStore("T1", 1000L, "shard-1");
        s.record(42L, "com/example/A", 0, 3);
        s.record(42L, "com/example/A", 2, 3);
        Map<Long, ClassProbes> snap = s.snapshot();
        assertEquals(1, snap.size());
        assertArrayEquals(new boolean[]{true, false, true}, snap.get(42L).probes());
        assertEquals("com/example/A", snap.get(42L).className());
    }

    @Test
    void snapshotIsCopy() {
        TestStore s = new TestStore("T1", 1000L, null);
        s.record(1L, "X", 0, 1);
        Map<Long, ClassProbes> snap = s.snapshot();
        s.record(1L, "X", 0, 1);
        assertFalse(snap.get(1L).probes() == s.snapshot().get(1L).probes());
    }

    @Test
    void classCountReflectsDistinctClasses() {
        TestStore s = new TestStore("T1", 1000L, null);
        s.record(1L, "A", 0, 1);
        s.record(2L, "B", 0, 1);
        s.record(1L, "A", 0, 1);
        assertEquals(2, s.classCount());
    }

    @Test
    void retryCountIncrements() {
        TestStore s = new TestStore("T1", 1000L, null);
        assertEquals(0, s.retryCount());
        s.incrementRetry();
        assertEquals(1, s.retryCount());
    }
}
