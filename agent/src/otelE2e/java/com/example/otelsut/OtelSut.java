package com.example.otelsut;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OTel SUT main class that runs under both:
 * <ul>
 *   <li>{@code -javaagent:opentelemetry-javaagent.jar ...} — provides OTel tracing; the javaagent
 *       registers a GlobalOpenTelemetry so {@link GlobalOpenTelemetry#getTracer} works.</li>
 *   <li>{@code -javaagent:jacocoagent-parallel.jar=traceKeyAutoCreate=true,...} — instruments
 *       {@link RequestHandler} and {@link AsyncWorker}, and hooks the OTel scope lifecycle via the
 *       {@code OtelScopeInboundActivator} weave.</li>
 * </ul>
 *
 * <p>The SUT:
 * <ol>
 *   <li>Starts an OTel span and makes it current on the request thread.</li>
 *   <li>Runs {@link RequestHandler#handle} on the request thread (REQ-004).</li>
 *   <li>Wraps an executor with {@code Context.current().wrap(executor)} so OTel re-attaches the
 *       context (and re-fires the scope weave) on the worker thread.</li>
 *   <li>Runs {@link AsyncWorker#compute} on the worker thread (REQ-006).</li>
 *   <li>Closes the span and shuts down, triggering pjacoco to flush a per-trace {@code .exec}.</li>
 *   <li>Prints the trace-id to {@code stdout} so the test harness can locate the right
 *       {@code .exec} file.</li>
 * </ol>
 */
public class OtelSut {

    public static void main(String[] args) throws Exception {
        // Allow OTel javaagent to complete its initialisation (it is deferred to first use
        // in many versions — calling getTracer triggers it).
        Tracer tracer = GlobalOpenTelemetry.getTracer("io.pjacoco.otelsut", "1.0.0");

        Span span = tracer.spanBuilder("sut-root").startSpan();
        String traceId = span.getSpanContext().getTraceId();

        // Request-thread instrumented work
        RequestHandler handler = new RequestHandler();
        try (Scope scope = span.makeCurrent()) {
            handler.handle("hello");  // REQ-004: request-thread attribution

            // Async handoff: Context.current().wrap() causes OTel to re-attach the context
            // (firing attach() on the shaded storage) on the worker thread — this is exactly
            // the path that REQ-006 requires pjacoco to attribute to the same trace.
            ExecutorService exec = Executors.newSingleThreadExecutor();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Throwable> workerError = new AtomicReference<Throwable>();
            final AsyncWorker worker = new AsyncWorker();

            exec.submit(Context.current().wrap(new Runnable() {
                public void run() {
                    try {
                        worker.compute(42);  // REQ-006: async-thread attribution
                    } catch (Throwable t) {
                        workerError.set(t);
                    } finally {
                        done.countDown();
                    }
                }
            }));

            boolean workerDone = done.await(10, TimeUnit.SECONDS);
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);

            if (!workerDone) {
                System.err.println("[OtelSut] WARN: worker did not complete within 10s");
            }
            if (workerError.get() != null) {
                workerError.get().printStackTrace(System.err);
            }
        } finally {
            span.end();
        }

        // Brief pause to let pjacoco's async trace-store flush complete (it writes on span close
        // via the coverage-context clear triggered by the scope weave).
        Thread.sleep(200);

        // Print the traceId to stdout — the test harness reads it to locate the .exec file.
        // Format: TRACE_ID=<hex>
        System.out.println("TRACE_ID=" + traceId);
        System.out.flush();
    }
}
