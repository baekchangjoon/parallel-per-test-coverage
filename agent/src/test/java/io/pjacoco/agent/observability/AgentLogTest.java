package io.pjacoco.agent.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** REQ-U03: premain failures must surface a self-identifying {@code [pjacoco][ERROR]} line. */
class AgentLogTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void capture() {
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
    }

    @AfterEach
    void restore() {
        System.setErr(originalErr);
    }

    @Test
    void errorPrintsPjacocoErrorPrefix() {
        new AgentLog().error("init", "agent initialization failed: boom");
        assertTrue(captured.toString().contains("[pjacoco][ERROR] agent initialization failed: boom"),
                "AgentLog.error must emit a [pjacoco][ERROR] line; got: " + captured);
    }
}
