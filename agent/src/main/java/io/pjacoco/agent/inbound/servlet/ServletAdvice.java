package io.pjacoco.agent.inbound.servlet;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.inbound.BaggageParser;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.BraveTestIdSource;
import io.pjacoco.agent.trace.OtelTestIdSource;
import io.pjacoco.agent.trace.TestIdSource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>Local/baggage fallback — reads {@code test.id} from one of three headers, in priority
 *       order: W3C {@code baggage}, Brave/Micrometer {@code test.id} field header, legacy Sleuth
 *       {@code baggage-test.id} field header (REQ-MM-004). See {@link #fallbackTestId}.</li>
 * </ol>
 * In a no-tracer environment the first two sources return {@code null} (reflective Class.forName
 * throws → best-effort null), so activation falls back to the baggage headers.  Each fallback
 * activation increments {@link Metrics#fallbackActivations} (REQ-019), and the winning header type
 * increments one of {@link Metrics#testIdFromW3cBaggage}, {@link Metrics#testIdFromFieldHeader},
 * {@link Metrics#testIdFromLegacyFieldHeader} (REQ-MM-007).
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
     * Bound once by ServletInboundActivator; read by the woven advice to count fallback
     * activations (REQ-019).  May be {@code null} in tests that do not need metric assertions.
     */
    public static volatile Metrics metrics;

    /** Bound once by ServletInboundActivator; used for the once-per-JVM missing-test.id WARN. */
    public static volatile AgentLog log;
    private static final AtomicBoolean MISSING_ID_WARNED = new AtomicBoolean(false);
    /** Test-only: reset the once-per-JVM warn guard between integration tests. */
    public static void resetWarnGuardForTest() { MISSING_ID_WARNED.set(false); }

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

            // Try tracer sources first (OTel, Brave).
            String key = new CoverageKeyResolver(traceSources).resolve();
            boolean fromFallback = false;

            // If no tracer context is active, fall back to the baggage headers (REQ-007 + REQ-MM-004).
            if (key == null) {
                String local = fallbackTestId(request);
                if (local != null) {
                    key = local;
                    fromFallback = true;
                    Metrics m = metrics;
                    if (m != null) m.fallbackActivations.incrementAndGet();  // REQ-019 불변식: 3종 공통
                }
            }

            if (key != null) {
                // REQ-MM-006: auto-create (traceKeyAutoCreate) is tracer-key-only. A baggage-fallback-derived
                // key always follows the plain registry contract (strict/lenient) via active(key).
                TestStore store = fromFallback ? reg.active(key) : reg.forCoverageKey(key);
                if (store != null) {
                    CoverageContext.set(store);
                }
            } else if (CoverageContext.get() == null && reg.hasActive()) {
                // No tracer/baggage test.id, no active context on this thread, but a collection window is
                // open → this request's probes will be dropped. Surface it (CLS-REQ-001).
                Metrics mm = metrics;
                if (mm != null) mm.missingTestIdInbound.incrementAndGet();
                if (MISSING_ID_WARNED.compareAndSet(false, true)) {
                    String msg = "inbound HTTP request had no test.id (no tracer scope, no 'baggage: test.id') "
                            + "and no active in-process context; its probes are not attributed to any test. "
                            + "For black-box HTTP tests (SpringBootTest RANDOM_PORT + TestRestTemplate/RestAssured) "
                            + "use the out-of-process baggage model. (logged once; see shutdown summary for totals)";
                    AgentLog lg = log;
                    if (lg != null) lg.warn("missing-test-id", msg);
                    else System.err.println("[pjacoco][WARN] " + msg);
                }
            }
        } catch (Throwable ignored) { /* never disturb the app */ }
    }

    public static void deactivate() {
        try { CoverageContext.clear(); } catch (Throwable ignored) {}
    }

    /** 폴백 헤더 순서(REQ-MM-004): W3C baggage → Brave/Micrometer 필드 → legacy Sleuth. */
    static String fallbackTestId(Object request) {
        String w3c = BaggageParser.testId(header(request, "baggage"));
        if (w3c != null) {
            Metrics m = metrics;
            if (m != null) m.testIdFromW3cBaggage.incrementAndGet();
            return w3c;
        }
        String field = trimToNull(header(request, "test.id"));
        if (field != null) {
            Metrics m = metrics;
            if (m != null) m.testIdFromFieldHeader.incrementAndGet();
            return field;
        }
        String legacy = trimToNull(header(request, "baggage-test.id"));
        if (legacy != null) {
            Metrics m = metrics;
            if (m != null) m.testIdFromLegacyFieldHeader.incrementAndGet();
            return legacy;
        }
        return null;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;                    // REQ-MM-005: 빈 값은 매치 아님
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
