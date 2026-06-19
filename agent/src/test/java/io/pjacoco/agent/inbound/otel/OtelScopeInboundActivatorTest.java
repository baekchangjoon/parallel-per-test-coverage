package io.pjacoco.agent.inbound.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OtelScopeInboundActivator#discoverOtelJar(java.util.List)}.
 *
 * <p>Reproduces the C3 trace-context gap root cause: the OTel javaagent is discovered for the
 * ByteBuddy {@code TypePool} locator by scanning {@code -javaagent:} arguments, but the original
 * implementation matched only the conventional filename substring {@code "opentelemetry-javaagent"}.
 * Real deployments (tainted-spring) mount the jar under a different name
 * ({@code -javaagent:/opt/otel/otel.jar}), so discovery returned {@code null} and the entire OTel
 * scope weave install was silently skipped — leaving Kafka-consumer threads (which have no
 * servlet/JUnit choke-point) with zero coverage attribution.
 */
class OtelScopeInboundActivatorTest {

    /** The shaded class the weave targets — its presence identifies a jar as the OTel javaagent. */
    private static final String STORAGE_ENTRY =
            "io/opentelemetry/javaagent/shaded/io/opentelemetry/context/ThreadLocalContextStorage.class";

    @Test
    void discoversByConventionalFilename() {
        String found = OtelScopeInboundActivator.discoverOtelJar(
                Arrays.asList("-javaagent:/opt/otel/opentelemetry-javaagent.jar=opts"));
        assertEquals("/opt/otel/opentelemetry-javaagent.jar", found);
    }

    @Test
    void discoversAgentMountedWithNonConventionalFilename(@TempDir Path tmp) throws Exception {
        // A genuine OTel agent jar mounted under a non-conventional name (as tainted-spring does).
        File otelJar = makeJar(tmp, "otel.jar", STORAGE_ENTRY);
        String found = OtelScopeInboundActivator.discoverOtelJar(Collections.singletonList(
                "-javaagent:" + otelJar.getAbsolutePath() + "=destfile=/coverage,traceKeyAutoCreate=true"));
        assertEquals(otelJar.getAbsolutePath(), found);
    }

    @Test
    void ignoresNonOtelJavaagent(@TempDir Path tmp) throws Exception {
        // pjacoco's own agent is also a -javaagent but contains no shaded OTel storage class.
        File pjacoco = makeJar(tmp, "agent.jar", "io/pjacoco/agent/Bootstrap.class");
        String found = OtelScopeInboundActivator.discoverOtelJar(Collections.singletonList(
                "-javaagent:" + pjacoco.getAbsolutePath() + "=port=6310"));
        assertNull(found);
    }

    @Test
    void returnsNullWhenNoJavaagent() {
        assertNull(OtelScopeInboundActivator.discoverOtelJar(
                Arrays.asList("-Xmx512m", "-Dfoo=bar")));
    }

    private static File makeJar(Path dir, String name, String entry) throws Exception {
        File jar = dir.resolve(name).toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new ZipEntry(entry));
            jos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jos.closeEntry();
        }
        return jar;
    }
}
