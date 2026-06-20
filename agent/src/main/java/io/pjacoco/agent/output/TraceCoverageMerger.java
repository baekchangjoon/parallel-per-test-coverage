package io.pjacoco.agent.output;

import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Report-time merge: groups an output directory's per-key {@code <key>.exec} snapshots by their resolved
 * testId ({@link TraceMapping#testIdFor}; unmapped keys fall back to the raw key, REQ-012) and OR-merges
 * each group into a single {@code <testId>.exec} (REQ-013). Authored against {@code org.jacoco.*} exactly
 * like {@link ExecWriter}/{@link AggregateWriter} so the shadow plugin relocates it consistently; the
 * {@code .exec} format is vanilla JaCoCo, so a merge over agent-produced files is interoperable.
 *
 * <p>This is the single-service seed of the cross-service {@code TraceCoverageMerger} (design §5.2-4); C3
 * extends it with a service axis, drain-wait, and central collection.
 */
public final class TraceCoverageMerger {

    /** Whole-run aggregate dump (AgentOptions.aggregateFile() default) — not a per-key snapshot, excluded. */
    static final String AGGREGATE_EXEC = "aggregate.exec";

    /** @param mapping nullable; null behaves as an always-unmapped mapping (everything keyed by raw key). */
    public void merge(Path inputDir, TraceMapping mapping, Path outputDir, Metrics metrics) throws Exception {
        if (!Files.isDirectory(inputDir)) return;
        Map<String, ExecFileLoader> byTestId = new LinkedHashMap<String, ExecFileLoader>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.exec")) {
            for (Path f : ds) {
                if (!Files.isRegularFile(f)) continue;                       // skip dirs named *.exec
                if (AGGREGATE_EXEC.equals(f.getFileName().toString())) continue;  // skip whole-run dump
                String key = stem(f);
                String testId = (mapping == null) ? null : mapping.testIdFor(key);
                if (testId == null) {
                    testId = key;
                    if (metrics != null) metrics.unmappedTraceIds.incrementAndGet();
                }
                ExecFileLoader loader = byTestId.get(testId);
                if (loader == null) { loader = new ExecFileLoader(); byTestId.put(testId, loader); }
                loader.load(f.toFile());                    // OR-merges same-classId probes
            }
        }
        if (byTestId.isEmpty()) return;
        Files.createDirectories(outputDir);
        for (Map.Entry<String, ExecFileLoader> e : byTestId.entrySet()) {
            writeMerged(outputDir, e.getKey(), e.getValue().getExecutionDataStore());
        }
    }

    private static void writeMerged(Path dir, String testId, ExecutionDataStore store) throws Exception {
        Path exec = dir.resolve(testId + ".exec");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            SessionInfoStore sessions = new SessionInfoStore();
            sessions.visitSessionInfo(new SessionInfo(testId, 0L, 0L));
            sessions.accept(w);
            store.accept(w);
        }
    }

    private static String stem(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.length() - ".exec".length());
    }
}
