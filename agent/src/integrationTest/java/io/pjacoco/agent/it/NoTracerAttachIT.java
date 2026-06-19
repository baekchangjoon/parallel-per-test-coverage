package io.pjacoco.agent.it;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjacoco.agent.trace.BraveTestIdSource;
import io.pjacoco.agent.trace.CoverageKeyResolver;
import io.pjacoco.agent.trace.OtelTestIdSource;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * REQ-002 / REQ-007 runtime guard: the agent's trace-id sources degrade gracefully to
 * {@code null} when no tracer context is active, and a {@link CoverageKeyResolver} over both
 * sources also returns {@code null} (fallback to the local/baggage path).
 *
 * <p>Notes on classpath state during this test:
 * <ul>
 *   <li>OTel ({@code io.opentelemetry.*}) is NOT on the integrationTest classpath —
 *       {@link OtelTestIdSource} hits {@code Class.forName} failure → returns {@code null}.
 *   <li>Brave IS on the integrationTest classpath ({@code integrationTestImplementation} dep),
 *       but no {@code Tracing} instance has been registered, so
 *       {@code brave.Tracing.current()} returns {@code null} →
 *       {@link BraveTestIdSource} returns {@code null} without throwing.
 * </ul>
 *
 * <p>This test does NOT spin up Jetty or install ByteBuddy advice — the full servlet
 * no-tracer path is already exercised end-to-end by {@code SpecAcceptanceE2E} / the e2e
 * Gradle task. This IT focuses on the trace-source layer in isolation.
 */
class NoTracerAttachIT {

    /**
     * OtelTestIdSource returns null (not throws) when OTel classes are absent from the
     * classpath (Class.forName fails → best-effort catch → null).
     */
    @Test
    void otelSourceReturnsNullWhenOtelAbsent() {
        OtelTestIdSource source = new OtelTestIdSource();
        String key = source.currentKey();
        assertNull(key,
                "OtelTestIdSource.currentKey() must return null when OTel is not present "
                        + "(REQ-002: no hard runtime dependency on OTel)");
    }

    /**
     * BraveTestIdSource returns null (not throws) when Brave is on the classpath but no
     * Tracing instance is current (brave.Tracing.current() == null → resolveTraceId returns null).
     */
    @Test
    void braveSourceReturnsNullWhenNoCurrentTracing() {
        BraveTestIdSource source = new BraveTestIdSource();
        String key = source.currentKey();
        assertNull(key,
                "BraveTestIdSource.currentKey() must return null when no Brave Tracing is active "
                        + "(REQ-002: graceful no-tracer degradation)");
    }

    /**
     * A CoverageKeyResolver over [OtelTestIdSource, BraveTestIdSource] returns null when
     * neither tracer has an active context — activation falls back to the local/baggage path.
     */
    @Test
    void resolverReturnsNullWhenNoTracerContextActive() {
        CoverageKeyResolver resolver = new CoverageKeyResolver(
                Arrays.<io.pjacoco.agent.trace.TestIdSource>asList(
                        new OtelTestIdSource(),
                        new BraveTestIdSource()));
        String key = resolver.resolve();
        assertNull(key,
                "CoverageKeyResolver must return null when no tracer context is active, "
                        + "so the agent can fall back to local/baggage activation (REQ-007)");
    }
}
