package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared-volume collector with drain-wait (REQ-023).
 *
 * <p>Waits a configurable drain window via a {@link Sleeper} hook so that async downstream
 * services (Tram, CDC, Kafka consumers) have time to flush their {@code .exec} files into the
 * shared volume. After the wait it discovers every per-service sub-directory and delegates to
 * {@link DistributedCoverageMerger} for report-time merging.
 */
public final class DistributedCollector {

    /**
     * Drain-wait abstraction. The real implementation wraps {@link Thread#sleep}; tests inject a
     * hook that performs side-effects (writing a late-arriving {@code .exec}) instead of waiting.
     *
     * <p>{@code throws Exception} intentionally — checked exceptions from test hooks must be
     * propagatable without wrapping.
     */
    public interface Sleeper {
        void sleep(long millis) throws Exception;
    }

    /** Real sleeper: delegates to {@link Thread#sleep}. */
    public static final Sleeper THREAD_SLEEP = Thread::sleep;

    /**
     * Collects per-service coverage from {@code sharedVolume} after a drain-wait.
     *
     * <ol>
     *   <li>Invokes {@code sleeper.sleep(drainWaitMillis)} — test hooks write late {@code .exec}
     *       files here; production blocks the calling thread.
     *   <li>Discovers every <em>sub-directory</em> of {@code sharedVolume} (hidden entries whose
     *       names start with {@code .} and stray files are skipped; {@code trace-map.properties}
     *       is also excluded by the directory-only filter).
     *   <li>Calls {@link DistributedCoverageMerger#merge} with the discovered service map.
     * </ol>
     *
     * <p>If {@code sharedVolume} is missing or contains no sub-directories, the method is a no-op
     * (a warning is printed to stderr). All per-service merge errors are best-effort (swallowed
     * internally by {@link DistributedCoverageMerger}).
     */
    public void collectAfterDrain(Path sharedVolume, TraceMapping central, Path reportDir,
            long drainWaitMillis, Sleeper sleeper, Metrics metrics) {
        // Step 1 — drain wait (lets async downstream .exec files arrive)
        try {
            sleeper.sleep(drainWaitMillis);
        } catch (Exception e) {
            System.err.println("[pjacoco] drain-wait interrupted: " + e);
        }

        // Step 2 — discover sub-directories only
        if (!Files.isDirectory(sharedVolume)) {
            System.err.println("[pjacoco] shared volume missing or not a directory: " + sharedVolume);
            return;
        }

        Map<String, Path> serviceDirs = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sharedVolume)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) continue;          // hidden — skip
                if (!Files.isDirectory(entry)) continue;     // stray files (incl. trace-map.properties) — skip
                serviceDirs.put(name, entry);
            }
        } catch (Exception e) {
            System.err.println("[pjacoco] failed to list shared volume '" + sharedVolume + "': " + e);
        }

        if (serviceDirs.isEmpty()) {
            System.err.println("[pjacoco] no service directories found under: " + sharedVolume);
            return;
        }

        // Step 3 — merge
        try {
            new DistributedCoverageMerger().merge(serviceDirs, central, reportDir, metrics);
        } catch (Exception e) {
            System.err.println("[pjacoco] distributed merge error: " + e);
        }
    }
}
