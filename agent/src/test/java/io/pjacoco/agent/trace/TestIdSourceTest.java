package io.pjacoco.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TestIdSourceTest {

    @Test
    void localReturnsTestIdFromSupplierElseNull() {
        LocalTestIdSource src = new LocalTestIdSource(() -> "com.x.T#m");
        assertEquals("com.x.T#m", src.currentKey());
        assertNull(new LocalTestIdSource(() -> null).currentKey());
    }

    @Test
    void localReturnsNullWhenSupplierThrows() {
        LocalTestIdSource src = new LocalTestIdSource(() -> {
            throw new RuntimeException("boom");
        });
        assertNull(src.currentKey());
    }
}
