package io.pjacoco.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BraveTestIdSourceTest {

    @Test
    void nullContextFallsBack() {
        assertNull(new BraveTestIdSource() {
            @Override
            protected String resolveTraceId() {
                return null;
            }
        }.currentKey());
    }

    @Test
    void validContextReturnsTraceId() {
        assertEquals("abc123", new BraveTestIdSource() {
            @Override
            protected String resolveTraceId() {
                return "abc123";
            }
        }.currentKey());
    }

    @Test
    void emptyStringFallsBack() {
        assertNull(new BraveTestIdSource() {
            @Override
            protected String resolveTraceId() {
                return "";
            }
        }.currentKey());
    }

    @Test
    void throwingSeamFallsBack() {
        assertNull(new BraveTestIdSource() {
            @Override
            protected String resolveTraceId() throws Exception {
                throw new RuntimeException("Brave not available");
            }
        }.currentKey());
    }
}
