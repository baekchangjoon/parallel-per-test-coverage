package io.pjacoco.agent.trace.boot;

import java.lang.reflect.Method;

/**
 * Thin static dispatcher injected into the BOOTSTRAP classloader so that OTel-agent-shaded
 * context-storage advice (also bootstrap-resident) can reach the system-CL bridge.
 *
 * <h2>Classloader architecture</h2>
 * <p>The OTel javaagent loads {@code ThreadLocalContextStorage} on the bootstrap classloader.
 * Advice inlined into that class can only call code reachable via the bootstrap CL's own
 * parent chain — the system classloader (where pjacoco's shaded jar lives) is NOT in that
 * chain. This class is therefore injected to bootstrap via
 * {@code Instrumentation.appendToBootstrapClassLoaderSearch} at agent startup, before any OTel
 * storage class is woven.
 *
 * <h2>Handler binding: reflective to avoid classloader mismatch</h2>
 * <p>{@link #handler} is typed as plain {@code Object} to avoid a classloader mismatch:
 * the bootstrap copy of {@link TraceWeaveHandler} and the system-CL copy loaded from the pjacoco
 * jar are different {@code Class} objects in the JVM, so a system-CL object that
 * {@code implements TraceWeaveHandler} (the system-CL interface) cannot be cast to the bootstrap
 * copy of that interface. Keeping {@link #handler} as {@code Object} sidesteps this; the actual
 * dispatch uses pre-resolved {@code Method} objects that reference the SYSTEM-CL interface
 * (loaded reflectively on first use). This is safe because:
 * <ul>
 *   <li>The system CL is a CHILD of bootstrap, so a method object obtained from the system-CL
 *       interface can be invoked on a system-CL receiver without restriction.</li>
 *   <li>The bootstrap copy of this gateway does the reflective lookup of the system-CL
 *       interface via {@code ClassLoader.getSystemClassLoader()} on first use.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Every public method is fully try/catch(Throwable) — a bug here must never propagate
 *       into the application (REQ-003).</li>
 *   <li>{@link #handler} is {@code volatile} so that the write from {@code Bootstrap.premain}
 *       is immediately visible to advice running on any thread.</li>
 * </ul>
 *
 * <p><strong>MUST depend on nothing outside {@code java.*}</strong> — any extra import would
 * prevent bootstrap injection. All heavy lifting is done reflectively.
 */
public final class TraceWeaveGateway {

    private TraceWeaveGateway() {}

    /**
     * The system-CL {@code TraceScopeBridge} instance, stored as {@code Object} to avoid a
     * classloader mismatch (see Javadoc). Set via {@link #setHandler} after bootstrap injection.
     */
    public static volatile Object handler;

    /** Cached reflective method: {@code TraceWeaveHandler#onScopeEnter(String, Object)}. */
    private static volatile Method onScopeEnterMethod;

    /** Cached reflective method: {@code TraceWeaveHandler#onScopeExit(Object)}. */
    private static volatile Method onScopeExitMethod;

    /**
     * Sets the handler and pre-resolves the dispatch methods from the SYSTEM classloader's
     * {@code TraceWeaveHandler} interface.  Call this once after bootstrap injection is complete
     * (i.e. after {@code appendToBootstrapClassLoaderSearch}) so the system-CL interface is
     * accessible via {@code Class.forName(..., false, ClassLoader.getSystemClassLoader())}.
     *
     * @param h a system-CL object that implements {@code io.pjacoco.agent.trace.boot.TraceWeaveHandler}
     */
    public static void setHandler(Object h) {
        try {
            if (h == null) {
                handler = null;
                return;
            }
            // Load TraceWeaveHandler from the SYSTEM classloader (where TraceScopeBridge lives).
            // The bootstrap copy of this class uses getSystemClassLoader() to look past itself.
            ClassLoader sysCl = ClassLoader.getSystemClassLoader();
            Class<?> iface = Class.forName(
                    "io.pjacoco.agent.trace.boot.TraceWeaveHandler", false, sysCl);
            onScopeEnterMethod = iface.getMethod("onScopeEnter", String.class, Object.class);
            onScopeExitMethod  = iface.getMethod("onScopeExit",  Object.class);
            handler = h;
        } catch (Throwable t) {
            // If method lookup fails the handler stays null — no-op (REQ-003).
        }
    }

    /**
     * Called from {@code OtelAttachAdvice} after {@code ThreadLocalContextStorage#attach(Context)}
     * returns. Extracts the trace-id from the shaded {@code Context} reflectively and dispatches to
     * {@link #handler}.
     *
     * @param shadedContext the {@code io.opentelemetry.javaagent.shaded...Context} argument
     * @param scope         the {@code Scope} returned by {@code attach}
     */
    public static void onOtelAttach(Object shadedContext, Object scope) {
        try {
            Object h = handler;
            if (h == null || shadedContext == null) {
                return;
            }
            String traceId = extractTraceId(shadedContext);
            if (traceId == null || traceId.isEmpty() || isAllZero(traceId)) {
                return;
            }
            Method m = onScopeEnterMethod;
            if (m != null) {
                m.invoke(h, traceId, scope);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    /**
     * Called from {@code OtelCloseAdvice} before {@code ThreadLocalContextStorage$ScopeImpl#close()}
     * runs.
     *
     * @param scope the scope instance being closed
     */
    public static void onOtelClose(Object scope) {
        try {
            Object h = handler;
            Method m = onScopeExitMethod;
            if (h != null && m != null) {
                m.invoke(h, scope);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    // -----------------------------------------------------------------------
    // Reflective trace-id extraction
    // -----------------------------------------------------------------------

    /**
     * Reads {@code Span.fromContext(ctx).getSpanContext().getTraceId()} from the shaded OTel
     * context, loading all intermediate types through the shaded context's own classloader
     * (the OTel AgentClassLoader).
     *
     * <p>The concrete {@code Span} / {@code SpanContext} impls (e.g. {@code SdkSpan}) are
     * non-public, so methods MUST be resolved from the PUBLIC shaded interfaces ({@code Span},
     * {@code SpanContext}) — invoking a method obtained from the concrete class throws
     * {@link IllegalAccessException}.
     *
     * <p>Mirrors the proven spike {@code OtelWeaveBridge#traceId}.
     */
    private static String extractTraceId(Object shadedContext) {
        try {
            ClassLoader cl = shadedContext.getClass().getClassLoader();
            // When bootstrap-loaded the classloader is null; fall back to system CL which can
            // see the OTel shaded types through the OTel agent's own CL delegation.
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }
            Class<?> spanIface = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.Span",
                    false, cl);
            Class<?> spanContextIface = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.api.trace.SpanContext",
                    false, cl);
            Class<?> contextClass = Class.forName(
                    "io.opentelemetry.javaagent.shaded.io.opentelemetry.context.Context",
                    false, cl);

            // Span.fromContext(ctx)
            Method fromContext = spanIface.getMethod("fromContext", contextClass);
            Object span = fromContext.invoke(null, shadedContext);
            if (span == null) {
                return null;
            }

            // span.getSpanContext()  — resolved on the public interface, not span.getClass()
            Method getSpanContext = spanIface.getMethod("getSpanContext");
            Object spanContext = getSpanContext.invoke(span);
            if (spanContext == null) {
                return null;
            }

            // spanContext.getTraceId()  — resolved on the public interface
            Method getTraceId = spanContextIface.getMethod("getTraceId");
            Object tid = getTraceId.invoke(spanContext);
            return tid == null ? null : String.valueOf(tid);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns {@code true} when every character in {@code s} is {@code '0'} (invalid trace-id). */
    private static boolean isAllZero(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
