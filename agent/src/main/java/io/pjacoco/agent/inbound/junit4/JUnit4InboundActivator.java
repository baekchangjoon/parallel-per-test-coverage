package io.pjacoco.agent.inbound.junit4;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.pjacoco.agent.inbound.InboundActivator;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Installs the JUnit 4 zero-touch path: body-only advice on
 * {@code org.junit.runners.ParentRunner.runLeaf(Statement, Description, RunNotifier)}. In an
 * out-of-process app JVM {@code ParentRunner} is never loaded, so the advice simply never matches.
 * Reuses the {@code ServletInboundActivator} pattern. Gated by {@code junit4Auto} in {@code Bootstrap}.
 */
public final class JUnit4InboundActivator implements InboundActivator {

    @Override
    public void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.junit.runners.ParentRunner"))
                .transform((builder, type, classLoader, module, pd) -> builder.visit(
                        Advice.to(RunLeafAdvice.class).on(named("runLeaf").and(takesArguments(3)))))
                .installOn(inst);
    }
}
