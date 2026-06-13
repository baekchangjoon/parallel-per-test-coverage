package io.pjacoco.agent.inbound.servlet;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.pjacoco.agent.inbound.InboundActivator;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStoreRegistry;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** v1 servlet implementation: advises the single {@code HttpServlet#service(ServletRequest,ServletResponse)} choke point. */
public final class ServletInboundActivator implements InboundActivator {

    public ServletInboundActivator(TestStoreRegistry registry, Metrics metrics, AgentLog log) {
        ServletAdvice.registry = registry;   // bind the static the woven advice reads
    }

    @Override
    public void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()                    // body-only advice: no member changes
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // HttpServlet declares service(ServletRequest,ServletResponse) — the single entry every
                // request goes through (subclasses override the HttpServletRequest variant / doGet/doPost).
                .type(named("javax.servlet.http.HttpServlet"))
                .transform((builder, type, classLoader, module, pd) -> builder.visit(
                        Advice.to(ServletAdvice.class).on(
                                named("service")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                                        .and(takesArgument(1, named("javax.servlet.ServletResponse"))))))
                .installOn(inst);
    }
}
