package io.pjacoco.spike.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;

/**
 * Spike {@link ContextStorage} that delegates to the agent-supplied default storage and logs every
 * attach (context becoming current) and the corresponding detach (scope close), printing the
 * {@code traceId} read from {@code Span.fromContext(context).getSpanContext()}.
 *
 * <p>If the trace context propagates correctly, the same {@code traceId} appears on the request
 * thread AND on the executor/@Async handoff thread — that is the PASS signal for GA-1 (OTel).
 */
final class DelegatingContextStorage implements ContextStorage {

    private final ContextStorage delegate;

    DelegatingContextStorage(ContextStorage delegate) {
        this.delegate = delegate;
        System.err.println("[OTEL-SPIKE] DelegatingContextStorage constructed, delegate="
                + delegate.getClass().getName());
    }

    @Override
    public Scope attach(Context toAttach) {
        String tid = traceId(toAttach);
        String thread = Thread.currentThread().getName();
        System.err.println("[OTEL-SPIKE attach tid=" + tid + " thread=" + thread + "]");
        final Scope inner = delegate.attach(toAttach);
        return () -> {
            System.err.println("[OTEL-SPIKE detach tid=" + tid + " thread=" + Thread.currentThread().getName() + "]");
            inner.close();
        };
    }

    @Override
    public Context current() {
        return delegate.current();
    }

    private static String traceId(Context ctx) {
        if (ctx == null) {
            return "<null-ctx>";
        }
        SpanContext sc = Span.fromContext(ctx).getSpanContext();
        return sc.isValid() ? sc.getTraceId() : "<invalid>";
    }
}
