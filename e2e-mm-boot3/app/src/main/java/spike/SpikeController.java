package spike;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class SpikeController {

    private final Tracer tracer;
    private final SyncWorker syncWorker;
    private final AsyncWorker asyncWorker;
    private final DownstreamWorker downstreamWorker;
    private final RestTemplate restTemplate;
    private final Environment environment;
    // Task 9's 2-hop distributed E2E injects instance B's base URL here; default (blank) means
    // "call self" (unchanged single-instance behavior).
    private final String downstreamBaseUrl;

    public SpikeController(Tracer tracer, SyncWorker syncWorker, AsyncWorker asyncWorker,
                           DownstreamWorker downstreamWorker, RestTemplate restTemplate,
                           Environment environment,
                           @Value("${downstream.base-url:}") String downstreamBaseUrl) {
        this.tracer = tracer;
        this.syncWorker = syncWorker;
        this.asyncWorker = asyncWorker;
        this.downstreamWorker = downstreamWorker;
        this.restTemplate = restTemplate;
        this.environment = environment;
        this.downstreamBaseUrl = downstreamBaseUrl;
    }

    private String requestTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan != null ? currentSpan.context().traceId() : "none";
    }

    @GetMapping("/sync")
    public String sync() {
        String workResult = syncWorker.work(10);
        return "requestTraceId=" + requestTraceId() + ";" + workResult;
    }

    @GetMapping("/async")
    public String async() {
        String requestThreadTraceId = requestTraceId();
        String asyncResult = asyncWorker.work(10).join();
        return "requestTraceId=" + requestThreadTraceId + ";" + asyncResult;
    }

    @GetMapping("/call-downstream")
    public String callDownstream() {
        String markResult = downstreamWorker.mark(10);
        String sinkResponse = restTemplate.getForObject(downstreamBase() + "/sink", String.class);
        return "requestTraceId=" + requestTraceId() + ";" + markResult + ";sink=" + sinkResponse;
    }

    private String downstreamBase() {
        if (downstreamBaseUrl != null && !downstreamBaseUrl.isBlank()) {
            return downstreamBaseUrl;
        }
        String port = environment.getProperty("local.server.port", "0");
        return "http://127.0.0.1:" + port;
    }

    @GetMapping("/sink")
    public String sink(HttpServletRequest request) throws IOException {
        StringBuilder capturedHeaders = new StringBuilder("--- sink request headers ---\n");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            capturedHeaders.append(name).append(": ").append(request.getHeader(name)).append('\n');
        }
        Path sinkFile = Paths.get(System.getProperty("spike.sink.file", "sink-headers.txt"));
        Files.writeString(sinkFile, capturedHeaders.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "sink-ok;sinkTraceId=" + requestTraceId();
    }

    @GetMapping("/baggage-debug")
    public String baggageDebug() {
        // Micrometer facade view.
        String micrometerView;
        try {
            Map<String, String> allBaggage = tracer.getAllBaggage();
            micrometerView = String.valueOf(allBaggage);
        } catch (Throwable t) {
            micrometerView = "error:" + t;
        }
        // Raw Brave view.
        String braveView;
        try {
            brave.Tracing braveTracing = brave.Tracing.current();
            brave.propagation.TraceContext braveContext =
                    braveTracing != null ? braveTracing.currentTraceContext().get() : null;
            brave.baggage.BaggageField field = brave.baggage.BaggageField.getByName(braveContext, "test.id");
            braveView = field != null ? String.valueOf(field.getValue(braveContext)) : "no-field";
        } catch (Throwable t) {
            braveView = "error:" + t;
        }
        return "requestTraceId=" + requestTraceId()
                + ";micrometerBaggage=" + micrometerView
                + ";braveBaggage[test.id]=" + braveView;
    }
}
