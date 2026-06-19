package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code CurrentTraceContext#newScope(TraceContext)} and {@code #maybeScope(TraceContext)}.
 * On method exit (so we have the returned Scope), records ENTER for {@code context.traceIdString()}
 * and registers the returned Scope identity → traceId so {@link ScopeCloseAdvice} can pair the EXIT.
 *
 * <p>References everything as {@code Object}: no compile-time dependency on brave.
 */
public final class ScopeEnterAdvice {

    private ScopeEnterAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.Argument(0) Object traceContext,
            @Advice.Return Object returnedScope) {
        BraveScopeBridge.onEnter(traceContext, returnedScope);
    }
}
