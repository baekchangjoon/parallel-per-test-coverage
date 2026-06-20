package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.nio.file.Path;
import java.util.Map;

/** Cross-service report-time merge (design §5.2-4): runs the single-service {@link TraceCoverageMerger}
 *  per service into {@code reportDir/<service>/}, applying ONE central traceId->testId map. The service
 *  dimension is preserved as a subdirectory so JaCoCo classIds from different services never collide. */
public final class DistributedCoverageMerger {

    private final TraceCoverageMerger one = new TraceCoverageMerger();

    public void merge(Map<String, Path> serviceDirs, TraceMapping central, Path reportDir, Metrics metrics)
            throws Exception {
        for (Map.Entry<String, Path> e : serviceDirs.entrySet()) {
            try {
                one.merge(e.getValue(), central, reportDir.resolve(e.getKey()), metrics);
            } catch (Throwable t) {
                if (metrics != null) metrics.swallowedExceptions.incrementAndGet();   // best-effort per service
                System.err.println("[pjacoco] distributed merge failed for service '" + e.getKey() + "': " + t);
            }
        }
    }
}
