package io.pjacoco.agent.mapping;

/** Resolves a coverage key (traceId) to a human-readable testId. Returns {@code null} when the key has
 *  no registered mapping — callers fall back to the raw key (REQ-012). */
public interface TraceMapping {
    String testIdFor(String traceId);
}
