package io.pjacoco.agent.inbound.brave;

import net.bytebuddy.asm.Advice;

/**
 * Woven into every concrete implementor of {@code CurrentTraceContext$Scope#close()}.
 *
 * <p>Fires before the close method body runs, so we can pair the exit with its enter via
 * the scope object's identity. Delegates to {@link BraveScopeWeaveSupport#onExit}.
 *
 * <p>No compile-time dependency on brave: the scope is referenced as {@code Object}.
 */
public final class BraveScopeCloseAdvice {

    private BraveScopeCloseAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.This Object scope) {
        BraveScopeWeaveSupport.onExit(scope);
    }
}
