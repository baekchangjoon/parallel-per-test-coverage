package io.pjacoco.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceScopeBridgeTest {

    @AfterEach
    void clearContext() {
        CoverageContext.clear();
    }

    private TestStoreRegistry newRegistry(Path dir) {
        final AtomicLong clock = new AtomicLong(1000L);
        return new TestStoreRegistry(dir, new ExecWriter(), new Metrics(), new AgentLog(),
                /*lenient=*/ false, /*maxStores=*/ 100,
                new java.util.function.LongSupplier() {
                    public long getAsLong() { return clock.get(); }
                },
                /*traceKeyAutoCreate=*/ true);
    }

    private TraceScopeBridge newBridge(TestStoreRegistry registry) {
        CoverageKeyResolver resolver =
                new CoverageKeyResolver(Collections.<TestIdSource>emptyList());
        return new TraceScopeBridge(registry, resolver);
    }

    // -----------------------------------------------------------------------
    // REQ-009: closing a scope on a foreign thread must NOT corrupt that
    // thread's current CoverageContext binding.
    // -----------------------------------------------------------------------

    @Test
    void closeOnOtherThreadDoesNotCorrupt(@TempDir Path dir) throws Exception {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        // Main thread enters k1
        TraceScope a = bridge.enter("k1");
        assertEquals("k1", CoverageContext.get().testId());

        // Latches to synchronize the two halves of the cross-thread test
        CountDownLatch readyLatch = new CountDownLatch(1);   // thread B signals it entered k2
        CountDownLatch doneLatch  = new CountDownLatch(1);   // thread B signals it finished

        AtomicReference<String> midThreadId  = new AtomicReference<String>();
        AtomicReference<String> finalThreadId = new AtomicReference<String>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread threadB = new Thread(new Runnable() {
            public void run() {
                try {
                    // B enters its own scope
                    TraceScope b2 = bridge.enter("k2");
                    midThreadId.set(
                            CoverageContext.get() == null ? null : CoverageContext.get().testId());
                    readyLatch.countDown();

                    // B tries to exit scope 'a' (which was entered on the main thread).
                    // ownerThread guard: this must be a no-op on thread B.
                    bridge.exit(a);

                    // After the attempted foreign-thread exit, B must still see k2
                    finalThreadId.set(
                            CoverageContext.get() == null ? null : CoverageContext.get().testId());

                    bridge.exit(b2);
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    CoverageContext.clear();
                    doneLatch.countDown();
                }
            }
        });

        threadB.start();
        readyLatch.await();   // wait until B has entered k2
        doneLatch.await();    // wait until B has finished

        if (error.get() != null) throw new AssertionError("thread B threw", error.get());

        // Thread B saw k2 after entering its scope
        assertEquals("k2", midThreadId.get(), "thread B must see k2 after its own enter");
        // Thread B still saw k2 after the foreign exit(a) — the guard preserved k2
        assertEquals("k2", finalThreadId.get(),
                "exit(a) on foreign thread must not corrupt thread B's k2 binding");

        // Main thread restores its own binding
        bridge.exit(a);
        assertNull(CoverageContext.get(), "main thread must be cleared after exit(a)");
    }

    // -----------------------------------------------------------------------
    // Same-thread enter/exit restores previous context
    // -----------------------------------------------------------------------

    @Test
    void sameThreadEnterExitRestoresPrevious(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        assertNull(CoverageContext.get());

        TraceScope scope = bridge.enter("k1");
        assertEquals("k1", CoverageContext.get().testId());

        bridge.exit(scope);
        assertNull(CoverageContext.get(), "context must be cleared when previous was null");
    }

    // -----------------------------------------------------------------------
    // Nested same-thread enter/exit restores intermediate and then clears
    // -----------------------------------------------------------------------

    @Test
    void nestedSameThreadEnterExitRestoresIntermediate(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        TraceScope scope1 = bridge.enter("k1");
        assertEquals("k1", CoverageContext.get().testId());

        TraceScope scope2 = bridge.enter("k2");
        assertEquals("k2", CoverageContext.get().testId());

        bridge.exit(scope2);
        assertEquals("k1", CoverageContext.get().testId(),
                "after exit(scope2) should be back to k1");

        bridge.exit(scope1);
        assertNull(CoverageContext.get(), "after exit(scope1) should be cleared");
    }

    // -----------------------------------------------------------------------
    // Scope-identity pairing: onScopeEnter / onScopeExit
    // -----------------------------------------------------------------------

    @Test
    void scopeIdentityPairingBindsAndRestores(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        Object scopeObj = new Object();

        bridge.onScopeEnter("k1", scopeObj);
        assertEquals("k1", CoverageContext.get().testId());

        bridge.onScopeExit(scopeObj);
        assertNull(CoverageContext.get(), "context must be cleared after onScopeExit");
    }

    @Test
    void scopeIdentityExitWithUnknownKeyIsNoOp(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        // Set a context externally
        TestStore external = registry.forCoverageKey("external");
        CoverageContext.set(external);

        // Exit with an unknown scope object — must not touch the context
        bridge.onScopeExit(new Object());
        assertSame(external, CoverageContext.get(),
                "onScopeExit with unknown scopeId must be a no-op");
    }

    @Test
    void scopeEnterWithNullScopeIdStillEnters(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        // null scopeId: enter still activates coverage key but the scope cannot be paired
        bridge.onScopeEnter("k1", null);
        assertEquals("k1", CoverageContext.get().testId());
    }

    // -----------------------------------------------------------------------
    // key==null enter is a harmless no-op
    // -----------------------------------------------------------------------

    /**
     * Verifies the semantics: enter(null) does NOT change context; exit restores the previous binding.
     */
    @Test
    void nullKeyEnterExitRestoresPrevious(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        // Set a pre-existing context
        TestStore pre = registry.forCoverageKey("pre");
        CoverageContext.set(pre);

        // enter(null): no store lookup → context unchanged, but previous is captured
        TraceScope scope = bridge.enter(null);
        assertSame(pre, CoverageContext.get(), "enter(null) must not change context");

        // exit: restores previous (which was 'pre')
        bridge.exit(scope);
        assertSame(pre, CoverageContext.get(),
                "exit after enter(null) restores the previous context ('pre')");
    }

    @Test
    void nullKeyEnterFromCleanSlateExitClearsContext(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        // No context set: enter(null) → no-op; previous==null
        assertNull(CoverageContext.get());
        TraceScope scope = bridge.enter(null);
        assertNull(CoverageContext.get(), "enter(null) must not set context");

        // exit: previous was null → clears
        bridge.exit(scope);
        assertNull(CoverageContext.get(), "exit after null-key enter with no previous must leave context null");
    }

    // -----------------------------------------------------------------------
    // exit(null) is a harmless no-op
    // -----------------------------------------------------------------------

    @Test
    void exitNullScopeIsNoOp(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        TraceScopeBridge bridge = newBridge(registry);

        TestStore store = registry.forCoverageKey("k");
        CoverageContext.set(store);

        bridge.exit(null);  // must not throw, must not touch context
        assertSame(store, CoverageContext.get(), "exit(null) must not touch context");
    }

    // -----------------------------------------------------------------------
    // REQ-003: enterResolved() must never throw even when resolver.resolve() throws
    // -----------------------------------------------------------------------

    /**
     * Verifies the no-throw contract of enterResolved() with a throwing TestIdSource.
     *
     * NOTE ON COVERAGE PATH: CoverageKeyResolver is final and cannot be subclassed, so we
     * cannot make resolver.resolve() itself throw from a test. Instead, we use a throwing
     * TestIdSource — CoverageKeyResolver.resolve() already catches that internally (skips it
     * and returns null), so the NEW catch block in enterResolved() is not directly exercised
     * by this test. What IS verified: the full no-throw contract — enterResolved() returns a
     * non-null TraceScope and exit() is a harmless no-op — which is the observable guarantee
     * of REQ-003. The new catch block protects against a hypothetical future change to
     * CoverageKeyResolver or resolve() being called differently; the fix is structurally
     * correct and the contract is fully pinned here.
     */
    @Test
    void enterResolvedDoesNotThrowWhenSourceThrows(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);

        // Source whose currentKey() throws — resolver catches it internally and returns null.
        // enterResolved() then calls enter(null), which is a no-op enter returning a valid handle.
        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Collections.<TestIdSource>singletonList(new TestIdSource() {
                    public String currentKey() { throw new RuntimeException("source boom"); }
                }));
        TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);

        // Capture context before — null on a clean thread
        TestStore before = CoverageContext.get();

        // Must not throw; must return a non-null scope
        TraceScope scope = bridge.enterResolved();
        assertNotNull(scope, "enterResolved() must return non-null scope even when source throws");

        // paired exit must also not throw and must leave context as it was
        bridge.exit(scope);
        assertSame(before, CoverageContext.get(),
                "context after exit must equal context before enterResolved");
    }

    // -----------------------------------------------------------------------
    // enterResolved() delegates to resolver.resolve()
    // -----------------------------------------------------------------------

    @Test
    void enterResolvedUsesResolver(@TempDir Path dir) {
        TestStoreRegistry registry = newRegistry(dir);
        // A resolver that always returns "resolved-key"
        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Collections.<TestIdSource>singletonList(new TestIdSource() {
                    public String currentKey() { return "resolved-key"; }
                }));
        TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);

        TraceScope scope = bridge.enterResolved();
        assertEquals("resolved-key", CoverageContext.get().testId());

        bridge.exit(scope);
        assertNull(CoverageContext.get());
    }
}
