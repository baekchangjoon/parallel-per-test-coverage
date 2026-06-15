package io.pjacoco.agent.inbound.servlet;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A reused worker thread must not inherit the previous request's test store. */
class ThreadReuseTest {
    @AfterEach void clear() { CoverageContext.clear(); ServletAdvice.registry = null; }

    @Test
    void contextClearedAfterRequestSoReusedThreadIsClean() {
        CoverageContext.set(new TestStore("T_PREV", 1L, null));
        ServletAdvice.deactivate();                       // exit advice of the previous request
        assertNull(CoverageContext.get(), "context must be cleared on request exit");

        ServletAdvice.activate(new Object());             // untagged request on the reused thread
        assertNull(CoverageContext.get(), "reused worker must not inherit previous test store");
    }
}
