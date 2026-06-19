package io.pjacoco.agent.inbound.brave;

import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code CurrentTraceContext#newScope(TraceContext)} and
 * {@code CurrentTraceContext#maybeScope(TraceContext)} as an {@code OnMethodExit} advice.
 *
 * <p>Fires after the method returns, so both the trace context argument and the returned scope
 * are available. Delegates to {@link BraveScopeWeaveSupport#onEnter} which drives
 * {@link io.pjacoco.agent.trace.TraceScopeBridge}.
 *
 * <p>No compile-time dependency on brave: all types are referenced as {@code Object}.
 */
public final class BraveScopeEnterAdvice {

    private BraveScopeEnterAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.Argument(0) Object traceContext,
            @Advice.Return Object scope) {
        BraveScopeWeaveSupport.onEnter(traceContext, scope);
    }
}
