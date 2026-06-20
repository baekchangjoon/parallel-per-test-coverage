package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestStoreDropTest {
    @Test
    void dropCount_defaultsToZero() {
        TestStore s = new TestStore("T1", 1L, null);
        assertEquals(0L, s.droppedProbes());
    }

    @Test
    void recordDrop_increments() {
        TestStore s = new TestStore("T1", 1L, null);
        s.recordDrop();
        s.recordDrop();
        assertEquals(2L, s.droppedProbes());
    }
}
