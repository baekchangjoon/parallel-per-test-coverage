package io.pjacoco.agent.output;

import io.pjacoco.agent.store.ClassProbes;
import io.pjacoco.agent.store.TestStore;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;

/** Serializes a TestStore snapshot to a vanilla-format {@code <testId>.exec} + {@code <testId>.json} sidecar. */
public final class ExecWriter {

    /** Drop-ratio above which a store is flagged {@code incompleteAttribution} (CLS-REQ-009). Default 0.0. */
    private final double incompleteAttributionThreshold;

    public ExecWriter() { this(0.0); }

    public ExecWriter(double incompleteAttributionThreshold) {
        this.incompleteAttributionThreshold = incompleteAttributionThreshold;
    }

    /** Serialize store to JaCoCo exec bytes in memory. Does NOT touch disk. */
    public byte[] toExecBytes(TestStore store, long stoppedAtMillis) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeExecToStream(baos, store, store.snapshot(), stoppedAtMillis);
        return baos.toByteArray();
    }

    /** Write .exec file only (existing logic, call {@link #toExecBytes} internally). */
    public void writeExecFile(Path dir, TestStore store, long stoppedAtMillis) throws Exception {
        Files.createDirectories(dir);
        Path exec = dir.resolve(store.testId() + ".exec");
        OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec));
        try {
            writeExecToStream(os, store, store.snapshot(), stoppedAtMillis);
        } finally {
            os.close();
        }
    }

    /** Write .json sidecar only (Priority 3 may call this separately). */
    public void writeSidecarFile(Path dir, TestStore store, String result, String commitSha,
                                 long stoppedAtMillis, String status) throws Exception {
        Files.createDirectories(dir);
        Map<Long, ClassProbes> snap = store.snapshot();
        long recordedProbes = countRecordedProbes(snap);
        String json = buildSidecarJson(store, result, stoppedAtMillis, status, recordedProbes).toString();
        Files.write(dir.resolve(store.testId() + ".json"), json.getBytes("UTF-8"));
    }

    /** @param status "complete" for a normal stop, "partial" for a shutdown-forced dump. */
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis, String status) throws Exception {
        writeExecFile(dir, store, stoppedAtMillis);
        writeSidecarFile(dir, store, result, commitSha, stoppedAtMillis, status);
    }

    /** Convenience overload used by a normal stop. */
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis) throws Exception {
        write(dir, store, result, commitSha, stoppedAtMillis, "complete");
    }

    public long countRecordedProbes(TestStore store) {
        return countRecordedProbes(store.snapshot());
    }

    private long writeExecToStream(OutputStream os, TestStore store, Map<Long, ClassProbes> snap,
                                   long stoppedAtMillis) throws Exception {
        ExecutionDataWriter w = new ExecutionDataWriter(os);
        w.visitSessionInfo(new SessionInfo(store.testId(), store.startedAtMillis(), stoppedAtMillis));
        long recordedProbes = 0;
        for (Map.Entry<Long, ClassProbes> e : snap.entrySet()) {
            boolean[] probes = e.getValue().probes();
            for (boolean hit : probes) {
                if (hit) {
                    recordedProbes++;
                }
            }
            w.visitClassExecution(new ExecutionData(e.getKey(), e.getValue().className(), probes));
        }
        return recordedProbes;
    }

    private Json buildSidecarJson(TestStore store, String result, long stoppedAtMillis, String status,
                                  long recordedProbes) {
        Json j = new Json()
                .put("testId", store.testId())
                .put("exec", store.testId() + ".exec")
                .put("precision", "line")
                .put("startedAtMillis", store.startedAtMillis())
                .put("stoppedAtMillis", stoppedAtMillis)
                .put("durationMs", stoppedAtMillis - store.startedAtMillis())
                .put("result", result)
                .put("classCount", store.classCount())
                .put("retryCount", store.retryCount())
                .put("shardId", store.shardId())
                .put("status", status);
        long dropped = store.droppedProbes();
        if (dropped > 0) {
            long denom = dropped + recordedProbes;
            double ratio = denom == 0 ? 0.0 : (double) dropped / (double) denom;
            j.put("droppedProbes", dropped)
             .put("recordedProbes", recordedProbes);
            if (ratio > incompleteAttributionThreshold) {
                j.put("incompleteAttribution", true)
                 .put("attribution", "exact");
            }
        }
        return j;
    }

    private static long countRecordedProbes(Map<Long, ClassProbes> snap) {
        long recordedProbes = 0;
        for (Map.Entry<Long, ClassProbes> e : snap.entrySet()) {
            for (boolean hit : e.getValue().probes()) {
                if (hit) {
                    recordedProbes++;
                }
            }
        }
        return recordedProbes;
    }
}
