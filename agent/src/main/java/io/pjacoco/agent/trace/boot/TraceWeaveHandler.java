package io.pjacoco.agent.trace.boot;

/**
 * Minimal bootstrap-safe callback interface that the OTel scope weave invokes.
 *
 * <p>This interface (together with {@link TraceWeaveGateway}) is injected into the BOOTSTRAP
 * classloader so that advice woven into the OTel-agent-shaded {@code ThreadLocalContextStorage}
 * (which is itself bootstrap-resident) can dispatch upward into the system-CL bridge without a
 * classloader visibility problem.
 *
 * <p><strong>MUST depend on nothing outside {@code java.*}</strong> — any extra import would
 * prevent bootstrap injection.
 */
public interface TraceWeaveHandler {

    /**
     * Called when the OTel shaded storage makes a context current (i.e. {@code attach(Context)}
     * returned a live {@code Scope}).
     *
     * @param traceId the trace-id string extracted from the shaded {@code Context}; never
     *                {@code null} or all-zero (callers must pre-filter)
     * @param scope   the {@code Scope} object whose identity is used to pair this enter with the
     *                matching {@link #onScopeExit} call
     */
    void onScopeEnter(String traceId, Object scope);

    /**
     * Called when the OTel shaded scope is about to close.
     *
     * @param scope the same {@code Scope} object that was passed to {@link #onScopeEnter}
     */
    void onScopeExit(Object scope);
}
