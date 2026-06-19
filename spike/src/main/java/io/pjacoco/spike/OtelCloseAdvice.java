package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into the shaded {@code ThreadLocalContextStorage$ScopeImpl#close()}. On entry, pairs the EXIT
 * with the ENTER recorded by {@link OtelAttachAdvice} via the scope-instance identity.
 */
public final class OtelCloseAdvice {

    private OtelCloseAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.This Object scope) {
        io.pjacoco.spike.OtelWeaveBridge.onClose(scope);
    }
}
