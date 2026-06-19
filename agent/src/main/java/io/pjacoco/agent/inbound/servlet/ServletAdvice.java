package io.pjacoco.agent.inbound.servlet;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.BaggageParser;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.BraveTestIdSource;
import io.pjacoco.agent.trace.LocalTestIdSource;
import io.pjacoco.agent.trace.OtelTestIdSource;
import io.pjacoco.agent.trace.TestIdSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * Woven into {@code HttpServlet.service(ServletRequest,ServletResponse)} (single choke point).
 * The {@code baggage} header is read via reflection so the agent carries no javax.servlet
 * compile/runtime dependency (classloader-safe: a bundled servlet-api would be a different Class
 * than the app's). {@link #activate}/{@link #deactivate} are extracted for unit testing.
 *
 * <p>Key resolution order (highest to lowest priority):
 * <ol>
 *   <li>{@link OtelTestIdSource} — reads the current OTel trace ID from the thread context.</li>
 *   <li>{@link BraveTestIdSource} — reads the current Brave trace ID from the thread context.</li>
 *   <li>{@link LocalTestIdSource} — falls back to the {@code baggage} header's {@code test.id}.</li>
 * </ol>
 * In a no-tracer environment the first two sources return {@code null} (reflective Class.forName
 * throws → best-effort null), so baggage-only behaviour is byte-for-byte unchanged.
 *
 * <p>{@link #deactivate} unconditionally clears the {@link CoverageContext} so that thread-pool
 * workers never inherit a previous request's store (REQ-001 thread hygiene).  Async attribution
 * is handled entirely by the trace-scope WEAVE on worker threads — deactivate does not need a
 * guard.  See docs/superpowers/decisions/2026-06-19-deactivate-clear-semantics.md.
 */
public final class ServletAdvice {
    /** Bound once by ServletInboundActivator; read by the woven advice. */
    public static volatile TestStoreRegistry registry;

    /**
     * Ordered tracer sources — OTel first, Brave second.  Declared {@code public volatile} so
     * tests can replace the list without subclassing.  In production this list never changes.
     */
    public static volatile List<TestIdSource> traceSources =
            Arrays.<TestIdSource>asList(new OtelTestIdSource(), new BraveTestIdSource());

    private ServletAdvice() {}

    public static void activate(Object request) {
        try {
            TestStoreRegistry reg = registry;
            if (request == null || reg == null) return;

            // Build a per-request resolver: tracer sources first, baggage as the local fallback.
            List<TestIdSource> sources = new ArrayList<TestIdSource>(traceSources);
            final Object req = request;
            sources.add(new LocalTestIdSource(new java.util.function.Supplier<String>() {
                public String get() {
                    return BaggageParser.testId(header(req, "baggage"));
                }
            }));

            String key = new CoverageKeyResolver(sources).resolve();
            if (key != null) {
                TestStore store = reg.forCoverageKey(key);
                if (store != null) {
                    CoverageContext.set(store);
                }
            }
        } catch (Throwable ignored) { /* never disturb the app */ }
    }

    public static void deactivate() {
        try { CoverageContext.clear(); } catch (Throwable ignored) {}
    }

    private static String header(Object request, String name) {
        try {
            Method m = request.getClass().getMethod("getHeader", String.class);
            Object v = m.invoke(request, name);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            return null;   // not an HttpServletRequest / no getHeader(String)
        }
    }

    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Object request) { activate(request); }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() { deactivate(); }
}
