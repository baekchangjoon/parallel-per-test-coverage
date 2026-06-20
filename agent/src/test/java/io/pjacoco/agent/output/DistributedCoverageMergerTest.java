package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.mapping.TraceMapping;
import io.pjacoco.agent.observability.Metrics;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributedCoverageMergerTest {

    private static void writeExec(Path dir, String key, long classId, String cls, int probeIdx) throws Exception {
        Files.createDirectories(dir);
        boolean[] p = new boolean[] { false, false }; p[probeIdx] = true;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, cls, p));
        }
    }

    @Test
    void perServicePerTestIdReport(@TempDir Path tmp) throws Exception {            // REQ-015
        Path svcA = tmp.resolve("reservation"), svcB = tmp.resolve("ledger"), report = tmp.resolve("report");
        writeExec(svcA, "T1", 10L, "com/x/Reservation", 0);
        writeExec(svcB, "T2", 20L, "com/x/Ledger", 1);
        Map<String,Path> services = new LinkedHashMap<>();
        services.put("reservation", svcA); services.put("ledger", svcB);
        TraceMapping central = traceId -> "com.x.OrderIT#placesOrder";             // both traceIds -> one testId

        new DistributedCoverageMerger().merge(services, central, report, new Metrics());

        // service dimension preserved: each service has its own <testId>.exec
        assertTrue(Files.exists(report.resolve("reservation/com.x.OrderIT#placesOrder.exec")));
        assertTrue(Files.exists(report.resolve("ledger/com.x.OrderIT#placesOrder.exec")), "downstream service coverage present");
    }

    @Test
    void multipleTraceIdsOneTestIdWithinServiceMerged(@TempDir Path tmp) throws Exception {  // REQ-013 across service
        Path svc = tmp.resolve("svc"), report = tmp.resolve("report");
        writeExec(svc, "T1", 42L, "com/x/Svc", 0);
        writeExec(svc, "T2", 42L, "com/x/Svc", 1);                                 // same class, different probe
        Map<String,Path> services = new LinkedHashMap<>(); services.put("svc", svc);
        new DistributedCoverageMerger().merge(services, traceId -> "com.x.T#m", report, new Metrics());

        org.jacoco.core.tools.ExecFileLoader l = new org.jacoco.core.tools.ExecFileLoader();
        l.load(report.resolve("svc/com.x.T#m.exec").toFile());
        boolean[] probes = l.getExecutionDataStore().get(42L).getProbes();
        assertTrue(probes[0] && probes[1], "N:1 within a service OR-merged");
    }

    @Test
    void unmappedTraceIdFallsBackToRawPerService(@TempDir Path tmp) throws Exception {  // REQ-012 across service
        Path svc = tmp.resolve("svc"), report = tmp.resolve("report");
        writeExec(svc, "rawT", 7L, "com/x/A", 0);
        Metrics m = new Metrics();
        new DistributedCoverageMerger().merge(java.util.Collections.singletonMap("svc", svc),
                traceId -> null, report, m);
        assertTrue(Files.exists(report.resolve("svc/rawT.exec")));
        assertEquals(1L, m.unmappedTraceIds.get());
    }
}
