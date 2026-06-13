package io.pjacoco.agent.context;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pjacoco.agent.store.TestStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoverageContextTest {
    @AfterEach void clear() { CoverageContext.clear(); }

    @Test
    void unsetByDefault() {
        assertNull(CoverageContext.get());
    }

    @Test
    void setAndClear() {
        TestStore s = new TestStore("T1", 1L, null);
        CoverageContext.set(s);
        assertSame(s, CoverageContext.get());
        CoverageContext.clear();
        assertNull(CoverageContext.get());
    }

    @Test
    void isolatedPerThread() throws Exception {
        TestStore s = new TestStore("main-thread", 1L, null);
        CoverageContext.set(s);
        final TestStore[] seen = new TestStore[1];
        Thread t = new Thread(new Runnable() {
            public void run() { seen[0] = CoverageContext.get(); }
        });
        t.start();
        t.join();
        assertNull(seen[0]);                       // child thread has no context (v1: no async propagation)
        assertSame(s, CoverageContext.get());
    }
}
