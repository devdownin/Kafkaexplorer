// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.TimesFmInferenceException.State.*;

/** Bounded internal adapter, deliberately not called by collection or public reads. */
public final class TimesFmClient implements AutoCloseable {
    public static final String MODEL_ID = "google/timesfm-2.5-200m-pytorch";
    public static final String MODEL_REVISION = "1d952420fba87f3c6dee4f240de0f1a0fbc790e3";
    public static final String ADAPTER_VERSION = "kex-timesfm-2.5-v1";
    private static final int MAX_REQUEST_BYTES = 128 * 1024, MAX_RESPONSE_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore slot = new Semaphore(1);
    private final MeterRegistry registry;
    private final Counter requests, busy, timeouts, unavailable, invalidOutput;
    private final Timer duration;

    public TimesFmClient(TimesFmInferenceProperties properties) {
        this(properties, Metrics.globalRegistry);
    }

    public TimesFmClient(TimesFmInferenceProperties properties, MeterRegistry registry) {
        endpoint = properties.validateEnabled();
        token = properties.getToken();
        timeout = properties.getTimeout();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        this.registry = registry;
        requests = Counter.builder("explorer_forecasting_requests_total").description("TimesFM calls").register(registry);
        busy = Counter.builder("explorer_forecasting_busy_total").description("TimesFM calls rejected while busy").register(registry);
        timeouts = Counter.builder("explorer_forecasting_timeouts_total").description("TimesFM calls that timed out").register(registry);
        unavailable = Counter.builder("explorer_forecasting_unavailable_total").description("TimesFM unavailable calls").register(registry);
        invalidOutput = Counter.builder("explorer_forecasting_invalid_output_total").description("TimesFM invalid responses").register(registry);
        duration = Timer.builder("explorer_forecasting_duration_seconds").description("TimesFM call duration")
            .publishPercentiles(0.5, 0.95).register(registry);
    }

    public List<MetricForecast> forecast(List<PreparedMetricSeries> contexts, int horizon) {
        var series = List.copyOf(contexts);
        validateInput(series, horizon);
        if (!slot.tryAcquire()) {
            busy.increment();
            throw new TimesFmInferenceException(BUSY);
        }
        Timer.Sample sample = Timer.start(registry);
        try {
            String requestId = UUID.randomUUID().toString();
            var inputs = series.stream().map(s -> Map.of("seriesId", s.seriesId(), "values",
                s.points().stream().map(PreparedMetricSeries.Point::value).toList())).toList();
            byte[] body = JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "requestId", requestId,
                "horizonPoints", horizon, "series", inputs));
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("TimesFM request limit exceeded");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var pending = http.sendAsync(request, response -> new BoundedBody(MAX_RESPONSE_BYTES));
            HttpResponse<byte[]> response;
            try {
                // Covers headers AND the entire bounded body, including a stalled chunked response.
                response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new TimesFmInferenceException(TIMEOUT);
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new TimesFmInferenceException(UNAVAILABLE);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimesFmInferenceException safe) throw safe;
                if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw new TimesFmInferenceException(TIMEOUT);
                throw new TimesFmInferenceException(UNAVAILABLE);
            }
            if (response.statusCode() != 200) {
                throw new TimesFmInferenceException(switch (response.statusCode()) {
                    case 429 -> BUSY;
                    case 502 -> INVALID_OUTPUT;
                    case 504 -> TIMEOUT;
                    default -> UNAVAILABLE;
                });
            }
            if (!response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().equals("application/json")) {
                throw new TimesFmInferenceException(INVALID_OUTPUT);
            }
            var result = decode(JSON.readTree(response.body()), requestId, series, horizon);
            requests.increment();
            return result;
        } catch (TimesFmInferenceException e) {
            switch (e.state()) {
                case TIMEOUT -> timeouts.increment();
                case UNAVAILABLE -> unavailable.increment();
                case INVALID_OUTPUT -> invalidOutput.increment();
                case BUSY -> busy.increment();
            }
            throw e;
        } catch (IllegalArgumentException e) {
            invalidOutput.increment();
            throw e;
        } catch (Exception e) {
            invalidOutput.increment();
            throw new TimesFmInferenceException(INVALID_OUTPUT);
        } finally {
            sample.stop(duration);
            slot.release();
        }
    }

    private static void validateInput(List<PreparedMetricSeries> contexts, int horizon) {
        if (contexts.isEmpty() || contexts.size() > 4 || horizon < 1 || horizon > 60) {
            throw new IllegalArgumentException("TimesFM requires 1–4 contexts and 1–60 horizon points");
        }
        var seen = new HashSet<String>();
        for (var s : contexts) {
            if (s.status() != PreparedMetricSeries.Status.READY || s.seriesId() == null
                || !s.seriesId().matches("[a-f0-9]{64}") || !seen.add(s.seriesId())
                || s.profile() == null || s.profile().contextPoints() != 512 || s.points().size() != 512
                || s.definitionVersion() == null || s.inputFingerprint() == null || s.profileFingerprint() == null
                || s.outputUnit() == null || s.outputUnit().isBlank() || "UNKNOWN".equals(s.outputUnit())
                || s.toExclusive() - s.fromInclusive() != 512 * s.profile().stepMillis()) {
                throw new IllegalArgumentException("TimesFM requires an admissible 512-point context");
            }
            for (int i = 0; i < 512; i++) {
                var p = s.points().get(i);
                long expected = Math.addExact(s.fromInclusive(), Math.multiplyExact(i + 1L, s.profile().stepMillis()));
                if (p.endAt() != expected || p.value() == null || !Double.isFinite(p.value()) || Math.abs(p.value()) > 1e30) {
                    throw new IllegalArgumentException("TimesFM requires finite, regularly spaced observations");
                }
            }
            Math.addExact(s.toExclusive(), Math.multiplyExact(horizon, s.profile().stepMillis()));
        }
    }

    private static List<MetricForecast> decode(JsonNode root, String requestId, List<PreparedMetricSeries> contexts, int horizon) {
        require(root != null && root.isObject() && root.path("schemaVersion").isIntegralNumber()
            && root.path("schemaVersion").asInt() == 1 && requestId.equals(root.path("requestId").textValue())
            && MODEL_ID.equals(root.path("modelId").textValue()) && MODEL_REVISION.equals(root.path("modelRevision").textValue())
            && ADAPTER_VERSION.equals(root.path("adapterVersion").textValue())
            && "MEDIAN".equals(root.path("centralStatistic").textValue())
            && root.path("durationMillis").isIntegralNumber() && root.path("durationMillis").canConvertToLong()
            && root.path("durationMillis").longValue() >= 0 && root.path("durationMillis").longValue() <= 30_000);
        JsonNode rows = root.path("series");
        require(rows.isArray() && rows.size() == contexts.size());
        var result = new ArrayList<MetricForecast>();
        for (int i = 0; i < contexts.size(); i++) {
            var context = contexts.get(i);
            JsonNode row = rows.get(i);
            require(context.seriesId().equals(row.path("seriesId").textValue()));
            for (String field : List.of("central", "q10", "q50", "q90")) {
                require(row.path(field).isArray() && row.path(field).size() == horizon);
            }
            var points = new ArrayList<MetricForecast.Point>();
            for (int p = 0; p < horizon; p++) {
                double central = number(row.path("central").get(p)), q10 = number(row.path("q10").get(p));
                double q50 = number(row.path("q50").get(p)), q90 = number(row.path("q90").get(p));
                require(q10 <= q50 && q50 <= q90 && central == q50);
                points.add(new MetricForecast.Point(Math.addExact(context.toExclusive(),
                    Math.multiplyExact(p + 1L, context.profile().stepMillis())), central, q10, q50, q90));
            }
            result.add(new MetricForecast(requestId, context.seriesId(), context.definitionVersion(),
                context.inputFingerprint(), context.profileFingerprint(), context.outputUnit(), context.toExclusive(),
                MODEL_ID, MODEL_REVISION, ADAPTER_VERSION, "MEDIAN", root.path("durationMillis").longValue(), points));
        }
        return List.copyOf(result);
    }

    private static double number(JsonNode node) {
        require(node != null && node.isNumber() && Double.isFinite(node.doubleValue()));
        return node.doubleValue();
    }
    private static void require(boolean value) {
        if (!value) throw new TimesFmInferenceException(INVALID_OUTPUT);
    }
    @Override public void close() { http.close(); }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new TimesFmInferenceException(INVALID_OUTPUT));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
