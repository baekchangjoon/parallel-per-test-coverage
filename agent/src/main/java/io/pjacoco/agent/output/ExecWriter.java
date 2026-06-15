package io.pjacoco.agent.output;

import io.pjacoco.agent.store.ClassProbes;
import io.pjacoco.agent.store.TestStore;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;

/** Serializes a TestStore snapshot to a vanilla-format {@code <testId>.exec} + {@code <testId>.json} sidecar. */
public final class ExecWriter {

    /** @param status "complete" for a normal stop, "partial" for a shutdown-forced dump. */
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis, String status) throws Exception {
        Files.createDirectories(dir);
        Map<Long, ClassProbes> snap = store.snapshot();

        Path exec = dir.resolve(store.testId() + ".exec");
        OutputStream os = new BufferedOutputStream(Files.newOutputStream(exec));
        try {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(store.testId(),
                    store.startedAtMillis(), stoppedAtMillis));
            for (Map.Entry<Long, ClassProbes> e : snap.entrySet()) {
                w.visitClassExecution(new ExecutionData(
                        e.getKey(), e.getValue().className(), e.getValue().probes()));
            }
        } finally {
            os.close();
        }

        String json = new Json()
                .put("testId", store.testId())
                .put("exec", store.testId() + ".exec")
                .put("precision", "line")
                .put("startedAtMillis", store.startedAtMillis())
                .put("stoppedAtMillis", stoppedAtMillis)
                .put("durationMs", stoppedAtMillis - store.startedAtMillis())
                .put("result", result)                 // null -> omitted
                .put("classCount", store.classCount())
                .put("retryCount", store.retryCount())
                .put("shardId", store.shardId())        // null -> omitted
                .put("status", status)
                .toString();
        Files.write(dir.resolve(store.testId() + ".json"), json.getBytes("UTF-8"));
    }

    /** Convenience overload used by a normal stop. */
    public void write(Path dir, TestStore store, String result, String commitSha,
                      long stoppedAtMillis) throws Exception {
        write(dir, store, result, commitSha, stoppedAtMillis, "complete");
    }
}
