package io.pjacoco.agent.inbound.otel;

import net.bytebuddy.asm.Advice;

/**
 * Woven into the shaded {@code ThreadLocalContextStorage#attach(Context)} as an
 * {@code OnMethodExit} advice.
 *
 * <p>Fires after the method returns so both the {@code Context} argument and the returned
 * {@code Scope} are available. Delegates to {@link io.pjacoco.agent.trace.boot.TraceWeaveGateway}
 * which is injected into the BOOTSTRAP classloader before this advice is woven — making it
 * reachable from the OTel-agent-shaded storage class (also bootstrap-resident).
 *
 * <p>No compile-time dependency on OTel: all parameters are typed as {@code Object}.
 */
public final class OtelAttachAdvice {

    private OtelAttachAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.Argument(0) Object context,
            @Advice.Return Object scope) {
        io.pjacoco.agent.trace.boot.TraceWeaveGateway.onOtelAttach(context, scope);
    }
}
