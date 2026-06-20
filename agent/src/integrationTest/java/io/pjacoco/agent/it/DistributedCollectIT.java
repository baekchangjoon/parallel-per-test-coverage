package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.DistributedCollector;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-023: per-service .exec collected from a shared volume after a drain-wait, with a downstream
 *  service's .exec arriving DURING the drain window (simulating async Tram/CDC/Kafka lag). */
class DistributedCollectIT {

    private static void writeExec(Path dir, String key, long classId, String cls) throws Exception {
        Files.createDirectories(dir);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, cls, new boolean[] { true }));
        }
    }

    @Test
    void downstreamCollectedAfterDrain(@TempDir Path tmp) throws Exception {
        Path shared = tmp.resolve("shared"), report = tmp.resolve("report");
        Path upstream = shared.resolve("reservation"), downstream = shared.resolve("ledger");
        writeExec(upstream, "T", 10L, "com/x/Reservation");                       // upstream already flushed

        TraceMapping central = traceId -> "com.x.OrderIT#placesOrder";
        // Sleeper simulates the drain window; the downstream .exec "arrives" during the wait.
        DistributedCollector.Sleeper lateArrival = millis -> writeExec(downstream, "T", 20L, "com/x/Ledger");

        new DistributedCollector().collectAfterDrain(shared, central, report, 15000, lateArrival, new Metrics());

        assertTrue(Files.exists(report.resolve("reservation/com.x.OrderIT#placesOrder.exec")));
        assertTrue(Files.exists(report.resolve("ledger/com.x.OrderIT#placesOrder.exec")),
                "downstream .exec that arrived during drain-wait is collected (REQ-023)");
    }
}
