package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process regression for the {@code includes=*} premain crash. A child JVM is launched with the
 * shaded agent and the default wildcard include; before the fix this aborted in premain with the native
 * {@code *** java.lang.instrument ASSERTION FAILED ***} (bootstrap classes) / an {@code IllegalAccessError}
 * on {@code com.sun.net.httpserver.HttpServer} (platform-loaded JDK class). The agent must instead run
 * past premain and let the application proceed. Gated on the shaded jar being available
 * ({@code pjacoco.shadedJar}, wired by the {@code test} task).
 */
class WildcardIncludesCrashE2E {

    private static final String SENTINEL = "PJACOCO_PREMAIN_OK";

    /** Child entry point: touch a few JDK classes, print the sentinel, then hard-exit past the agent's
     *  (non-daemon) control-server thread so the child does not linger. */
    public static void main(String[] args) {
        java.util.logging.Logger.getLogger("pjacoco-probe").info("touch java.logging");
        java.util.regex.Pattern.compile("a(b)c").matcher("abc").matches();
        System.out.println(SENTINEL);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    @Test
    void wildcardIncludesDoesNotCrashPremain() throws Exception {
        String agentJar = System.getProperty("pjacoco.shadedJar");
        assumeTrue(agentJar != null && new File(agentJar).isFile(),
                "pjacoco.shadedJar not provided — shaded agent jar required for this out-of-process E2E");

        Path outDir = Files.createTempDirectory("pjacoco-wildcard-e2e");
        File childLog = outDir.resolve("child.log").toFile();
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = Arrays.asList(
                javaBin,
                "-javaagent:" + agentJar + "=includes=*,port=0,destfile=" + outDir.resolve("cov"),
                "-cp", System.getProperty("java.class.path"),
                WildcardIncludesCrashE2E.class.getName());

        // Redirect child output to a FILE and bound everything on waitFor — never drain the pipe with an
        // unbounded read, because the agent's control server is non-daemon: if the child's main threw
        // before halt(0), an in-process read loop would block forever instead of failing the assertions.
        Process child = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(childLog).start();
        boolean exited = child.waitFor(30, TimeUnit.SECONDS);
        if (!exited) child.destroyForcibly();
        String log = new String(Files.readAllBytes(childLog.toPath()), StandardCharsets.UTF_8);

        assertFalse(log.contains("ASSERTION FAILED"),
                "includes=* must not trip the native JPLIS assertion.\n--- child output ---\n" + log);
        assertFalse(log.contains("FATAL ERROR in native method"),
                "includes=* must not abort the VM in premain.\n--- child output ---\n" + log);
        assertTrue(log.contains(SENTINEL),
                "child must run past premain under includes=*.\n--- child output ---\n" + log);
    }
}
