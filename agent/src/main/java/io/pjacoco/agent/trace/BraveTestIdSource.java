package io.pjacoco.agent.trace;

import java.lang.reflect.Method;

public class BraveTestIdSource implements TestIdSource {

    @Override
    public String currentKey() {
        try {
            String id = resolveTraceId();
            return (id == null || id.isEmpty()) ? null : id;
        } catch (Throwable t) {
            return null;   // best-effort (REQ-003)
        }
    }

    /**
     * Reflective seam: {@code brave.Tracing.current().currentTraceContext().get().traceIdString()}.
     *
     * <p>Each reflective {@link Method} is resolved from the PUBLIC Brave API class that
     * declares it — NOT from {@code instance.getClass()} — because Brave's concrete
     * implementations (e.g. the internal {@code Tracing} impl, the concrete
     * {@code ThreadLocalCurrentTraceContext}, and the internal {@code TraceContext} impl)
     * are non-public classes.  Invoking a {@link Method} obtained via
     * {@code nonPublicClass.getMethod(...)} from our {@code io.pjacoco} package raises
     * {@link IllegalAccessException} at {@code invoke()} time, silently swallowed by the
     * best-effort catch, making this source always return {@code null} in a live Brave
     * environment — a latent REQ-005 regression documented in the GA-3 spike
     * (ga-spike-results.md §GA-3).  A {@link Method} resolved from a {@code public} class
     * is accessible from any package regardless of the concrete instance's visibility.
     *
     * @return the trace ID string from the current Brave {@code TraceContext}, or
     *         {@code null} when no active trace context exists or Brave is absent.
     * @throws Exception propagated to {@link #currentKey()}'s best-effort catch block.
     */
    protected String resolveTraceId() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Resolve each Method from the PUBLIC declaring class (not from instance.getClass()).
        Class<?> tracingC = Class.forName("brave.Tracing", false, cl);
        Object cur = tracingC.getMethod("current").invoke(null);          // static on public brave.Tracing
        if (cur == null) return null;

        Object ctc = tracingC.getMethod("currentTraceContext").invoke(cur); // declared on public brave.Tracing
        if (ctc == null) return null;

        Class<?> ctcC = Class.forName("brave.propagation.CurrentTraceContext", false, cl);
        Object ctx = ctcC.getMethod("get").invoke(ctc);                   // declared on public CurrentTraceContext
        if (ctx == null) return null;

        Class<?> tcC = Class.forName("brave.propagation.TraceContext", false, cl);
        return (String) tcC.getMethod("traceIdString").invoke(ctx);       // declared on public TraceContext
    }
}
