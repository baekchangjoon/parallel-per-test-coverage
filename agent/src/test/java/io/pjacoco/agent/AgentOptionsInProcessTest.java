package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentOptionsInProcessTest {

    @Test
    void aggregateDefaultsOnWithDefaultFileName() {
        AgentOptions o = AgentOptions.empty();
        assertTrue(o.aggregate(), "aggregate must default to true");
        assertEquals("aggregate.exec", o.aggregateFile(), "aggregateFile default name");
        assertTrue(o.junit4Auto(), "junit4Auto must default to true");
    }

    @Test
    void aggregateCanBeDisabledAndFileRenamed() {
        AgentOptions o = AgentOptions.parse("aggregate=false,aggregateFile=whole.exec,junit4Auto=false");
        assertFalse(o.aggregate());
        assertEquals("whole.exec", o.aggregateFile());
        assertFalse(o.junit4Auto());
    }
}
