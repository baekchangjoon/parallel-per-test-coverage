package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into the shaded {@code ThreadLocalContextStorage#attach(Context)}. On method exit (so the
 * returned {@code Scope} is available), records ENTER for the trace id read reflectively from the
 * shaded {@code Context} arg and registers the returned {@code Scope} identity → traceId so {@link
 * OtelCloseAdvice} can pair the EXIT.
 *
 * <p>Everything is {@code Object}-typed: no compile-time dependency on OTel. The advice body is
 * inlined into the shaded storage class (resident in the OTel {@code AgentClassLoader}); it calls
 * {@code io.pjacoco.spike.OtelWeaveBridge}, which is injected into the BOOTSTRAP classloader so it is
 * reachable from {@code AgentClassLoader} (whose parent chain reaches bootstrap but NOT the system CL
 * where pjacoco's agent jar lives).
 */
public final class OtelAttachAdvice {

    private OtelAttachAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(
            @Advice.Argument(0) Object context,
            @Advice.Return Object returnedScope) {
        io.pjacoco.spike.OtelWeaveBridge.onAttach(context, returnedScope);
    }
}
