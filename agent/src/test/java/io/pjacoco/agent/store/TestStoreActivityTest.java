package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TestStoreActivityTest {
    @Test
    void writesIncrementOnRecord() {
        TestStore s = new TestStore("k", 1000L, null);
        assertEquals(0L, s.writes());
        s.record(7L, "com/x/A", 0, 2);
        s.record(7L, "com/x/A", 1, 2);
        assertEquals(2L, s.writes(), "each record() bumps the activity counter");
    }
    @Test
    void lastActivityDefaultsToStartAndIsSettable() {
        TestStore s = new TestStore("k", 1000L, null);
        assertEquals(1000L, s.lastActivityMillis(), "defaults to startedAtMillis");
        s.lastActivityMillis(5000L);
        assertEquals(5000L, s.lastActivityMillis());
    }
}
