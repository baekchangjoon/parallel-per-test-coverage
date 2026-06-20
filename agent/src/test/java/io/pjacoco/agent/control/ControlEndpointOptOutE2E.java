package io.pjacoco.agent.control;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * REQ-U01 (P2-D): out-of-process behavior of the control endpoint options.
 * <ul>
 *   <li>{@code control=false} → the endpoint is never started, so its port stays free (no bind cost /
 *       conflict for pure aggregate/in-process users);</li>
 *   <li>default ({@code control=true}) → the endpoint binds the configured port;</li>
 *   <li>{@code port=0} → an ephemeral port is bound and surfaced via {@code pjacoco.control-port}.</li>
 * </ul>
 * Uses an uncommon port (6399) to avoid colliding with the other suites that use 63xx.
 */
class ControlEndpointOptOutE2E {

    private static final int TEST_PORT = 6399;

    /** Child entry point. {@code bindcheck <port>} reports whether the port is bindable; {@code showport}
     *  prints the discovered control port. Then hard-exit past the (non-daemon) control server. */
    public static void main(String[] args) {
        if (args.length >= 2 && "bindcheck".equals(args[0])) {
            int port = Integer.parseInt(args[1]);
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                System.out.println("PORT_" + port + "_FREE");
            } catch (Exception e) {
                System.out.println("PORT_" + port + "_TAKEN");
            }
        } else if (args.length >= 1 && "showport".equals(args[0])) {
            System.out.println("CONTROL_PORT=" + System.getProperty("pjacoco.control-port"));
        }
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static String runChild(String agentOpts, String... mainArgs) throws Exception {
        String agentJar = System.getProperty("pjacoco.shadedJar");
        assumeTrue(agentJar != null && new File(agentJar).isFile(),
                "pjacoco.shadedJar not provided — shaded agent jar required for this out-of-process E2E");
        Path outDir = Files.createTempDirectory("pjacoco-ctrl-e2e");
        File childLog = outDir.resolve("child.log").toFile();
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.add("-javaagent:" + agentJar + "=" + agentOpts + ",destfile=" + outDir.resolve("cov"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ControlEndpointOptOutE2E.class.getName());
        cmd.addAll(Arrays.asList(mainArgs));

        Process child = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(childLog).start();
        boolean exited = child.waitFor(30, TimeUnit.SECONDS);
        if (!exited) child.destroyForcibly();
        return new String(Files.readAllBytes(childLog.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void controlFalseDoesNotBindThePort() throws Exception {
        String out = runChild("control=false,port=" + TEST_PORT, "bindcheck", String.valueOf(TEST_PORT));
        assertTrue(out.contains("PORT_" + TEST_PORT + "_FREE"),
                "control=false must leave the port free.\n--- child output ---\n" + out);
        assertTrue(out.contains("control endpoint disabled"),
                "control=false should log the disabled endpoint.\n--- child output ---\n" + out);
    }

    @Test
    void defaultControlBindsThePort() throws Exception {
        String out = runChild("port=" + TEST_PORT, "bindcheck", String.valueOf(TEST_PORT));
        assertTrue(out.contains("PORT_" + TEST_PORT + "_TAKEN"),
                "default control=true must bind the configured port.\n--- child output ---\n" + out);
    }

    @Test
    void ephemeralPortIsExposedViaSystemProperty() throws Exception {
        String out = runChild("port=0", "showport");
        Matcher m = Pattern.compile("CONTROL_PORT=(\\d+)").matcher(out);
        assertTrue(m.find(), "port=0 must expose the actual port via pjacoco.control-port.\n--- child output ---\n" + out);
        assertTrue(Integer.parseInt(m.group(1)) > 0,
                "exposed control port must be a real ephemeral port.\n--- child output ---\n" + out);
    }
}
