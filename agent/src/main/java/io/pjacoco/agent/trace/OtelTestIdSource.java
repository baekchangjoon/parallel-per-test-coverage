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
        Object sc = spanContext();
        Method isValid = sc.getClass().getMethod("isValid");
        return Boolean.TRUE.equals(isValid.invoke(sc));
    }

    /** reflective seam: SpanContext.getTraceId() */
    protected String traceId() throws Exception {
        Object sc = spanContext();
        Method getTraceId = sc.getClass().getMethod("getTraceId");
        return (String) getTraceId.invoke(sc);
    }

    private Object spanContext() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> span = Class.forName("io.opentelemetry.api.trace.Span", false, cl);
        Object current = span.getMethod("current").invoke(null);
        return current.getClass().getMethod("getSpanContext").invoke(current);
    }
}
