package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.store.TestStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLS-REQ-009: the drop-ratio threshold suppresses minor background-thread noise on the single-active
 * (exact) path. ratio = droppedProbes / (droppedProbes + recordedProbes). droppedProbes/recordedProbes
 * are always exposed for visibility; {@code incompleteAttribution} is set only when ratio > threshold.
 */
class ExecWriterThresholdTest {

    /** A store with {@code recorded} hit probes (one class) and {@code dropped} attributed drops. */
    private static TestStore storeWith(int recorded, int dropped) {
        TestStore store = new TestStore("T", 1000L, null);
        for (int i = 0; i < recorded; i++) {
            store.record(1L, "com/example/C", i, Math.max(recorded, 1));
        }
        for (int i = 0; i < dropped; i++) {
            store.recordDrop();
        }
        return store;
    }

    private static String writeAndRead(Path dir, TestStore store, double threshold) throws Exception {
        new ExecWriter(threshold).write(dir, store, "passed", null, 2000L);
        return new String(Files.readAllBytes(dir.resolve("T.json")), "UTF-8");
    }

    @Test
    void defaultZero_flagsAnyDrop(@TempDir Path dir) throws Exception {
        // ratio = 1/(1+9) = 0.1 > 0.0 default -> flagged (backward-compatible).
        String json = writeAndRead(dir, storeWith(9, 1), 0.0);
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
        assertTrue(json.contains("\"droppedProbes\":1"), json);
        assertTrue(json.contains("\"recordedProbes\":9"), json);
    }

    @Test
    void belowThreshold_notFlaggedButStillVisible(@TempDir Path dir) throws Exception {
        // ratio = 1/10 = 0.1 <= 0.2 -> NOT flagged, but drop counts stay visible.
        String json = writeAndRead(dir, storeWith(9, 1), 0.2);
        assertFalse(json.contains("incompleteAttribution"), json);
        assertTrue(json.contains("\"droppedProbes\":1"), json);
        assertTrue(json.contains("\"recordedProbes\":9"), json);
    }

    @Test
    void aboveThreshold_flagged(@TempDir Path dir) throws Exception {
        // ratio = 1/(1+1) = 0.5 > 0.2 -> flagged.
        String json = writeAndRead(dir, storeWith(1, 1), 0.2);
        assertTrue(json.contains("\"incompleteAttribution\":true"), json);
    }
}
