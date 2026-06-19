package io.pjacoco.agent.trace;

import java.util.List;

/**
 * Resolves a coverage key from an ordered list of {@link TestIdSource}s.
 * Returns the first non-null key; sources that throw are skipped (best-effort, REQ-003).
 */
public final class CoverageKeyResolver {
    private final List<TestIdSource> sources;

    public CoverageKeyResolver(List<TestIdSource> sources) {
        this.sources = sources;
    }

    /**
     * Iterates sources in order and returns the first non-null key.
     *
     * @return the first non-null key, or {@code null} if all sources returned null or threw.
     */
    public String resolve() {
        for (TestIdSource source : sources) {
            try {
                String key = source.currentKey();
                if (key != null) return key;
            } catch (Throwable ignored) {
                // best-effort: a throwing source is skipped, not fatal (REQ-003)
            }
        }
        return null;
    }
}
