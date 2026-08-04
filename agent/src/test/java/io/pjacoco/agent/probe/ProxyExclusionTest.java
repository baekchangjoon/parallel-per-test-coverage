package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.probe.ProbeInstrumentation.JacocoTransformer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.junit.jupiter.api.Test;

/**
 * Exclusion guard for JDK dynamic proxies: generated in dynamic modules (jdk.proxy3, com.sun.proxy)
 * as synthetic implementations of interfaces. Instrumenting them injects a $jacocoInit that crosses
 * JPMS read edges and throws IllegalAccessError at boot (Boot 3, discovered by the 2026-08-03 spike).
 * Unconditional exclusion, like the self-excludes above — user includes=/excludes= cannot re-enable it.
 */
class ProxyExclusionTest {

    private static JacocoTransformer transformer(String opts) {
        Instrumenter instrumenter = new Instrumenter(new LoggerRuntime());
        return new JacocoTransformer(instrumenter, AgentOptions.parse(opts),
                new io.pjacoco.agent.observability.Metrics(), new io.pjacoco.agent.observability.AgentLog());
    }

    /** Real, instrumentable class bytes (major >= 49) shipped in the agent's main classes. */
    private static byte[] classBytes() throws IOException {
        try (InputStream in = ProxyExclusionTest.class.getResourceAsStream(
                "/io/pjacoco/agent/probe/WarmupTarget.class")) {
            if (in == null) throw new IOException("WarmupTarget.class not found on test classpath");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Discovery method: Boot 3 failed to boot when jdk.proxy3 was instrumented (IllegalAccessError: $jacocoInit).
     *  instrumentFailures doesn't catch this class of bug because instrumentation itself succeeds — the
     *  failure is a runtime IllegalAccessError at class initialization (after instrument() already returned
     *  bytecode), so the only reliable observable is transform()'s return value at the pre-check layer. */
    @Test void transformReturnsNullForJdkProxy() throws IOException {
        assertNull(transformer("includes=*").transform(
                ProxyExclusionTest.class.getClassLoader(), "jdk/proxy3/$Proxy42", null, null, classBytes()),
                "jdk.proxy3 (jdk.proxy namespace) must NOT be instrumented under any includes");
        assertNull(transformer("includes=*").transform(
                ProxyExclusionTest.class.getClassLoader(), "com/sun/proxy/$Proxy7", null, null, classBytes()),
                "com.sun.proxy must NOT be instrumented under any includes");
    }

    @Test void explicitIncludesCannotReenable() throws IOException {
        // excludes cannot widen its set (it only shrinks the includes), so test the adversary via includes.
        assertNull(transformer("includes=jdk.proxy*").transform(
                ProxyExclusionTest.class.getClassLoader(), "jdk/proxy3/$Proxy42", null, null, classBytes()),
                "even explicit includes=jdk.proxy* must NOT re-enable jdk.proxy instrumentation");
    }
}
