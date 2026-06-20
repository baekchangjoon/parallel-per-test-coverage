package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceMergeMainTest {

    /** Write a minimal .exec into dir/key.exec */
    private static void writeExec(Path dir, String key, long classId, String cls) throws Exception {
        Files.createDirectories(dir);
        boolean[] p = {true, false};
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(dir.resolve(key + ".exec")))) {
            ExecutionDataWriter w = new ExecutionDataWriter(os);
            w.visitSessionInfo(new SessionInfo(key, 0L, 0L));
            w.visitClassExecution(new ExecutionData(classId, cls, p));
        }
    }

    /** Write a trace-map.properties file mapping traceId -> testId */
    private static Path writeMapFile(Path dir, String traceId, String testId) throws Exception {
        Files.createDirectories(dir);
        Properties p = new Properties();
        p.setProperty(traceId, testId);
        Path f = dir.resolve("trace-map.properties");
        try (OutputStream os = Files.newOutputStream(f)) {
            p.store(os, null);
        }
        return f;
    }

    @Test
    void runSharedMode(@TempDir Path tmp) throws Exception {
        // Arrange: shared/svc/<traceId>.exec  +  trace-map.properties
        Path shared = tmp.resolve("shared");
        Path svcDir = shared.resolve("svc");
        writeExec(svcDir, "traceXYZ", 1L, "com/x/T");
        Path mapFile = writeMapFile(tmp, "traceXYZ", "com.x.T#m");
        Path report = tmp.resolve("report");

        int code = TraceMergeMain.run(new String[]{
            "--shared", shared.toString(),
            "--map", mapFile.toString(),
            "--report", report.toString(),
            "--drain-wait-ms", "0"   // 0 for determinism (no real sleep)
        });

        assertEquals(0, code);
        assertTrue(Files.exists(report.resolve("svc/com.x.T#m.exec")),
            "shared mode must produce report/svc/<testId>.exec");
    }

    @Test
    void runServiceDirMode(@TempDir Path tmp) throws Exception {
        // Arrange: explicit service dir with .exec files
        Path svcDir = tmp.resolve("reservation");
        writeExec(svcDir, "traceABC", 2L, "com/x/Svc");
        Path mapFile = writeMapFile(tmp, "traceABC", "com.x.T#m");
        Path report = tmp.resolve("report");

        int code = TraceMergeMain.run(new String[]{
            "--service-dir", "reservation=" + svcDir.toString(),
            "--map", mapFile.toString(),
            "--report", report.toString()
        });

        assertEquals(0, code);
        assertTrue(Files.exists(report.resolve("reservation/com.x.T#m.exec")),
            "service-dir mode must produce report/<serviceName>/<testId>.exec");
    }

    @Test
    void runRejectsNoInputMode(@TempDir Path tmp) {
        // Neither --shared nor --service-dir → must return 2 (usage error)
        assertEquals(2, TraceMergeMain.run(new String[]{ "--map", "x", "--report", "y" }));
    }
}
