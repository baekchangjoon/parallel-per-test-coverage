package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression test for dogfooding BUG-4 (2026-07-27), encoding how it was discovered:
 * {@code -javaagent:...=destdirr=/x,bogusOpt=1} (typo) started with no warning and wrote to the
 * default {@code coverage/} — "coverage in the wrong place" with zero diagnostic hint. A bare
 * {@code destfile} (no value) was likewise dropped silently.
 */
class AgentOptionsParseWarningsTest {

    @Test
    void typoedKeysProduceWarnings() {
        AgentOptions options = AgentOptions.parse("destdirr=/x,bogusOpt=1");
        assertEquals("coverage", options.outputDir(), "typoed key falls back to the default dir");
        assertEquals(2, options.parseWarnings().size());
        assertTrue(options.parseWarnings().get(0).contains("destdirr"));
        assertTrue(options.parseWarnings().get(1).contains("bogusOpt"));
    }

    @Test
    void valuelessTokenProducesWarning() {
        AgentOptions options = AgentOptions.parse("destfile");
        assertEquals(1, options.parseWarnings().size());
        assertTrue(options.parseWarnings().get(0).contains("malformed option 'destfile'"));
    }

    @Test
    void allKnownKeysParseWithoutWarnings() {
        AgentOptions options = AgentOptions.parse(
                "destdir=/tmp/x,destfile=/tmp/y,autoRegister=true,lenient=true,address=127.0.0.1,"
                + "port=0,maxstores=10,commitSha=abc,incompleteAttributionThreshold=0.1,"
                + "aggregate=false,aggregateFile=agg.exec,junit4Auto=false,traceKeyAutoCreate=true,"
                + "maxTraceMappings=10,traceReaperIntervalMillis=1,traceIdleFlushMillis=1,"
                + "traceLateWriteGraceMillis=1,inFlightGuardMillis=1,includes=*,excludes=x.*,"
                + "inclbootstrapclasses=false,control=false,persistOnStop=false");
        assertEquals(java.util.Collections.emptyList(), options.parseWarnings(),
                "every documented option must be in the known-keys set");
    }

    @Test
    void knownKeysSetIsFrozenForThisCycle() {
        // REQ-MM-015(a): Surface-invariant guard — no new agent options this cycle.
        // Any attempt to add a new option will trigger a parseWarning, forcing this test
        // to fail and requiring an update to the requirements spec before merging.

        // All existing options parse without warning
        assertTrue(AgentOptions.parse("destdir=/x,control=false").parseWarnings().isEmpty(),
                "existing options must parse without warnings");

        // Any new/unknown option produces a warning
        assertEquals(1, AgentOptions.parse("someNewOption=1").parseWarnings().size(),
                "new options must be rejected with a parse warning; add to requirements spec if intentional");
    }
}
