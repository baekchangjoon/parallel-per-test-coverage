package io.pjacoco.spike;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPIKE-ONLY runtime bridge invoked from the woven OTel shaded-storage advice. Prints {@code
 * [OTEL-WEAVE attach tid=<traceId> thread=<name>]} / {@code [OTEL-WEAVE detach ...]} to stderr so the
 * spike can observe whether attach/detach fires on the request thread AND on executor handoff threads
 * carrying the same trace id.
 *
 * <p>No compile-time dependency on OTel: the shaded {@code Span.fromContext(ctx).getSpanContext()
 * .getTraceId()} chain is invoked reflectively, loading the shaded classes via the shaded {@code
 * Context} arg's own classloader (the OTel {@code AgentClassLoader}).
 *
 * <p>This class is injected into the BOOTSTRAP classloader so it is reachable from BOTH the OTel
 * AgentClassLoader-resident woven storage AND app-CL-resident woven advice (Q2).
 */
public final class OtelWeaveBridge {

    private OtelWeaveBridge() {}

    /** scope instance identity → trace id, so detach can be paired with its attach across threads. */
    private static final Map<Object, String> SCOPE_TO_TID = new ConcurrentHashMap<Object, String>();

    private static volatile boolean reportedLoaders = false;

    public static void onAttach(Object context, Object returnedScope) {
        if (context == null) {
            return;
        }
        String tid = traceId(context);
        // Skip the invalid/all-zero trace id (root context being made current).
        boolean valid = tid != null && !tid.isEmpty() && !tid.matches("0+");
        if (valid) {
            CoverageContext.set(tid);
            maybeReportLoaders(context);
            System.err.println("[OTEL-WEAVE attach tid=" + tid + " thread="
                    + Thread.currentThread().getName() + " scope=" + identity(returnedScope)
                    + " covCtx=" + CoverageContext.get() + "]");
            if (returnedScope != null) {
                SCOPE_TO_TID.put(returnedScope, tid);
            }
        }
    }

    public static void onClose(Object scope) {
        if (scope == null) {
            return;
        }
        String tid = SCOPE_TO_TID.remove(scope);
        if (tid == null) {
            return;
        }
        System.err.println("[OTEL-WEAVE detach tid=" + tid + " thread="
                + Thread.currentThread().getName() + " scope=" + identity(scope) + "]");
    }

    /**
     * Read the trace id from the shaded Context reflectively:
     * {@code Span.fromContext(ctx).getSpanContext().getTraceId()}.
     *
     * <p>The concrete {@code Span}/{@code SpanContext} impls (e.g. {@code SdkSpan}) are non-public, so
     * methods MUST be resolved from the PUBLIC shaded interfaces ({@code Span}, {@code SpanContext}) and
     * invoked through them — invoking the method object obtained from the concrete class throws
     * {@code IllegalAccessException}.
     */
    private static String traceId(Object shadedContext) {
        try {
            ClassLoader cl = shadedContext.getClass().getClassLoader();
            Class<?> spanIface = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.Span", false, cl);
            Class<?> spanContextIface = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.SpanContext", false, cl);
            Class<?> contextClass = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context", false, cl);
            Object span = spanIface.getMethod("fromContext", contextClass).invoke(null, shadedContext);
            if (span == null) {
                return null;
            }
            // Resolve on the public interface, not span.getClass() (concrete SdkSpan is non-public).
            Object spanContext = spanIface.getMethod("getSpanContext").invoke(span);
            if (spanContext == null) {
                return null;
            }
            Object tid = spanContextIface.getMethod("getTraceId").invoke(spanContext);
            return tid == null ? null : String.valueOf(tid);
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ":" + t.getMessage() + ">";
        }
    }

    /** One-time Q2 placement evidence: where do the bridge, the shared CoverageContext, and the shaded Context live? */
    private static void maybeReportLoaders(Object context) {
        if (reportedLoaders) {
            return;
        }
        reportedLoaders = true;
        ClassLoader bridgeCl = OtelWeaveBridge.class.getClassLoader();
        ClassLoader ctxCl = context.getClass().getClassLoader();
        System.err.println("[OTEL-WEAVE Q2] OtelWeaveBridge loader="
                + (bridgeCl == null ? "bootstrap" : bridgeCl.getClass().getName())
                + " CoverageContext loader=" + CoverageContext.loaderDescription()
                + " shadedContext loader=" + (ctxCl == null ? "bootstrap" : ctxCl.getClass().getName()));
    }

    private static String identity(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }
}
