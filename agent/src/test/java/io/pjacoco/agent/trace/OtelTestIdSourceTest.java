package io.pjacoco.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OtelTestIdSourceTest {

    @Test
    void invalidSpanFallsBackToNull() {
        OtelTestIdSource src = new OtelTestIdSource() {
            @Override protected boolean valid() { return false; }
            @Override protected String traceId() { return "00000000000000000000000000000000"; }
        };
        assertNull(src.currentKey());
    }

    @Test
    void validSpanReturnsTraceId() {
        OtelTestIdSource src = new OtelTestIdSource() {
            @Override protected boolean valid() { return true; }
            @Override protected String traceId() { return "4bf92f3577b34da6a3ce929d0e0e4736"; }
        };
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", src.currentKey());
    }

    @Test
    void throwingSeamFallsBackToNull() {
        OtelTestIdSource src = new OtelTestIdSource() {
            @Override protected boolean valid() { throw new RuntimeException("otel not on classpath"); }
        };
        assertNull(src.currentKey());
    }
}
