package io.pjacoco.agent.inbound.otel;

import net.bytebuddy.asm.Advice;

/**
 * Woven into the shaded {@code ThreadLocalContextStorage$ScopeImpl#close()} as an
 * {@code OnMethodEnter} advice.
 *
 * <p>Fires before the close method body runs so the scope-instance identity is still valid and can
 * be paired with the corresponding enter recorded by {@link OtelAttachAdvice}. Delegates to
 * {@link io.pjacoco.agent.trace.boot.TraceWeaveGateway} which is on the BOOTSTRAP classloader.
 *
 * <p>No compile-time dependency on OTel: the scope is typed as {@code Object}.
 */
public final class OtelCloseAdvice {

    private OtelCloseAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.This Object scope) {
        io.pjacoco.agent.trace.boot.TraceWeaveGateway.onOtelClose(scope);
    }
}
