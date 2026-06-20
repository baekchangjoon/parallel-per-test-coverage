package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.*;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.store.TraceStoreReaper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REQ-016: a long-running (never-exiting) tracer-mode service flushes a finished trace's .exec via the
 *  idle reaper, with no JVM shutdown. Driven with an injected clock + explicit ticks for determinism. */
class TraceStoreLifecycleIT {
    @Test
    void idleReaperFlushWithoutJvmExit(@TempDir Path dir) throws Exception {     // matrix-matched name
        AtomicLong clock = new AtomicLong(1_000);
        Metrics metrics = new Metrics();
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), metrics, new AgentLog(),
                false, 1000, clock::get, true, 30_000);
        TraceStoreReaper reaper = new TraceStoreReaper(reg, clock::get, 30_000, 10_000);

        TestStore s = reg.forCoverageKey("4bf92f");      // a trace handled by the service
        s.record(7L, "com/x/Svc", 0, 2);
        clock.set(1_000);        reaper.tick();          // active
        // the service keeps running (no JVM exit); time passes with the trace finished/idle
        clock.set(1_000 + 30_000); reaper.tick();        // reaper flushes the idle trace
        assertTrue(Files.exists(dir.resolve("4bf92f.exec")),
                "finished trace's .exec is collectable without JVM shutdown (REQ-016)");
    }
}
