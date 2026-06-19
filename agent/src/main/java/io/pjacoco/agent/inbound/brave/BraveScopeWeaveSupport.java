package io.pjacoco.agent.inbound.brave;

import io.pjacoco.agent.trace.TraceScopeBridge;

/**
 * Static support class called from the inlined Brave scope advice.
 *
 * <p>Keeping the support class separate from the advice classes keeps the inlined advice bodies
 * small (a single static call each) and makes testing straightforward. The {@link #bridge} field is
 * set once at activator construction time and never reset.
 *
 * <p>All methods are best-effort — they swallow every {@link Throwable} so that a bug here can
 * never propagate into the application (REQ-003).
 *
 * <p>No compile-time dependency on brave: {@code TraceContext} is accessed reflectively via the
 * public {@code brave.propagation.TraceContext} class (not the concrete impl class) to avoid
 * {@link IllegalAccessException} on non-public subclasses — same lesson as {@code BraveTestIdSource}.
 */
public final class BraveScopeWeaveSupport {

    /** Bound once by {@link BraveScopeInboundActivator}; read by the woven advice. */
    public static volatile TraceScopeBridge bridge;

    private BraveScopeWeaveSupport() {}

    /**
     * Called from {@link BraveScopeEnterAdvice} on exit of {@code CurrentTraceContext#newScope} and
     * {@code #maybeScope}.
     *
     * @param traceContext the {@code TraceContext} argument (may be {@code null} for
     *                     {@code maybeScope(null)} → NOOP)
     * @param scope        the scope returned by the intercepted method
     */
    public static void onEnter(Object traceContext, Object scope) {
        try {
            if (traceContext == null) {
                // maybeScope(null) → NOOP scope; nothing meaningful to attribute.
                return;
            }
            String tid = resolveTraceId(traceContext);
            if (tid == null || tid.isEmpty()) {
                return;
            }
            TraceScopeBridge b = bridge;
            if (b == null) {
                return;
            }
            b.onScopeEnter(tid, scope);
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    /**
     * Called from {@link BraveScopeCloseAdvice} on entry of {@code Scope#close()}.
     *
     * @param scope the scope instance being closed
     */
    public static void onExit(Object scope) {
        try {
            TraceScopeBridge b = bridge;
            if (b != null) {
                b.onScopeExit(scope);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    /**
     * Resolves {@code traceIdString()} from the PUBLIC {@code brave.propagation.TraceContext} class
     * (not from {@code traceContext.getClass()}) to avoid {@link IllegalAccessException} on
     * package-private or inner-class implementations.
     */
    private static String resolveTraceId(Object traceContext) {
        try {
            ClassLoader cl = traceContext.getClass().getClassLoader();
            // Resolve from the public API class, not the concrete impl class.
            Class<?> tcClass = Class.forName("brave.propagation.TraceContext", false, cl);
            Object result = tcClass.getMethod("traceIdString").invoke(traceContext);
            return (result instanceof String) ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
