package io.pjacoco.agent.probe;

import net.bytebuddy.asm.Advice;

/**
 * Woven into jacoco's {@code ProbeInserter.visitMaxs(int,int)}. jacoco already adds +3 for its probe;
 * our extra {@code recordCoverage} call (Class + long + int) needs a little more headroom. Verbatim from spike/.
 */
public class VisitMaxsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
        maxStack = maxStack + 2;
    }
}
