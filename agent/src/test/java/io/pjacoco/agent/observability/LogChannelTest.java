package io.pjacoco.agent.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Regression tests for the 2026-07-27 dogfooding findings BUG-7/BUG-8, encoding how they
 * were discovered:
 * <ul>
 *   <li>BUG-7: every {@code mvn test} showed surefire's "Corrupted channel by directly writing
 *       to native stream in forked JVM" warning + a dumpstream containing the agent's two
 *       startup INFO lines — because {@link AgentLog#info} wrote to stdout, which surefire
 *       owns as its process communication channel. All agent logging must go to stderr.</li>
 *   <li>BUG-8: the shutdown summary printed "[pjacoco] [pjacoco] summary: ..." because
 *       {@link Metrics#summary()} embedded the prefix that {@link AgentLog#info} adds.</li>
 * </ul>
 */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class LogChannelTest {

    @Test
    void infoWritesToStderrNotStdout() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outBuf, true));
            System.setErr(new PrintStream(errBuf, true));
            new AgentLog().info("agent installed");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals("", outBuf.toString(),
                "stdout is surefire's forked-JVM channel; agent logs there corrupt it (BUG-7)");
        assertTrue(errBuf.toString().contains("[pjacoco] agent installed"));
    }

    @Test
    void summaryDoesNotDuplicateTheLogPrefix() {
        String summary = new Metrics().summary();
        assertFalse(summary.startsWith("[pjacoco]"),
                "AgentLog.info already prepends [pjacoco]; summary() embedding it too printed "
                        + "'[pjacoco] [pjacoco] summary: ...' (BUG-8)");
        assertTrue(summary.startsWith("summary:"));
    }
}
