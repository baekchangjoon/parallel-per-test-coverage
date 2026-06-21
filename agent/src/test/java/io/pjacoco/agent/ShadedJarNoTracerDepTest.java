package io.pjacoco.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * REQ-002 build-time guard: the shaded agent jar must contain NO Brave or OpenTelemetry
 * classes, and its bytecode must target Java 8 (class major version 52).
 *
 * <p>Gated by {@code @EnabledIfSystemProperty} so it is a no-op when the system property is
 * absent (e.g. plain {@code ./gradlew :agent:test} without the shadow step); the {@code test}
 * task in {@code agent/build.gradle.kts} depends on {@code shadowJar} and sets
 * {@code pjacoco.shadedJar} via {@code doFirst}, so the guard runs automatically there.
 */
class ShadedJarNoTracerDepTest {

    /** Brave package prefix that must not appear in the shaded agent jar. */
    private static final String BRAVE_PREFIX = "brave/";

    /** OpenTelemetry package prefix that must not appear in the shaded agent jar. */
    private static final String OTEL_PREFIX = "io/opentelemetry/";

    /**
     * Un-relocated ASM package prefix that must not appear in the shaded agent jar. jacoco-core
     * depends on org.objectweb.asm; if it ships un-relocated, the embedded jacoco resolves ASM from
     * the target app's classpath and throws NoSuchMethodError (e.g. Type.getArgumentCount(String),
     * ASM 9.6+) against an older app ASM, silently dropping coverage. All ASM must be relocated under
     * io/pjacoco/shaded/.
     */
    private static final String UNRELOCATED_ASM_PREFIX = "org/objectweb/asm/";

    /** Where jacoco's ASM dependency must live after relocation (see agent/build.gradle.kts). */
    private static final String RELOCATED_ASM_PREFIX = "io/pjacoco/shaded/asm/";

    /** A representative agent class whose bytecode major version is checked. */
    private static final String PROBE_CLASS = "io/pjacoco/agent/probe/CoverageBridge.class";

    /** Java 8 class file major version. */
    private static final int JAVA8_MAJOR = 52;

    @Test
    @EnabledIfSystemProperty(named = "pjacoco.shadedJar", matches = ".+")
    void shadedJarContainsNoBraveClasses() throws Exception {
        File jar = new File(System.getProperty("pjacoco.shadedJar"));
        assertTrue(jar.isFile(), "shaded jar not found: " + jar);

        List<String> offending = new ArrayList<String>();
        try (ZipFile zf = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith(BRAVE_PREFIX)) {
                    offending.add(name);
                }
            }
        }
        assertTrue(offending.isEmpty(),
                "shaded jar must contain NO brave/* classes (REQ-002), but found: " + offending);
    }

    @Test
    @EnabledIfSystemProperty(named = "pjacoco.shadedJar", matches = ".+")
    void shadedJarContainsNoOpenTelemetryClasses() throws Exception {
        File jar = new File(System.getProperty("pjacoco.shadedJar"));
        assertTrue(jar.isFile(), "shaded jar not found: " + jar);

        List<String> offending = new ArrayList<String>();
        try (ZipFile zf = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith(OTEL_PREFIX)) {
                    offending.add(name);
                }
            }
        }
        assertTrue(offending.isEmpty(),
                "shaded jar must contain NO io/opentelemetry/* classes (REQ-002), but found: " + offending);
    }

    @Test
    @EnabledIfSystemProperty(named = "pjacoco.shadedJar", matches = ".+")
    void shadedJarContainsNoUnrelocatedAsmClasses() throws Exception {
        File jar = new File(System.getProperty("pjacoco.shadedJar"));
        assertTrue(jar.isFile(), "shaded jar not found: " + jar);

        List<String> offending = new ArrayList<String>();
        boolean relocatedAsmPresent = false;
        try (ZipFile zf = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith(UNRELOCATED_ASM_PREFIX)) {
                    offending.add(name);
                } else if (name.startsWith(RELOCATED_ASM_PREFIX)) {
                    relocatedAsmPresent = true;
                }
            }
        }
        assertTrue(offending.isEmpty(),
                "shaded jar must contain NO un-relocated org/objectweb/asm/* classes — jacoco's ASM "
                        + "must be relocated under io/pjacoco/shaded/ so it cannot clash with an older "
                        + "ASM on the target app's classpath. Found: " + offending);
        // Positive guard: absence alone would also pass if jacoco ever stopped bundling ASM. Confirm the
        // ASM the embedded jacoco binds to is actually present, under the relocated package.
        assertTrue(relocatedAsmPresent,
                "shaded jar must contain the relocated ASM under " + RELOCATED_ASM_PREFIX
                        + " (jacoco's bundled ASM) — none found.");
    }

    @Test
    @EnabledIfSystemProperty(named = "pjacoco.shadedJar", matches = ".+")
    void agentClassBytecodeTargetsJava8() throws Exception {
        File jar = new File(System.getProperty("pjacoco.shadedJar"));
        assertTrue(jar.isFile(), "shaded jar not found: " + jar);

        try (ZipFile zf = new ZipFile(jar)) {
            ZipEntry entry = zf.getEntry(PROBE_CLASS);
            assertFalse(entry == null,
                    PROBE_CLASS + " not found in shaded jar — cannot verify Java 8 bytecode");

            try (InputStream is = zf.getInputStream(entry);
                 DataInputStream dis = new DataInputStream(is)) {
                int magic = dis.readInt();           // bytes 0-3
                dis.readUnsignedShort();             // bytes 4-5: minor version
                int major = dis.readUnsignedShort(); // bytes 6-7: major version
                if (magic != (int) 0xCAFEBABE) {
                    fail("Not a valid class file (magic=0x" + Integer.toHexString(magic) + ")");
                }
                // Allow major == 52 (Java 8). Compiled with options.release.set(8) in the build.
                if (major != JAVA8_MAJOR) {
                    fail(PROBE_CLASS + " has class major version " + major
                            + " but Java 8 (52) is required (REQ-002). "
                            + "Check that options.release.set(8) is in effect for the main source set.");
                }
            }
        }
    }
}
