package io.pjacoco.spike;

import static net.bytebuddy.matcher.ElementMatchers.named;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Installs the (body-only) advice onto our embedded jacoco-core's internal instrumenter classes.
 * Body-only weaving adds no members, so {@code disableClassFormatChanges()} is correct here — jacoco's
 * own Instrumenter (not ByteBuddy) does the member-adding probe instrumentation.
 */
public final class JacocoProbeHook {
    private static volatile boolean installed = false;

    private JacocoProbeHook() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.jacoco.core.internal.instr.ProbeInserter"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder
                                .visit(Advice.to(VisitMaxsAdvice.class).on(named("visitMaxs")))
                                .visit(Advice.to(InsertProbeAdvice.class).on(named("insertProbe"))))
                .type(named("org.jacoco.core.internal.instr.ClassInstrumenter"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(
                                Advice.to(VisitTotalProbeCountAdvice.class)
                                        .on(named("visitTotalProbeCount"))))
                .installOn(ByteBuddyAgent.install());
        installed = true;
    }
}
