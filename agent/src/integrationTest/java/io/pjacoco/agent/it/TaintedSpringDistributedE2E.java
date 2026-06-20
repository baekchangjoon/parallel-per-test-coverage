package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-015 live E2E (OTel vector): cross-service per-test coverage merge on the tainted-spring
 * OpenTelemetry/Kafka stack.
 *
 * <p>Delegates to {@code agent/e2e/tainted-spring-distributed-coverage.sh} which:
 * <ol>
 *   <li>Starts diary (producer) and mindgraph (consumer) (+ Zookeeper/Kafka/Postgres) via Docker
 *       Compose with the OTel javaagent (2.11.0) AND the pjacoco agent injected into both JVMs.</li>
 *   <li>POSTs {@code /internal/diaries} with a fixed W3C {@code traceparent}; OTel propagates the
 *       traceId across the {@code diary.created} Kafka hop to mindgraph's {@code DiaryCreatedConsumer}
 *       (a separate JVM).</li>
 *   <li>Waits 25 s for idle-reaper flush and Kafka consumer drain.</li>
 *   <li>Merges via {@code TraceMergeMain} with a central traceId→testId map.</li>
 *   <li>Asserts {@code report/<service>/<testId>.exec} exists and is non-empty (&gt;70 B) for BOTH
 *       diary and the downstream Kafka consumer mindgraph.</li>
 * </ol>
 *
 * <p>Skipped (not failed) when Docker is unavailable or {@code TAINTED_SPRING_ROOT} (default
 * {@code ~/github_tainted-spring/tainted-spring-platform}) does not exist — so normal CI runs
 * without the external stack do not break.
 *
 * <p>Proven on 2026-06-20: diary 842 B, mindgraph (Kafka consumer downstream) 1072 B — both
 * attributed to one testId under the same OTel traceId.
 */
@DisplayName("REQ-015: cross-service per-test coverage merged (OTel/tainted-spring)")
class TaintedSpringDistributedE2E {

    private static final String DEFAULT_TAINTED_SPRING_ROOT =
            System.getProperty("user.home") + "/github_tainted-spring/tainted-spring-platform";

    @Test
    @DisplayName("REQ-015: cross-service per-test coverage merged (OTel/tainted-spring)")
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
                "Skipping REQ-015 OTel live E2E: Docker is not reachable on this host.");

        // --- guard: TAINTED_SPRING_ROOT present ---
        String tsRoot = System.getenv("TAINTED_SPRING_ROOT");
        if (tsRoot == null || tsRoot.isEmpty()) {
            tsRoot = DEFAULT_TAINTED_SPRING_ROOT;
        }
        Path tsPath = Paths.get(tsRoot);
        assumeTrue(Files.isDirectory(tsPath),
                "Skipping REQ-015 OTel live E2E: TAINTED_SPRING_ROOT not found: " + tsRoot);

        // --- locate the runner script (repo root contains gradlew) ---
        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve("agent/e2e/tainted-spring-distributed-coverage.sh");
        assumeTrue(Files.exists(script),
                "Skipping REQ-015 OTel live E2E: runner script not found: " + script);

        // --- run the script ---
        ProcessBuilder pb = new ProcessBuilder("bash", script.toAbsolutePath().toString())
                .inheritIO();
        pb.environment().put("TAINTED_SPRING_ROOT", tsRoot);
        Process proc = pb.start();
        boolean finished = proc.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            fail("tainted-spring distributed E2E timed out after 15 min");
        }
        assertEquals(0, proc.exitValue(),
                "REQ-015 OTel live E2E script exited with non-zero status — see output above.");
    }

    /** Walk up from CWD to find the git repo root (contains gradlew). */
    private static Path findRepoRoot() {
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
        return cwd;
    }
}
