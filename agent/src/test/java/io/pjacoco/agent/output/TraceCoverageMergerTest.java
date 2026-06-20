package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceCoverageMergerTest {

    /** Write a single-class .exec named {@code key.exec} covering exactly {@code probeIdx} of 2 probes. */
    private static void writeExec(Path dir, String key, long classId, String className, int probeIdx) throws Exception {
        Files.createDirectories(dir);
        boolean[] probes = new boolean[] { false, false };
        probes[probeIdx] = true;
        Path exec = dir.resolve(key + ".exec");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, className, probes));
        }
    }

    private static boolean[] probesFor(Path execFile, long classId) throws Exception {
        ExecFileLoader l = new ExecFileLoader();
        l.load(execFile.toFile());
        ExecutionData d = l.getExecutionDataStore().get(classId);
        return d == null ? null : d.getProbes();
    }

    @Test
    void multipleTraceIdsOneTestId(@TempDir Path tmp) throws Exception {       // REQ-013
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "T1", 42L, "com/x/Svc", 0);
        writeExec(in, "T2", 42L, "com/x/Svc", 1);
        TraceMapping map = traceId -> "com.x.Svc#it";                          // both map to one testId
        new TraceCoverageMerger().merge(in, map, out, new Metrics());

        Path merged = out.resolve("com.x.Svc#it.exec");
        assertTrue(Files.exists(merged));
        boolean[] p = probesFor(merged, 42L);
        assertNotNull(p);
        assertTrue(p[0] && p[1], "both traceIds' probes must be OR-merged");    // union
    }

    @Test
    void unmappedTraceFallsBackToRawAndCounts(@TempDir Path tmp) throws Exception {  // REQ-012 + REQ-019
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "rawTrace123", 7L, "com/x/Other", 0);
        Metrics metrics = new Metrics();
        TraceMapping empty = traceId -> null;                                  // nothing registered
        new TraceCoverageMerger().merge(in, empty, out, metrics);

        assertTrue(Files.exists(out.resolve("rawTrace123.exec")), "raw traceId used as testId");
        assertEquals(1L, metrics.unmappedTraceIds.get());
    }

    @Test
    void emptyInputDirIsNoOp(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        Files.createDirectories(in);
        new TraceCoverageMerger().merge(in, traceId -> null, out, new Metrics());
        // no throw; out may be created empty
        assertTrue(!Files.exists(out) || Files.list(out).count() == 0);
    }

    @Test
    void aggregateExecAndDirsAreExcluded(@TempDir Path tmp) throws Exception {   // operational contract
        Path in = tmp.resolve("in"), out = tmp.resolve("out");
        writeExec(in, "T1", 9L, "com/x/Svc", 0);
        writeExec(in, "aggregate", 9L, "com/x/Svc", 1);     // whole-run dump -> aggregate.exec, must be skipped
        Files.createDirectories(in.resolve("nested.exec"));  // a directory ending in .exec, must be skipped
        Metrics metrics = new Metrics();
        new TraceCoverageMerger().merge(in, traceId -> null, out, metrics);

        assertTrue(Files.exists(out.resolve("T1.exec")));
        assertTrue(!Files.exists(out.resolve("aggregate.exec")), "aggregate.exec must not be merged");
        assertEquals(1L, metrics.unmappedTraceIds.get(), "only T1 counted, not aggregate/dir");
    }
}
