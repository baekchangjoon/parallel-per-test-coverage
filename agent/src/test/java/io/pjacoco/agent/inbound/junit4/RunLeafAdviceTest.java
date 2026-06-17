package io.pjacoco.agent.inbound.junit4;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.api.CoverageControl;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLeafAdviceTest {

    /** A stand-in for org.junit.runner.Description (read reflectively by the advice). */
    public static final class FakeDescription {
        private final String className, methodName;
        FakeDescription(String c, String m) { this.className = c; this.methodName = m; }
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
    }

    @AfterEach
    void cleanup() {
        CoverageControl.bindRegistry(null);
        CoverageContext.clear();
    }

    @Test
    void activateBracketsTestByFqnTestId(@TempDir Path dir) {
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
        CoverageControl.bindRegistry(reg);

        RunLeafAdvice.activate(new FakeDescription("com.x.MyTest", "doesThing"));
        assertSame(reg.peek("com.x.MyTest#doesThing"), CoverageContext.get(),
                "advice must activate the FQN#method testId on this thread");
    }

    @Test
    void deactivateFlushesWithResultUnknown(@TempDir Path dir) {
        TestStoreRegistry reg = new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(), false, 1000, () -> 1L);
        CoverageControl.bindRegistry(reg);
        FakeDescription d = new FakeDescription("com.x.MyTest", "doesThing");
        RunLeafAdvice.activate(d);
        CoverageContext.get().record(1L, "com/x/A", 0, 1);   // non-empty
        RunLeafAdvice.deactivate(d);
        assertNull(CoverageContext.get(), "deactivate clears the context");
        assertTrue(Files.exists(dir.resolve("com.x.MyTest#doesThing.exec")), "must flush the per-test .exec");
        assertTrue(Files.exists(dir.resolve("com.x.MyTest#doesThing.json")), "must flush the sidecar");
    }

    @Test
    void nullDescriptionIsANoOp() {
        RunLeafAdvice.activate(null);   // must not throw
        RunLeafAdvice.deactivate(null);
        assertNull(CoverageContext.get());
    }
}
