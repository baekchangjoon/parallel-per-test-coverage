package io.pjacoco.testkit.junit5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The extension is a no-op without the agent; assert it loads + brackets a test without throwing.
 *  (Per-test routing is verified end-to-end by the gradle-plugin functional test AC-IP1/IP6.) */
@ExtendWith(PjacocoInProcessExtension.class)
class PjacocoInProcessExtensionTest {

    @Test
    void runsCleanlyWithoutAnAgent() {
        assertDoesNotThrow(() -> { int x = 1 + 1; });
    }
}
