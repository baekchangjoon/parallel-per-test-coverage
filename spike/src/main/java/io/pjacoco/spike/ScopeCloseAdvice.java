package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into every concrete {@code CurrentTraceContext$Scope#close()}. On entry, pairs the EXIT with
 * the ENTER recorded by {@link ScopeEnterAdvice} via the scope instance identity.
 */
public final class ScopeCloseAdvice {

    private ScopeCloseAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.This Object scope) {
        BraveScopeBridge.onExit(scope);
    }
}
