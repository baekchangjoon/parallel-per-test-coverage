package io.pjacoco.agent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageControlTest {

    @AfterEach
    void unbind() {
        CoverageControl.bindRegistry(null);
        CoverageContext.clear();
    }

    private TestStoreRegistry registry(Path dir) {
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
    }

    @Test
    void unboundIsNotReadyAndActivateIsNoOp() {
        assertFalse(CoverageControl.isReady(), "no registry bound -> not ready");
        CoverageControl.activate("T1", null);   // must not throw
        assertNull(CoverageContext.get(), "activate with no registry must not set a context");
        CoverageControl.deactivate("T1", "passed"); // must not throw
    }

    @Test
    void activateSetsContextToTheRegisteredStore(@TempDir Path dir) {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        assertTrue(CoverageControl.isReady());
        CoverageControl.activate("T1", "shardA");
        assertSame(reg.peek("T1"), CoverageContext.get(), "context must be the registered store");
    }

    @Test
    void deactivateFlushesANonEmptyStore(@TempDir Path dir) {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        CoverageControl.activate("T1", null);
        TestStore store = CoverageContext.get();
        store.record(1L, "com/x/A", 0, 1);   // make it non-empty
        CoverageControl.deactivate("T1", "passed");
        assertNull(CoverageContext.get(), "deactivate clears the thread context");
        assertTrue(Files.exists(dir.resolve("T1.exec")), "non-empty store must flush an .exec");
    }

    @Test
    void deactivateDiscardsAnEmptyStoreWithoutWriting(@TempDir Path dir) throws Exception {
        TestStoreRegistry reg = registry(dir);
        CoverageControl.bindRegistry(reg);
        CoverageControl.activate("T1", null);   // nothing recorded -> empty
        CoverageControl.deactivate("T1", "passed");
        assertFalse(Files.exists(dir.resolve("T1.exec")), "empty store must NOT flush an .exec");
        assertNull(reg.peek("T1"), "empty store must be removed");
    }
}
