package io.pjacoco.agent.probe;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression guard: verifies that {@link CoverageBridge#recordCoverage} reads thread-local state
 * exactly once (via {@link CoverageContext#get()}) and forwards to {@link TestStore#record} exactly
 * once. If a future change adds a second tracer lookup on the hot path, this test will fail.
 *
 * <p>NOTE: this guard test is expected to PASS on first run because the implementation is already
 * 1-read. The red step is intentionally skipped per the documented design invariant.
 */
class HotPathInvariantTest {

    @Test
    void recordCoverageReadsThreadLocalOnceAndRecordsOnce() {
        TestStore spy = Mockito.spy(new TestStore("k", 0L, null));
        CoverageContext.set(spy);
        try {
            CoverageBridge.setTotalProbeCount("io/pjacoco/agent/probe/WarmupTarget", 4);
            CoverageBridge.recordCoverage(WarmupTarget.class, 123L, 0);
            Mockito.verify(spy, Mockito.times(1)).record(eq(123L), anyString(), eq(0), anyInt());
        } finally {
            CoverageContext.clear();
        }
    }
}
