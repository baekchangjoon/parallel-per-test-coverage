package io.pjacoco.agent.store;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceStoreReaperTest {

    private static TestStoreRegistry reg(Path dir, AtomicLong clock) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                false, 1000, clock::get, /*traceKeyAutoCreate*/ true);
    }

    @Test
    void idleStoreFlushedWithoutJvmExit(@TempDir Path dir) throws Exception {     // REQ-016
        AtomicLong clock = new AtomicLong(1000);
        TestStoreRegistry reg = reg(dir, clock);
        TestStore s = reg.forCoverageKey("T");
        s.record(7L, "com/x/A", 0, 2);                  // some coverage
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get,
                /*idleFlushMillis*/ 30000, /*graceMillis*/ 10000);

        clock.set(1000);  reaper.tick();                // first observation -> active, lastActivity=1000
        assertFalse(Files.exists(dir.resolve("T.exec")), "not idle yet");
        clock.set(1000 + 30000); reaper.tick();         // idle >= threshold -> flush (kept for grace)
        assertTrue(Files.exists(dir.resolve("T.exec")), "idle store flushed without JVM exit");
        assertNotNull(reg.peek("T"), "kept during grace");
    }

    @Test
    void idleStoreEvictedAfterGrace(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(0);
        TestStoreRegistry reg = reg(dir, clock);
        TestStore s = reg.forCoverageKey("T");
        s.record(7L, "com/x/A", 0, 2);
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get, 30000, 10000);
        clock.set(0);     reaper.tick();                 // first observation -> active
        clock.set(30000); reaper.tick();                 // idle -> flush, start grace
        clock.set(30000 + 10000); reaper.tick();         // grace elapsed, no new writes -> reflush + evict
        assertNull(reg.peek("T"), "evicted after grace");
        assertTrue(Files.exists(dir.resolve("T.exec")), "flushed artifact remains on disk");
    }
}
