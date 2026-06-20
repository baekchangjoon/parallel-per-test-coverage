package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.mapping.TestIdMappingRegistry;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.output.TraceCoverageMerger;
import io.pjacoco.agent.probe.CoverageBridge;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-012: a tracer-mode store keyed by a raw traceId, when reported with no mapping, keeps the
 * raw traceId as the testId. Drives the real registry->ExecWriter pipeline to produce the input
 * .exec, then runs TraceCoverageMerger with an empty mapping.
 */
@DisplayName("REQ-012: unmapped traceId reported as raw testId, end-to-end via real .exec")
class UnmappedTraceReportIT {

    @AfterEach
    void cleanup() {
        // CoverageControl.registry is static volatile — parity with CoverageControlTest
        CoverageControl.bindRegistry(null);
        CoverageContext.clear();
    }

    @Test
    @DisplayName("REQ-012: rawTraceId kept as testId when merge mapping has no entry for it")
    void rawTraceIdAsTestId(@TempDir Path tmp) throws Exception {
        Path raw = tmp.resolve("raw");
        Path merged = tmp.resolve("merged");
        Metrics metrics = new Metrics();

        // Build a real TestStoreRegistry with traceKeyAutoCreate=true (tracer mode).
        TestStoreRegistry reg = new TestStoreRegistry(
                raw, new ExecWriter(), metrics, new AgentLog(),
                /* lenient= */ false, /* maxStores= */ 1000,
                System::currentTimeMillis,
                /* traceKeyAutoCreate= */ true);

        CoverageBridge.bindMetrics(metrics);
        CoverageControl.bindRegistry(reg);

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        // Tracer-mode store auto-created for the raw traceId key; record some coverage, flush to .exec.
        CoverageControl.activate(traceId, null);
        CoverageBridge.setTotalProbeCount("com/x/Svc", 2);
        // Use java.lang.String as the probe carrier — we only assert the key/file contract.
        CoverageBridge.recordCoverage(String.class, strClassId(), 0);
        CoverageControl.deactivate(traceId, "passed");   // -> writes <traceId>.exec

        // Invariant 1: the real pipeline produced a per-traceId .exec file.
        assertTrue(Files.exists(raw.resolve(traceId + ".exec")),
                "agent produced raw-traceId .exec (REQ-012)");

        // Merge with an EMPTY mapping (absent file -> always-null mapping).
        new TraceCoverageMerger().merge(
                raw,
                TestIdMappingRegistry.loadFrom(tmp.resolve("absent.properties")),
                merged,
                metrics);

        // Invariant 2a: raw traceId passes through as testId -> merged/<traceId>.exec exists.
        assertTrue(Files.exists(merged.resolve(traceId + ".exec")),
                "raw traceId is preserved as testId in merged output (REQ-012)");

        // Invariant 2b: the unmapped counter was incremented exactly once.
        assertEquals(1L, metrics.unmappedTraceIds.get(),
                "unmappedTraceIds counter must be 1 (REQ-012)");
    }

    /**
     * classId of java.lang.String under the agent's hashing — any stable non-instrumented class
     * works as a probe carrier; we only assert the key/file contract, not probe semantics.
     */
    private static long strClassId() {
        return (long) "java/lang/String".hashCode();
    }
}
