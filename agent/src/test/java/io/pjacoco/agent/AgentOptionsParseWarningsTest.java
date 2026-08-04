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
    void knownKeysSetIsFrozenForThisCycle() throws Exception {
        // REQ-MM-015(a): Surface-invariant guard — no new agent options this cycle.
        // Pin the cardinality of AgentOptions.KNOWN_KEYS (private static final Set<String>).
        // Any attempt to add a 24th key will fail this assertion and force spec review.

        // Reflection: read private KNOWN_KEYS field and assert its size is exactly 23
        java.lang.reflect.Field knownKeysField = AgentOptions.class.getDeclaredField("KNOWN_KEYS");
        knownKeysField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> knownKeys = (java.util.Set<String>) knownKeysField.get(null);
        assertEquals(23, knownKeys.size(),
                "KNOWN_KEYS cardinality must remain 23 this cycle; adding new options requires spec update");

        // Behavioral guard: all existing keys parse without warning
        assertTrue(AgentOptions.parse("destdir=/x,control=false").parseWarnings().isEmpty(),
                "existing documented options must parse without warnings");

        // Behavioral guard: any unknown key produces a warning
        assertEquals(1, AgentOptions.parse("someNewOption=1").parseWarnings().size(),
                "unknown options are rejected with a parse warning; new ones require spec review");
    }
}
