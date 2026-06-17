package com.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaUnitTest {
    @Test
    void hitsBeta() {
        assertEquals("x", new Beta().hit(true));
    }
}
