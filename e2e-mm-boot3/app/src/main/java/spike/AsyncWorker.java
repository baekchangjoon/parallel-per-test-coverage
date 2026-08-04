package spike;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Marker class covered ONLY on the @Async executor thread — its presence in the per-trace .exec proves S2. */
@Service
public class AsyncWorker {

    private final Tracer tracer;

    public AsyncWorker(Tracer tracer) {
        this.tracer = tracer;
    }

    // Straight-line (no ternary/if): deterministic per-line probe coverage for REQ-MM-012's
    // 100%-of-executed-lines assertion in Task 8 — a conditional here would leave some lines
    // input-dependent and non-deterministic across runs/inputs.
    @Async("appExecutor")
    public CompletableFuture<String> work(int inputCount) {
        int total = 0;
        for (int index = 0; index < inputCount; index++) {
            total += index * 5;
        }
        Span currentSpan = tracer.currentSpan();
        String asyncTraceId = currentSpan != null ? currentSpan.context().traceId() : "none";
        String threadName = Thread.currentThread().getName();
        return CompletableFuture.completedFuture(
                "async-total=" + total + ";asyncThreadTraceId=" + asyncTraceId + ";asyncThread=" + threadName);
    }
}
