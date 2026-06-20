package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-015 live E2E: cross-service per-test coverage merge on the legacy-tram Brave/Tram stack.
 *
 * <p>Delegates to {@code agent/e2e/legacy-tram-distributed-coverage.sh} which:
 * <ol>
 *   <li>Starts order-web, reservation, ledger (+ Zookeeper/Kafka/MySQL/CDC) via Docker Compose
 *       with pjacoco agent injected into all three Java services.</li>
 *   <li>POSTs orders with a fixed B3 traceId across order-web → reservation →
 *       (Tram/Kafka/CDC) → ledger.</li>
 *   <li>Waits 20 s for idle-reaper flush and async CDC drain.</li>
 *   <li>Merges via {@code TraceMergeMain} with a central traceId→testId map.</li>
 *   <li>Asserts that {@code report/<service>/<testId>.exec} exists and is non-empty (>40 B)
 *       for ALL THREE services including the downstream Kafka/CDC ledger.</li>
 * </ol>
 *
 * <p>The test is <em>skipped</em> (not failed) when Docker is unavailable or when
 * {@code LEGACY_TRAM_ROOT} (default
 * {@code ~/github_graph-rag-test-generator/graph-rag/samples/legacy-tram}) does not exist —
 * so normal CI runs without the external stack do not break.
 *
 * <p>Proven on 2026-06-20: order-web classCount=6, reservation=6, ledger=2.
 */
@DisplayName("REQ-015: cross-service per-test coverage merged (Brave/legacy-tram)")
class LegacyTramDistributedE2E {

    private static final String DEFAULT_LEGACY_TRAM_ROOT =
            System.getProperty("user.home")
            + "/github_graph-rag-test-generator/graph-rag/samples/legacy-tram";

    @Test
    @DisplayName("REQ-015: cross-service per-test coverage merged (Brave/legacy-tram)")
    void crossServiceCoverageMerged() throws Exception {
        // --- guard: Docker available ---
        boolean dockerAvailable;
        try {
            Process dockerInfo = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            dockerAvailable = dockerInfo.waitFor(10, TimeUnit.SECONDS) && dockerInfo.exitValue() == 0;
        } catch (Exception e) {
            dockerAvailable = false;
        }
        assumeTrue(dockerAvailable,
                "Skipping REQ-015 live E2E: Docker is not reachable on this host.");

        // --- guard: LEGACY_TRAM_ROOT present ---
        String tramRoot = System.getenv("LEGACY_TRAM_ROOT");
        if (tramRoot == null || tramRoot.isEmpty()) {
            tramRoot = DEFAULT_LEGACY_TRAM_ROOT;
        }
        Path tramPath = Paths.get(tramRoot);
        assumeTrue(Files.isDirectory(tramPath),
                "Skipping REQ-015 live E2E: LEGACY_TRAM_ROOT not found: " + tramRoot);

        // --- locate the runner script ---
        // Resolved relative to the repo root (two levels up from agent/e2e/).
        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve("agent/e2e/legacy-tram-distributed-coverage.sh");
        assumeTrue(Files.exists(script),
                "Skipping REQ-015 live E2E: runner script not found: " + script);

        // --- run the script ---
        ProcessBuilder pb = new ProcessBuilder("bash", script.toAbsolutePath().toString())
                .inheritIO();
        if (tramRoot != null) {
            pb.environment().put("LEGACY_TRAM_ROOT", tramRoot);
        }
        Process proc = pb.start();
        boolean finished = proc.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
        }
        assertEquals(0, proc.exitValue(),
                "REQ-015 live E2E script exited with non-zero status — see output above.");
    }

    /** Walk up from the class file location to find the git repo root (contains gradlew). */
    private static Path findRepoRoot() {
        // Try: CWD, then walk up from CWD
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        Path cur = cwd;
        for (int i = 0; i < 8; i++) {
            if (Files.exists(cur.resolve("gradlew"))) {
                return cur;
            }
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        // Fall back to CWD — the script's own relative resolution will handle it.
        return cwd;
    }
}
