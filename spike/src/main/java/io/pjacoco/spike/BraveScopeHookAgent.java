package io.pjacoco.spike;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * SPIKE-ONLY java-agent for GA-1 (Brave backend).
 *
 * <p>Central question: Sleuth 3.1.9 builds {@code brave.propagation.CurrentTraceContext} at startup
 * and it is immutable afterward (no post-build {@code addScopeDecorator}). Can a ByteBuddy java-agent
 * weave the scope enter/exit methods of the concrete {@code CurrentTraceContext} so an enter/exit
 * callback fires on the request thread AND on async handoff threads, carrying the same
 * {@code traceIdString()}?
 *
 * <p>This agent weaves, by body-only advice (no member/format changes), on every concrete subtype of
 * {@code brave.propagation.CurrentTraceContext}:
 * <ul>
 *   <li>{@code newScope(TraceContext)} and {@code maybeScope(TraceContext)} → ENTER callback
 *       carrying {@code context.traceIdString()}.</li>
 * </ul>
 * and, on every concrete implementor of {@code brave.propagation.CurrentTraceContext$Scope}:
 * <ul>
 *   <li>{@code close()} → EXIT callback carrying the trace id observed at close time
 *       (via {@code currentTraceContext.get().traceIdString()} captured by the enter advice's
 *       returned-scope identity — here we resolve the trace id reflectively from the scope itself
 *       when possible, else mark it unknown).</li>
 * </ul>
 *
 * <p>No hard dependency on brave: the {@code TraceContext} argument and {@code Scope} are referenced
 * as {@code Object}, and {@code traceIdString()} is invoked reflectively in {@link BraveScopeBridge}.
 */
public final class BraveScopeHookAgent {

    private BraveScopeHookAgent() {}

    public static void premain(String arg, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String arg, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        System.err.println("[BRAVE-SPIKE] agent installing (premain/agentmain)");

        // Match concrete subtypes of brave.propagation.CurrentTraceContext (the abstract base).
        ElementMatcher.Junction<TypeDescription> ctcSubtype =
                hasSuperType(named("brave.propagation.CurrentTraceContext"))
                        .and(not(named("brave.propagation.CurrentTraceContext")));

        // Match concrete implementors of the Scope interface.
        ElementMatcher.Junction<TypeDescription> scopeImpl =
                hasSuperType(named("brave.propagation.CurrentTraceContext$Scope"))
                        .and(not(named("brave.propagation.CurrentTraceContext$Scope")));

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(none()) // do not ignore brave/bootstrap-adjacent types
                .type(ctcSubtype)
                .transform((builder, type, cl, module, pd) -> {
                    System.err.println("[BRAVE-SPIKE] weaving CurrentTraceContext subtype: " + type.getName());
                    return builder
                            .visit(Advice.to(ScopeEnterAdvice.class)
                                    .on(named("newScope").and(takesArguments(1))))
                            .visit(Advice.to(ScopeEnterAdvice.class)
                                    .on(named("maybeScope").and(takesArguments(1))));
                })
                .type(scopeImpl)
                .transform((builder, type, cl, module, pd) -> {
                    System.err.println("[BRAVE-SPIKE] weaving Scope implementor: " + type.getName());
                    return builder.visit(Advice.to(ScopeCloseAdvice.class)
                            .on(named("close").and(takesArguments(0))));
                })
                .installOn(inst);

        System.err.println("[BRAVE-SPIKE] agent installed");
    }

    private static ElementMatcher.Junction<TypeDescription> none() {
        return isSubTypeOf(Void.class).and(not(isSubTypeOf(Void.class)));
    }
}
