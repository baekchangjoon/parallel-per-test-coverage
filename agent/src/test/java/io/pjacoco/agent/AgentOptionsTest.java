package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentOptionsTest {
    @Test
    void parsesKnownOptions() {
        AgentOptions o = AgentOptions.parse(
                "destfile=coverage,includes=com.example.*,autoRegister=true,port=6310,commitSha=abc");
        assertEquals("coverage", o.outputDir());
        assertEquals("com.example.*", o.includes());
        assertTrue(o.autoRegister());
        assertEquals(6310, o.controlPort());
        assertEquals("abc", o.commitSha());
    }

    @Test
    void acceptsLegacyLenientAlias() {
        assertTrue(AgentOptions.parse("lenient=true").autoRegister());
        assertFalse(AgentOptions.parse("autoRegister=false,lenient=true").autoRegister());
    }

    @Test
    void defaultsWhenEmpty() {
        AgentOptions o = AgentOptions.parse(null);
        assertEquals("coverage", o.outputDir());
        assertFalse(o.autoRegister());
        assertEquals(6310, o.controlPort());
        assertEquals("127.0.0.1", o.controlHost());
        assertEquals(1000, o.maxStores());
        assertNull(o.commitSha());
    }
}
