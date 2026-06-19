package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.brave.BraveScopeInboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.output.ExecWriter;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

/**
 * In-process integration test for the Brave scope weave (REQ-005, REQ-006).
 *
 * <p>Proves that {@link BraveScopeInboundActivator} correctly weaves
 * {@code CurrentTraceContext#newScope} / {@code maybeScope} / {@code Scope#close()} so that
 * {@link CoverageContext} tracks the Brave trace id both synchronously (REQ-005) and on async
 * handoff threads where Brave re-enters the scope (REQ-006).
 */
class BraveScopeWeaveIT {

    @Test
    void braveScope_sync_and_async_drivesCoverageContext() throws Exception {
        // --- setup ---
        Instrumentation inst = ByteBuddyAgent.install();

        Path tmpDir = Files.createTempDirectory("brave-weave-it");
        TestStoreRegistry registry = new TestStoreRegistry(
                tmpDir,
                new ExecWriter(),
                new Metrics(),
                new AgentLog(),
                /*lenient=*/ false,
                /*maxStores=*/ 100,
                new java.util.function.LongSupplier() {
                    public long getAsLong() { return System.currentTimeMillis(); }
                },
                /*traceKeyAutoCreate=*/ true);

        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Collections.<io.pjacoco.agent.trace.TestIdSource>emptyList());
        TraceScopeBridge bridge = new TraceScopeBridge(registry, resolver);

        // Install the Brave scope weave BEFORE creating the Tracing instance so brave's
        // ThreadLocalCurrentTraceContext (and its inner Scope impl) are woven at first load.
        new BraveScopeInboundActivator(bridge).install(inst);

        // Build a real Brave Tracing with ThreadLocalCurrentTraceContext.
        CurrentTraceContext ctc = ThreadLocalCurrentTraceContext.create();
        Tracing tracing = Tracing.newBuilder().currentTraceContext(ctc).build();

        // Create a span with a known trace context.
        brave.Span span = tracing.tracer().nextSpan().name("test-span").start();
        TraceContext context = span.context();
        String expectedTraceId = context.traceIdString();
        assertNotNull(expectedTraceId, "traceIdString must not be null");

        try {
            // ---------------------------------------------------------------
            // REQ-005: SYNC assertion — scope enter sets CoverageContext,
            //          scope close restores it.
            // ---------------------------------------------------------------
            CoverageContext.clear();
            assertNull(CoverageContext.get(), "CoverageContext must be null before scope");

            CurrentTraceContext.Scope scope = ctc.newScope(context);
            try {
                assertNotNull(CoverageContext.get(),
                        "CoverageContext must be non-null inside Brave scope (REQ-005)");
                assertEquals(expectedTraceId, CoverageContext.get().testId(),
                        "CoverageContext.testId() must equal Brave traceIdString (REQ-005)");
            } finally {
                scope.close();
            }

            assertNull(CoverageContext.get(),
                    "CoverageContext must be null after scope close (REQ-005)");

            // ---------------------------------------------------------------
            // REQ-006: ASYNC assertion — Brave re-enters the scope on a worker
            //          thread; CoverageContext on that thread should carry the
            //          same trace id.
            // ---------------------------------------------------------------
            ExecutorService realExecutor = Executors.newSingleThreadExecutor();
            // Brave's ctc.executor() wraps the executor so it re-enters the scope on the worker.
            // Returns Executor (not ExecutorService) in Brave 5.
            Executor wrappedExecutor = ctc.executor(realExecutor);

            AtomicReference<String> workerTraceId = new AtomicReference<String>();
            CountDownLatch latch = new CountDownLatch(1);

            // Open a scope on the current (main) thread and execute work inside it.
            CurrentTraceContext.Scope asyncScope = ctc.newScope(context);
            try {
                wrappedExecutor.execute(new Runnable() {
                    public void run() {
                        // On the worker thread, Brave re-enters the scope → woven advice fires.
                        io.pjacoco.agent.store.TestStore workerStore = CoverageContext.get();
                        workerTraceId.set(workerStore != null ? workerStore.testId() : null);
                        latch.countDown();
                    }
                });
            } finally {
                asyncScope.close();
            }

            boolean done = latch.await(5, TimeUnit.SECONDS);
            realExecutor.shutdown();
            realExecutor.awaitTermination(5, TimeUnit.SECONDS);

            assert done : "Worker thread did not complete within 5 seconds";

            System.out.println("[BraveScopeWeaveIT] worker-thread traceId observed: " + workerTraceId.get());
            assertNotNull(workerTraceId.get(),
                    "CoverageContext on async worker thread must be non-null (REQ-006)");
            assertEquals(expectedTraceId, workerTraceId.get(),
                    "CoverageContext.testId() on worker thread must equal Brave traceIdString (REQ-006)");

        } finally {
            span.finish();
            tracing.close();
            CoverageContext.clear();
        }
    }
}
