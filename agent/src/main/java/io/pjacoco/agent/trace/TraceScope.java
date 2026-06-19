package io.pjacoco.agent.trace;

import io.pjacoco.agent.store.TestStore;

/**
 * Immutable restore handle returned by {@link TraceScopeBridge#enter(String)}.
 *
 * <p>Captures the {@link io.pjacoco.agent.context.CoverageContext} value that was active
 * before the scope was entered ({@code previous}), and the thread that entered it
 * ({@code ownerThread}).  {@link TraceScopeBridge#exit(TraceScope)} uses both fields: it
 * restores {@code previous} only when it runs on the same thread that entered (REQ-009
 * cross-thread-close guard).
 *
 * <p>This class carries no logic; it is a plain data carrier used within the
 * {@code io.pjacoco.agent.trace} package.
 */
final class TraceScope {

    /** The {@code CoverageContext} binding that was active when the scope was entered; may be null. */
    final TestStore previous;

    /** The thread that called {@link TraceScopeBridge#enter(String)}. */
    final Thread ownerThread;

    TraceScope(TestStore previous, Thread ownerThread) {
        this.previous = previous;
        this.ownerThread = ownerThread;
    }
}
