package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestStoreDropTest {
    @Test
    void dropFields_defaultZeroAndExact() {
        TestStore s = new TestStore("T1", 1L, null);
        assertEquals(0L, s.droppedProbes());
        assertFalse(s.attributionConservative());
    }

    @Test
    void recordDrop_increments_andMarkConservative() {
        TestStore s = new TestStore("T1", 1L, null);
        s.recordDrop();
        s.recordDrop();
        s.markConservative();
        assertEquals(2L, s.droppedProbes());
        assertTrue(s.attributionConservative());
    }
}
