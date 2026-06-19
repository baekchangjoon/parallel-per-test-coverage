package io.pjacoco.agent.trace;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CoverageKeyResolverTest {

    @Test
    void resolvePrefersFirstNonNull() {
        CoverageKeyResolver r = new CoverageKeyResolver(asList(
                () -> null, () -> "trace-1", () -> "local-2"));
        assertEquals("trace-1", r.resolve());
    }

    @Test
    void resolveNullWhenAllEmpty() {
        assertNull(new CoverageKeyResolver(asList(() -> null, () -> null)).resolve());
    }

    @Test
    void throwingSourceIsSkipped() {
        CoverageKeyResolver r = new CoverageKeyResolver(asList(
                () -> { throw new RuntimeException("boom"); },
                () -> "x"));
        assertEquals("x", r.resolve());
    }
}
