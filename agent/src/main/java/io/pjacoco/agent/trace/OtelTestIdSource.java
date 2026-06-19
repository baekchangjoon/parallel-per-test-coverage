package io.pjacoco.agent.trace;

import java.lang.reflect.Method;

public class OtelTestIdSource implements TestIdSource {

    @Override
    public String currentKey() {
        try {
            return valid() ? traceId() : null;
        } catch (Throwable t) {
            return null;   // best-effort (REQ-003)
        }
    }

    /** reflective seam: Span.current().getSpanContext().isValid() */
    protected boolean valid() throws Exception {
        Object[] pair = spanContext();
        Class<?> scIf = (Class<?>) pair[0];
        Object sc     = pair[1];
        return Boolean.TRUE.equals(scIf.getMethod("isValid").invoke(sc));
    }

    /** reflective seam: SpanContext.getTraceId() */
    protected String traceId() throws Exception {
        Object[] pair = spanContext();
        Class<?> scIf = (Class<?>) pair[0];
        Object sc     = pair[1];
        return (String) scIf.getMethod("getTraceId").invoke(sc);
    }

    /**
     * Returns [SpanContext interface Class, SpanContext instance].
     *
     * Methods are resolved from the PUBLIC API interfaces
     * (io.opentelemetry.api.trace.Span / SpanContext) rather than from the
     * concrete instance class (e.g. SdkSpan, ImmutableSpanContext, PropagatedSpan).
     * OTel's concrete implementations are non-public classes; invoking a method
     * resolved via getClass() on a non-public class from our io.pjacoco package
     * raises IllegalAccessException at invoke() time. A Method obtained from a
     * public interface is accessible regardless of the instance's concrete-class
     * visibility. (See GA-3 spike, ga-spike-results.md §GA-3.)
     */
    private Object[] spanContext() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> spanIf = Class.forName("io.opentelemetry.api.trace.Span",        false, cl);
        Class<?> scIf   = Class.forName("io.opentelemetry.api.trace.SpanContext",  false, cl);
        Object current  = spanIf.getMethod("current").invoke(null);
        Object sc       = spanIf.getMethod("getSpanContext").invoke(current);
        return new Object[]{scIf, sc};
    }
}
