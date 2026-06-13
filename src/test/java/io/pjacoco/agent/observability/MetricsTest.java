package io.pjacoco.agent.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsTest {
    @Test
    void countersAndSummary() {
        Metrics m = new Metrics();
        m.testsCompleted.incrementAndGet();
        m.testsCompleted.incrementAndGet();
        m.swallowedExceptions.incrementAndGet();
        m.rejectedUnregistered.incrementAndGet();
        m.partialDumps.incrementAndGet();
        String s = m.summary();
        assertTrue(s.contains("completed=2"), s);
        assertTrue(s.contains("partial=1"), s);
        assertTrue(s.contains("swallowed=1"), s);
        assertTrue(s.contains("rejected=1"), s);
    }
}
