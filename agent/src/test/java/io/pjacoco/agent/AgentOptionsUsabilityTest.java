package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** REQ-U01 (control opt-out) + REQ-U04 (destdir alias) option parsing. */
class AgentOptionsUsabilityTest {

    @Test
    void controlDefaultsTrue() {
        assertTrue(AgentOptions.empty().control(), "control endpoint is on by default (backward-compat)");
    }

    @Test
    void controlFalseDisables() {
        assertFalse(AgentOptions.parse("control=false").control());
    }

    @Test
    void destdirAliasResolvesOutputDir() {
        assertEquals("foo", AgentOptions.parse("destdir=foo").outputDir());
    }

    @Test
    void destdirTakesPrecedenceOverDestfile() {
        assertEquals("foo", AgentOptions.parse("destdir=foo,destfile=bar").outputDir());
    }

    @Test
    void emptyDestdirIsTreatedAsAbsent() {
        assertEquals("bar", AgentOptions.parse("destdir=,destfile=bar").outputDir(),
                "empty destdir falls back to destfile");
        assertEquals("coverage", AgentOptions.parse("destdir=").outputDir(),
                "empty destdir with no destfile falls back to the default");
    }

    @Test
    void destfileStillWorks() {
        assertEquals("bar", AgentOptions.parse("destfile=bar").outputDir());
    }
}
