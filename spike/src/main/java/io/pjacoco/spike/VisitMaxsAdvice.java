package io.pjacoco.spike;

import net.bytebuddy.asm.Advice;

/**
 * Woven into jacoco's {@code ProbeInserter.visitMaxs(int,int)}. jacoco already adds +3 for its probe
 * code; our extra {@code recordCoverage} call (Class + long + int = 4 stack slots) needs a little
 * more headroom. +2 is sufficient and matches Datadog's value for the identical call shape.
 */
public class VisitMaxsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
        maxStack = maxStack + 2;
    }
}
