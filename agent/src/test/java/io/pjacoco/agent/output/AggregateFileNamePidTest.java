package io.pjacoco.agent.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** REQ-U02: {@code %p} placeholder in aggregateFile namespaces the whole-run dump per JVM so
 *  multi-module reactors don't overwrite each other's {@code aggregate.exec}. */
class AggregateFileNamePidTest {

    private static final Path OUT = Paths.get("/tmp/pjacoco-agg");

    @Test
    void pidPlaceholderIsSubstituted() {
        String name = AggregateWriter.resolve(OUT, "aggregate-%p.exec").getFileName().toString();
        assertFalse(name.contains("%p"), "%p must be substituted, got: " + name);
        assertTrue(name.startsWith("aggregate-") && name.endsWith(".exec"), name);
    }

    @Test
    void pidPlaceholderIsStableWithinAJvm() {
        String a = AggregateWriter.resolve(OUT, "aggregate-%p.exec").getFileName().toString();
        String b = AggregateWriter.resolve(OUT, "aggregate-%p.exec").getFileName().toString();
        assertEquals(a, b, "the resolved pid token must be stable within one JVM");
    }

    @Test
    void noPlaceholderIsUnchanged() {
        assertEquals("aggregate.exec",
                AggregateWriter.resolve(OUT, "aggregate.exec").getFileName().toString());
    }
}
