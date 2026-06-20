package io.pjacoco.agent.mapping;

/**
 * Canonicalizes a testId to {@code FQCN#method} shape: trims, and normalizes the single {@code '#'}
 * separator with trimmed class/method segments. It does NOT fabricate a package (a simple class name
 * stays a simple class name) — the canonical FQCN must be supplied by the registrant (see REQ-014 and
 * the testkit adapter alignment in Task 7).
 */
public final class TestIdNormalizer {
    private TestIdNormalizer() {}

    /** @return canonical {@code class#method}, or null for null/blank/empty-class input. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int hash = s.indexOf('#');
        if (hash < 0) return s;
        String cls = s.substring(0, hash).trim();
        String method = s.substring(hash + 1).trim();
        if (cls.isEmpty()) return null;
        return method.isEmpty() ? cls : cls + "#" + method;
    }
}
