package io.pjacoco.spike;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPIKE-ONLY runtime bridge invoked from woven Brave scope advice. Prints
 * {@code [SCOPE-ENTER tid=<traceIdString> thread=<name>]} / {@code [SCOPE-EXIT ...]} to stderr so the
 * spike can observe whether scope enter/exit fires on the request thread AND on async handoff threads
 * carrying the same trace id.
 *
 * <p>No compile-time dependency on brave: {@code traceIdString()} is invoked reflectively.
 */
public final class BraveScopeBridge {

    private BraveScopeBridge() {}

    /** scope instance identity → trace id, so EXIT can be paired with its ENTER across threads. */
    private static final Map<Object, String> SCOPE_TO_TID = new ConcurrentHashMap<Object, String>();

    private static volatile Method traceIdMethod;

    public static void onEnter(Object traceContext, Object returnedScope) {
        if (traceContext == null) {
            // maybeScope(null) → NOOP scope; nothing meaningful to attribute.
            return;
        }
        String tid = traceIdString(traceContext);
        System.err.println("[SCOPE-ENTER tid=" + tid + " thread=" + Thread.currentThread().getName()
                + " scope=" + identity(returnedScope) + " ctc=" + className(returnedScope) + "]");
        if (returnedScope != null) {
            SCOPE_TO_TID.put(returnedScope, tid);
        }
    }

    public static void onExit(Object scope) {
        if (scope == null) {
            return;
        }
        String tid = SCOPE_TO_TID.remove(scope);
        if (tid == null) {
            // Either a NOOP/unregistered scope, or close() fired on a scope we did not capture.
            return;
        }
        System.err.println("[SCOPE-EXIT  tid=" + tid + " thread=" + Thread.currentThread().getName()
                + " scope=" + identity(scope) + "]");
    }

    private static String traceIdString(Object traceContext) {
        try {
            Method m = traceIdMethod;
            if (m == null || m.getDeclaringClass() != traceContext.getClass()) {
                m = traceContext.getClass().getMethod("traceIdString");
                traceIdMethod = m;
            }
            Object v = m.invoke(traceContext);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String identity(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
