package io.pjacoco.agent.inbound.brave;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.pjacoco.agent.inbound.InboundActivator;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.trace.TraceScopeBridge;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;

/**
 * Installs the Brave scope weave: body-only ByteBuddy advice on every concrete subtype of
 * {@code brave.propagation.CurrentTraceContext} (for {@code newScope} / {@code maybeScope}) and
 * every implementor of {@code brave.propagation.CurrentTraceContext$Scope} (for {@code close}).
 *
 * <p>Brave classes load on the APP classloader, so no bootstrap-classloader injection is needed.
 * The woven advice runs in the app CL and reaches {@link BraveScopeWeaveSupport} on the system CL
 * via normal parent delegation — exactly like {@code ServletAdvice}.
 *
 * <p>The activator is gated in {@code Bootstrap} on {@code traceKeyAutoCreate}: if that option is
 * off, this activator is never installed and the default (no-tracer) hot-path is unchanged.
 *
 * <p>Install failures are best-effort: any {@link Throwable} thrown by {@code installOn} is caught
 * and recorded in {@link Metrics#scopeHookInjectionFailures} so that a weave failure never crashes
 * agent startup (REQ-003).
 */
public final class BraveScopeInboundActivator implements InboundActivator {

    private final Metrics metrics;

    public BraveScopeInboundActivator(TraceScopeBridge bridge, Metrics metrics) {
        BraveScopeWeaveSupport.bridge = bridge;
        this.metrics = metrics;
    }

    @Override
    public void install(Instrumentation inst) {
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    // brave types would otherwise be caught by ByteBuddy's default ignore matcher
                    .ignore(none())
                    // Weave concrete subtypes of CurrentTraceContext (newScope + maybeScope)
                    .type(hasSuperType(named("brave.propagation.CurrentTraceContext"))
                            .and(not(named("brave.propagation.CurrentTraceContext"))))
                    .transform((builder, type, classLoader, module, pd) ->
                            builder
                                    .visit(Advice.to(BraveScopeEnterAdvice.class)
                                            .on(named("newScope").and(takesArguments(1))))
                                    .visit(Advice.to(BraveScopeEnterAdvice.class)
                                            .on(named("maybeScope").and(takesArguments(1)))))
                    // Weave concrete implementors of CurrentTraceContext$Scope (close)
                    .type(hasSuperType(named("brave.propagation.CurrentTraceContext$Scope"))
                            .and(not(named("brave.propagation.CurrentTraceContext$Scope"))))
                    .transform((builder, type, classLoader, module, pd) ->
                            builder.visit(Advice.to(BraveScopeCloseAdvice.class)
                                    .on(named("close").and(takesArguments(0)))))
                    .installOn(inst);
        } catch (Throwable t) {
            metrics.scopeHookInjectionFailures.incrementAndGet();
        }
    }
}
