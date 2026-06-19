package io.pjacoco.agent.trace;

import io.pjacoco.agent.context.CoverageContext;
import io.pjacoco.agent.store.TestStore;
import io.pjacoco.agent.store.TestStoreRegistry;
import io.pjacoco.agent.trace.boot.TraceWeaveHandler;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core bridge between a distributed-tracing scope lifecycle (Brave / OTel) and the per-thread
 * {@link CoverageContext} used by the coverage hot-path.
 *
 * <h2>Threading model</h2>
 * <p>Brave/OTel scopes are normally entered <em>and</em> closed on the <em>same</em> thread.
 * For async handoff the tracer re-enters a fresh scope on the receiving thread, so there is an
 * independent enter/exit pair on that thread too.  Each {@link #enter(String)} call captures the
 * {@link TraceScope#ownerThread owner thread} in the returned {@link TraceScope}; {@link
 * #exit(TraceScope)} silently ignores the call when it runs on a <em>different</em> thread
 * (REQ-009) — because {@link CoverageContext} is a {@link ThreadLocal}, a restore is only valid
 * on the same thread that entered.
 *
 * <h2>Scope-identity pairing (advice-facing API)</h2>
 * <p>{@link #onScopeEnter(String, Object)} and {@link #onScopeExit(Object)} are the entry-points
 * that Task 10b's woven byte-buddy advice will call.  They pair each exit to its enter via the
 * scope object's identity (Brave/OTel {@code Scope} impls do not override
 * {@code hashCode}/{@code equals}).  Entries are removed on exit; a leak can only occur if a scope
 * is never closed, which tracers guarantee does not happen.  A stricter weak-identity map is a
 * possible future hardening (REQ-016/018 territory) — not built here.
 *
 * <h2>Best-effort / never-throw contract (REQ-003)</h2>
 * <p>Every public method swallows all {@link Throwable}s.  A bug in this class must never
 * propagate into the application.
 */
public final class TraceScopeBridge implements TraceWeaveHandler {

    private final TestStoreRegistry registry;

    /**
     * Resolver used by {@link #enterResolved()} — the no-weave / choke-point fallback path that
     * resolves the coverage key from the current thread's tracer context (e.g. Brave {@code
     * Tracer.currentSpan()} or OTel {@code Context.current()}) without requiring byte-buddy weaving.
     * Retained as a field so that callers can inject a fully-configured resolver at construction
     * time and use this bridge both in woven advice ({@link #onScopeEnter}) and in direct-call
     * activation ({@link #enterResolved}).
     */
    private final CoverageKeyResolver resolver;

    /**
     * Maps scope-object identity → {@link TraceScope} handle so that {@link #onScopeExit} can pair
     * each exit to its enter.  Relies on default {@code Object.hashCode}/{@code equals} (identity
     * semantics) because Brave/OTel scope impls do not override them.
     */
    private final ConcurrentHashMap<Object, TraceScope> byScope =
            new ConcurrentHashMap<Object, TraceScope>();

    /**
     * Constructs a bridge.
     *
     * @param registry the store registry used for key lookup / auto-creation
     * @param resolver used by {@link #enterResolved()} for the no-weave fallback path
     */
    public TraceScopeBridge(TestStoreRegistry registry, CoverageKeyResolver resolver) {
        this.registry = registry;
        this.resolver = resolver;
    }

    // -----------------------------------------------------------------------
    // Core enter / exit
    // -----------------------------------------------------------------------

    /**
     * Activates the coverage store for {@code key} on the current thread and returns a restore
     * handle.  If {@code key} is {@code null} or no store can be found/created, the current
     * context is left unchanged but a valid (no-op) handle is still returned so that the paired
     * {@link #exit} is always safe to call.
     *
     * <p>Never throws (REQ-003).
     *
     * @param key coverage key (trace id, span id, etc.); {@code null} is treated as a no-op enter
     * @return a handle that must be passed to {@link #exit(TraceScope)} in the same finally-block
     */
    public TraceScope enter(String key) {
        TestStore prev = CoverageContext.get();
        try {
            TestStore store = (key == null) ? null : registry.forCoverageKey(key);
            if (store != null) {
                CoverageContext.set(store);
            }
        } catch (Throwable ignored) {
            // best-effort: any failure still returns a harmless restore handle (REQ-003)
        }
        return new TraceScope(prev, Thread.currentThread());
    }

    /**
     * Restores the {@link CoverageContext} to the value captured at {@link #enter} time.
     *
     * <p>If {@code scope} is {@code null} this is a no-op.  If this method is called on a
     * <em>different</em> thread than the one that called {@link #enter}, the call is silently
     * ignored — touching another thread's {@link ThreadLocal} is never valid (REQ-009).
     *
     * <p>Never throws (REQ-003).
     *
     * @param scope the handle returned by {@link #enter(String)}; may be {@code null}
     */
    public void exit(TraceScope scope) {
        if (scope == null) return;
        try {
            // REQ-009: a scope closed on a foreign thread must NOT corrupt that thread's binding.
            if (scope.ownerThread != Thread.currentThread()) return;
            if (scope.previous == null) {
                CoverageContext.clear();
            } else {
                CoverageContext.set(scope.previous);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    // -----------------------------------------------------------------------
    // No-weave / choke-point fallback
    // -----------------------------------------------------------------------

    /**
     * Resolves the coverage key from {@link #resolver} and delegates to {@link #enter(String)}.
     *
     * <p>This is the no-weave / choke-point fallback activation path: code that can
     * intercept a tracer call directly (e.g. wrapping a {@code Tracer.currentSpan()}) invokes this
     * instead of relying on byte-buddy weaving to call {@link #onScopeEnter}.
     *
     * @return a handle that must be passed to {@link #exit(TraceScope)}
     */
    public TraceScope enterResolved() {
        try {
            return enter(resolver.resolve());
        } catch (Throwable ignored) {
            // best-effort: resolver.resolve() threw before enter() could run (REQ-003)
            return new TraceScope(CoverageContext.get(), Thread.currentThread());
        }
    }

    // -----------------------------------------------------------------------
    // Advice-facing API (scope-identity pairing — called from Task 10b woven advice)
    // -----------------------------------------------------------------------

    /**
     * Called by woven advice when a tracer scope is entered.  Activates coverage for {@code key}
     * and records the {@link TraceScope} handle keyed by {@code scopeId}'s identity.
     *
     * <p>If {@code scopeId} is {@code null} the scope cannot be paired with a later
     * {@link #onScopeExit} call; coverage is still activated for the duration of this thread's
     * execution, but the handle will not be found by exit.
     *
     * <p>All operations are best-effort; never throws (REQ-003).
     *
     * @param key     coverage key extracted from the span (may be {@code null})
     * @param scopeId the tracer scope object whose identity is used as a map key; may be {@code null}
     */
    public void onScopeEnter(String key, Object scopeId) {
        try {
            TraceScope ts = enter(key);
            if (scopeId != null) {
                byScope.put(scopeId, ts);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }

    /**
     * Called by woven advice when a tracer scope is closed.  Looks up the {@link TraceScope}
     * handle registered for {@code scopeId} and delegates to {@link #exit(TraceScope)}.
     *
     * <p>If {@code scopeId} is {@code null} or was never registered (e.g. NOOP scope), this is a
     * harmless no-op.
     *
     * <p>Never throws (REQ-003).
     *
     * @param scopeId the tracer scope object passed to {@link #onScopeEnter}; may be {@code null}
     */
    public void onScopeExit(Object scopeId) {
        if (scopeId == null) return;
        try {
            TraceScope ts = byScope.remove(scopeId);
            if (ts != null) {
                exit(ts);
            }
        } catch (Throwable ignored) {
            // best-effort (REQ-003)
        }
    }
}
