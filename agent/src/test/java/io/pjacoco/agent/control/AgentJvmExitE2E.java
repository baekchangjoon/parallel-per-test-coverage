package io.pjacoco.agent.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process regression test for dogfooding BUG-1 (2026-07-27), encoding exactly how it was
 * found: {@code java -javaagent:pjacoco-agent.jar=... MainThatReturns} never exited — the control
 * endpoint's JDK HTTP-Dispatcher thread was non-daemon (inherited from premain), and the shutdown
 * hook that would stop the server only runs once shutdown has already begun. The observable
 * symptoms were (a) the JVM hanging after main returned and (b) {@code aggregate.exec} never
 * appearing on the normal-exit path. (The sibling {@link ControlEndpointOptOutE2E} previously
 * masked this by calling {@code Runtime.halt(0)}.)
 */
class AgentJvmExitE2E {

    /** Child entry point: do a little covered work, then simply RETURN from main — no System.exit,
     *  no halt. With the agent attached, JVM exit must happen on its own. */
    public static void main(String[] args) {
        System.out.println("MAIN_RETURNED");
        System.out.flush();
    }

    @Test
    void agentOnlyJvmExitsAfterMainReturnsAndWritesAggregate(
            @org.junit.jupiter.api.io.TempDir Path outDir) throws Exception {
        String agentJar = System.getProperty("pjacoco.shadedJar");
        assumeTrue(agentJar != null && new File(agentJar).isFile(),
                "pjacoco.shadedJar not provided — shaded agent jar required for this out-of-process E2E");
        File childLog = outDir.resolve("child.log").toFile();
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.add("-javaagent:" + agentJar + "=port=0,destfile=" + outDir.resolve("cov"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(AgentJvmExitE2E.class.getName());

        Process child = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(childLog).start();
        boolean exited;
        try {
            exited = child.waitFor(30, TimeUnit.SECONDS);
        } finally {
            // Every exit path (timeout, interrupt, assertion throw below) must reap the child —
            // and wait for the kill so the test never returns with the process still dying.
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
        String out = new String(Files.readAllBytes(childLog.toPath()), StandardCharsets.UTF_8);

        assertTrue(exited, "the JVM must exit on its own after main returns — a non-daemon control"
                + " server thread pins it forever (BUG-1).\n--- child output ---\n" + out);
        assertEquals(0, child.exitValue(), "clean exit expected.\n--- child output ---\n" + out);
        assertTrue(out.contains("MAIN_RETURNED"), "child main must have run.\n--- child output ---\n" + out);
        assertTrue(Files.exists(outDir.resolve("cov").resolve("aggregate.exec")),
                "the shutdown hook must run on the normal-exit path and write aggregate.exec"
                        + " (it never ran while the JVM hung).\n--- child output ---\n" + out);
    }
}
