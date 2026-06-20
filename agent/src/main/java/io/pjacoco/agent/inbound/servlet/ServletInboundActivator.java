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

/** v1 servlet implementation: advises the single {@code HttpServlet#service(ServletRequest,ServletResponse)} choke point.
 *
 * <p>Matches both the {@code javax.servlet} (Servlet &le;4 / Spring Boot 2) and the {@code jakarta.servlet}
 * (Servlet 5+ / Spring Boot 3–4, e.g. Tomcat 10/11) namespaces. The two fully-qualified names are disjoint,
 * so for any given target app only the namespace actually on its classpath is ever woven. The woven
 * {@link ServletAdvice} reads the {@code baggage} header reflectively, so it needs no servlet dependency
 * and is identical for both namespaces. */
public final class ServletInboundActivator implements InboundActivator {

    public ServletInboundActivator(TestStoreRegistry registry, Metrics metrics, AgentLog log) {
        ServletAdvice.registry = registry;   // bind the statics the woven advice reads
        ServletAdvice.metrics  = metrics;
        ServletAdvice.log      = log;
    }

    @Override
    public void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()                    // body-only advice: no member changes
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // HttpServlet declares service(ServletRequest,ServletResponse) — the single entry every
                // request goes through (subclasses override the HttpServletRequest variant / doGet/doPost).
                // Match both servlet namespaces; the absent one simply never matches on a given app.
                .type(named("javax.servlet.http.HttpServlet")
                        .or(named("jakarta.servlet.http.HttpServlet")))
                .transform((builder, type, classLoader, module, pd) -> builder.visit(
                        Advice.to(ServletAdvice.class).on(
                                named("service")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("javax.servlet.ServletRequest")
                                                .or(named("jakarta.servlet.ServletRequest"))))
                                        .and(takesArgument(1, named("javax.servlet.ServletResponse")
                                                .or(named("jakarta.servlet.ServletResponse")))))))
                .installOn(inst);
    }
}
