package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestStoreRegistryEvictionTest {

    @Test
    void idleEvictedBeforeInFlight(@TempDir Path dir) {                          // REQ-018
        AtomicLong clock = new AtomicLong(0);
        Metrics metrics = new Metrics();
        // cap=2, inFlightGuard=5000
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 2, clock::get, true, /*inFlightGuardMillis*/ 5000);
        TestStore a = reg.forCoverageKey("A"); a.lastActivityMillis(0);          // idle (old)
        TestStore b = reg.forCoverageKey("B"); b.lastActivityMillis(100000);     // in-flight (recent)
        clock.set(100000);
        reg.forCoverageKey("C");                                                 // cap exceeded -> evict
        assertNull(reg.peek("A"), "idle A evicted first");
        assertNotNull(reg.peek("B"), "in-flight B protected");
        assertEquals(0L, metrics.evictedInFlightTraces.get(), "no in-flight eviction needed");
    }

    @Test
    void unavoidableInFlightEvictionIsCounted(@TempDir Path dir) {               // REQ-018 + REQ-019
        AtomicLong clock = new AtomicLong(100000);
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 1, clock::get, true, 5000);
        TestStore a = reg.forCoverageKey("A"); a.lastActivityMillis(99000);      // recent (in-flight)
        reg.forCoverageKey("B");                                                 // cap=1 -> must evict an in-flight
        assertEquals(1, reg.peek("B") != null ? 1 : 0);
        assertTrue(metrics.evictedInFlightTraces.get() >= 1, "forced in-flight eviction observed");
    }
}
