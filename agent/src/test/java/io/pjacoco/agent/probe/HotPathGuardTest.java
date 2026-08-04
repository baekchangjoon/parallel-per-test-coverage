package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * REQ-MM-015(b): Surface-invariant guard for the hot path (recordCoverage) method body.
 * The recordCoverage method is called per probe hit and must remain cheap. This test
 * pins the SHA-256 hash of the method body to detect unintended changes.
 *
 * The method body is delimited by // HOT-PATH-BEGIN and // HOT-PATH-END comments.
 * If the hot path must be changed, update this test's pinned hash AND the requirements
 * specification (the performance contract).
 */
class HotPathGuardTest {

    @Test
    void recordCoverageSourceUnchanged() throws Exception {
        String src = new String(
                Files.readAllBytes(
                        Paths.get("src/main/java/io/pjacoco/agent/probe/CoverageBridge.java")),
                StandardCharsets.UTF_8);

        int beginIdx = src.indexOf("// HOT-PATH-BEGIN");
        int endIdx = src.indexOf("// HOT-PATH-END");

        if (beginIdx < 0 || endIdx < 0) {
            throw new AssertionError("HOT-PATH markers not found in CoverageBridge.java");
        }

        // Extract from HOT-PATH-BEGIN through HOT-PATH-END (inclusive)
        String body = src.substring(beginIdx, endIdx + "// HOT-PATH-END".length());

        // Compute SHA-256
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
        String hash = bytesToHex(digest);

        assertEquals(
                "203605294dbb78e3fdaad762c0e768e227b7ae9c99170aa1f5d5a49a3917a741",
                hash,
                "recordCoverage method body hash changed; update the pinned hash AND the requirements spec");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
