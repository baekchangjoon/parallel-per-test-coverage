package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.probe.ProbeInstrumentation.JacocoTransformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.junit.jupiter.api.Test;

/**
 * Root-cause guard for the {@code includes=*} premain crash: with the default wildcard include the
 * transformer used to instrument bootstrap/JDK classes (loader == null) during premain, tripping the
 * native JPLIS {@code *** java.lang.instrument ASSERTION FAILED ***} (JPLISAgent.c) and aborting the
 * VM. jacoco skips bootstrap classes by default ({@code inclbootstrapclasses=false}); pjacoco must too.
 */
class JacocoTransformerBootstrapTest {

    private static JacocoTransformer transformer(String opts) {
        Instrumenter instrumenter = new Instrumenter(new LoggerRuntime());
        return new JacocoTransformer(instrumenter, AgentOptions.parse(opts));
    }

    /** Real, instrumentable class bytes (major >= 49) shipped in the agent's main classes. */
    private static byte[] sampleClassBytes() throws IOException {
        try (InputStream in = JacocoTransformerBootstrapTest.class.getResourceAsStream(
                "/io/pjacoco/agent/probe/WarmupTarget.class")) {
            assertNotNull(in, "WarmupTarget.class must be on the test classpath");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    @Test
    void bootstrapClassIsSkippedUnderWildcardIncludes() throws IOException {
        byte[] bytes = sampleClassBytes();
        // loader == null => bootstrap classloader (java.*, sun.*, jdk.* internals); name matches "*".
        byte[] result = transformer("includes=*")
                .transform(null, "app/Sample", null, null, bytes);
        assertNull(result,
                "bootstrap classes (loader==null) must NOT be instrumented under includes=* "
                        + "(jacoco inclbootstrapclasses=false default) — instrumenting them crashes premain");
    }

    @Test
    void applicationClassIsStillInstrumentedUnderWildcardIncludes() throws IOException {
        byte[] bytes = sampleClassBytes();
        // loader != null => application/system classloader: normal instrumentation must be preserved.
        byte[] result = transformer("includes=*")
                .transform(JacocoTransformerBootstrapTest.class.getClassLoader(),
                        "app/Sample", null, null, bytes);
        assertNotNull(result, "non-bootstrap classes matching includes must still be instrumented");
    }

    @Test
    void platformClassLoaderJdkClassIsSkippedUnderWildcardIncludes() throws Exception {
        // Java 9+: JDK runtime classes (com.sun.net.httpserver, java.sql, ...) are loaded by the
        // platform classloader, NOT the bootstrap loader — loader != null, so the bootstrap guard alone
        // misses them. Instrumenting them injects a $jacocoInit that crosses module read edges and throws
        // IllegalAccessError (this is what crashed premain on com.sun.net.httpserver.HttpServer).
        ClassLoader platform = platformClassLoaderOrNull();
        assumeTrue(platform != null, "no platform classloader (Java 8) — bootstrap guard covers JDK classes");
        byte[] bytes = sampleClassBytes();
        byte[] result = transformer("includes=*")
                .transform(platform, "app/Sample", null, null, bytes);
        assertNull(result, "platform-classloader JDK classes must NOT be instrumented under includes=*");
    }

    private static ClassLoader platformClassLoaderOrNull() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Test
    void bootstrapClassIsInstrumentedWhenExplicitlyOptedIn() throws IOException {
        byte[] bytes = sampleClassBytes();
        byte[] result = transformer("includes=*,inclbootstrapclasses=true")
                .transform(null, "app/Sample", null, null, bytes);
        assertNotNull(result, "inclbootstrapclasses=true must re-enable bootstrap instrumentation (opt-in)");
    }
}
