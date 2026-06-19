package io.pjacoco.spike.otel;

import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.ContextStorageProvider;

/**
 * SPIKE-ONLY OTel javaagent extension for GA-1 (OpenTelemetry backend).
 *
 * <p>Central question: with the OTel javaagent attached, can a {@link ContextStorageProvider}
 * extension's {@link ContextStorage} observe the trace context becoming current (attach/detach)
 * on EVERY thread the tracer propagates to — the request thread AND any executor/@Async handoff
 * thread — so pjacoco can bind a coverage key = traceId?
 *
 * <p>This provider is compiled against the public OTel API ({@code io.opentelemetry.context.*},
 * declared {@code compileOnly} — those classes are supplied at runtime by the application's own
 * {@code opentelemetry-api} dependency). It is packaged with
 * {@code META-INF/services/io.opentelemetry.context.ContextStorageProvider} and loaded via
 * {@code -Dotel.javaagent.extensions=<this-jar>}.
 *
 * <p>The application-side {@code io.opentelemetry.context.LazyStorage.get()} discovers this provider
 * through {@code ServiceLoader} and calls {@link #get()} exactly once. The OTel javaagent's
 * {@code ContextStorageWrappersInstrumentation} additionally injects {@code AgentContextStorage.wrap()}
 * so the agent's span context flows through whatever storage we return — meaning a valid
 * {@code SpanContext} is readable from the {@code Context} passed to {@link DelegatingContextStorage#attach}.
 */
public final class SpikeContextStorageProvider implements ContextStorageProvider {

    @Override
    public ContextStorage get() {
        System.err.println("[OTEL-SPIKE] SpikeContextStorageProvider.get() called -> installing DelegatingContextStorage"
                + " (classloader=" + classLoaderName(getClass().getClassLoader()) + ")");
        return new DelegatingContextStorage(ContextStorage.defaultStorage());
    }

    static String classLoaderName(ClassLoader cl) {
        if (cl == null) {
            return "bootstrap";
        }
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }
}
