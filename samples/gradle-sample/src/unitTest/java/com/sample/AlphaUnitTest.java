package com.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure unit test (no servlet): calls Alpha directly on the test thread. Auto-registered in-process
 *  extension brackets it, so it gets its own AlphaUnitTest#hitsAlpha.exec. */
class AlphaUnitTest {
    @Test
    void hitsAlpha() {
        assertEquals(1, new Alpha().hit(5));
    }
}
