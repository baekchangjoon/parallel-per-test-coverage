package io.pjacoco.agent.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjacoco.agent.AgentOptions;
import io.pjacoco.agent.observability.AgentLog;
import io.pjacoco.agent.observability.Metrics;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Regression test for the silent-instrumentation-loss failure mode (2026-06-21 feedback +
 * 2026-07-27 evaluation risk #1), encoding how it was discovered: in a real app, jacoco's
 * Instrumenter threw {@code NoSuchMethodError} (embedded jacoco compiled against ASM 9.6+
 * resolving against the app's ASM 9.1) for 18 classes; each was silently returned
 * uninstrumented — total coverage dropped 22.7% and NO pjacoco signal caught it. Only an
 * external diff against vanilla jacoco exposed the loss.
 *
 * <p>The transformer must still never break class loading (return null), but the failure must
 * now be a first-class signal: {@code Metrics.instrumentFailures} + a rate-limited WARN.
 */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class InstrumentFailureSignalTest {

    /** Mirrors the discovery: instrument() throwing a LinkageError (the ASM-clash shape). */
    static final class ThrowingInstrumenter extends Instrumenter {
        ThrowingInstrumenter() { super(new LoggerRuntime()); }
        @Override
        public byte[] instrument(byte[] buffer, String name) {
            throw new NoSuchMethodError("simulated ASM version clash");
        }
    }

    private static byte[] minimalClassBytes() throws Exception {
        try (java.io.InputStream in = InstrumentFailureSignalTest.class.getResourceAsStream(
                "/io/pjacoco/agent/probe/InstrumentFailureSignalTest.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in,
                    "own class bytes must be on the test classpath");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    @Test
    void instrumenterThrowableCountsAndWarnsButNeverBreaksLoading() throws Exception {
        Metrics metrics = new Metrics();
        ProbeInstrumentation.JacocoTransformer transformer = new ProbeInstrumentation.JacocoTransformer(
                new ThrowingInstrumenter(), AgentOptions.empty(), metrics, new AgentLog());

        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        byte[] result;
        try {
            System.setErr(new PrintStream(errBuf, true));
            result = transformer.transform(getClass().getClassLoader(), "com/example/App",
                    null, null, minimalClassBytes());
        } finally {
            System.setErr(err);
        }

        assertNull(result, "a failing Instrumenter must still never break class loading");
        assertEquals(1, metrics.instrumentFailures.get(),
                "an instrumentation failure must be counted, not swallowed silently");
        assertTrue(errBuf.toString().contains("failed to instrument com.example.App"),
                "the class that silently lost ALL coverage must be named in a WARN");
        assertTrue(metrics.summary().contains("instrumentFailures=1"),
                "the shutdown summary must surface instrumentation failures");
    }
}
