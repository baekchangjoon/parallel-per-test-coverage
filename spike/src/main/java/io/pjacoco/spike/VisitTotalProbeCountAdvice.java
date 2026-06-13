package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into jacoco's {@code ClassInstrumenter.visitTotalProbeCount(int)} (instrument time). Captures
 * the authoritative probe count per class so the bridge can size its boolean[] exactly as jacoco does.
 */
public class VisitTotalProbeCountAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(
            @Advice.FieldValue("className") String className,
            @Advice.Argument(0) int count) {
        CoverageBridge.setTotalProbeCount(className, count);
    }
}
