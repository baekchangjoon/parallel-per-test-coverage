package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.pjacoco.agent.store.TestStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecSerializerTest {

    @Test
    void roundTripMatchesFileWriter(@TempDir Path dir) throws Exception {
        TestStore store = new TestStore("trace-1", 1000L, null);
        store.record(0x1234L, "com/example/Foo", 0, 3);
        store.record(0x1234L, "com/example/Foo", 2, 3);

        ExecWriter writer = new ExecWriter();
        long stoppedAt = 2000L;
        byte[] memoryBytes = writer.toExecBytes(store, stoppedAt);
        writer.writeExecFile(dir, store, stoppedAt);
        byte[] fileBytes = Files.readAllBytes(dir.resolve("trace-1.exec"));

        ExecFileLoader fromMemory = new ExecFileLoader();
        fromMemory.load(new ByteArrayInputStream(memoryBytes));
        ExecFileLoader fromFile = new ExecFileLoader();
        fromFile.load(dir.resolve("trace-1.exec").toFile());

        assertFalse(fromMemory.getExecutionDataStore().getContents().isEmpty());
        assertArrayEquals(fileBytes, memoryBytes);
        assertEquals(
                fromMemory.getExecutionDataStore().getContents().size(),
                fromFile.getExecutionDataStore().getContents().size());
    }
}
