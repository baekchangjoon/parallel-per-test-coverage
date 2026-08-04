package io.pjacoco.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HeaderStyleCoreTest {

    @AfterEach
    void clear() {
        Pjacoco.clearCurrentTestId();
    }

    @Test
    void fieldHeaderAccessors() {
        Pjacoco.setCurrentTestId("T1#m");
        assertEquals("test.id", Pjacoco.fieldHeaderName());
        assertEquals("T1#m", Pjacoco.fieldHeaderValue());
    }

    @Test
    void fieldHeaderValueNullWhenNoActiveTest() {
        assertNull(Pjacoco.fieldHeaderValue());
    }

    @Test
    void headerStyleHasThreeValues() {
        assertEquals(3, HeaderStyle.values().length);
    }
}
