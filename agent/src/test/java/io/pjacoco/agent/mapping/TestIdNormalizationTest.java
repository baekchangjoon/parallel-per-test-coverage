package io.pjacoco.agent.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class TestIdNormalizationTest {
    @Test
    void toFqcnHashMethod() {
        assertEquals("com.x.T#m", TestIdNormalizer.normalize("com.x.T#m"));
        assertEquals("com.x.T#m", TestIdNormalizer.normalize("  com.x.T # m  "));
        assertEquals("com.x.T", TestIdNormalizer.normalize("com.x.T"));     // no method separator: kept
        assertEquals("com.x.T", TestIdNormalizer.normalize("com.x.T#"));    // empty method dropped
        assertNull(TestIdNormalizer.normalize(null));
        assertNull(TestIdNormalizer.normalize("   "));
        assertNull(TestIdNormalizer.normalize("#m"));                       // empty class -> null
    }
}
