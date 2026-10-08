// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static com.compagnonsdudev.kafkasqlexplorer.forecast.TimesFmInferenceException.State.*;

class TimesFmClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "contract-test-token-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    private HttpServer server;
    private TimesFmClient client;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> captured = new AtomicReference<>();
    private final AtomicReference<String> capturedToken = new AtomicReference<>();
    private Consumer<ObjectNode> mutate = root -> {};
    private SimpleMeterRegistry meters;
    private int status = 200;
    private byte[] rawResponse;
    private String contentType = "application/json";
    private boolean stalled;
    private final CountDownLatch headersSent = new CountDownLatch(1);
    private final CountDownLatch unblock = new CountDownLatch(1);

    @ParameterizedTest
    @ValueSource(ints = {200, 503, 302})
    void readinessUsesOnlyHealthAndNeverFollowsRedirects(int responseStatus) throws Exception {
        start(Duration.ofSeconds(1));
        server.createContext("/health/ready", exchange -> {
            exchange.getResponseHeaders().set("Location", "/v1/forecast");
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        assertEquals(responseStatus == 200, client.isReady());
        assertEquals(0, calls.get());
        assertEquals(0, meters.get("explorer_forecasting_requests_total").counter().count());
    }

    @AfterEach void close() {
        unblock.countDown();
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (meters != null) meters.close();
    }

    private void start(Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/forecast", exchange -> {
            calls.incrementAndGet();
            var request = JSON.readTree(exchange.getRequestBody());
            captured.set(request);
            capturedToken.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var root = JSON.createObjectNode();
            root.put("schemaVersion", 1).put("requestId", request.path("requestId").textValue())
                .put("modelId", "google/timesfm-2.5-200m-pytorch")
                .put("modelRevision", "1d952420fba87f3c6dee4f240de0f1a0fbc790e3")
                .put("adapterVersion", TimesFmClient.ADAPTER_VERSION).put("centralStatistic", "MEDIAN")
                .put("durationMillis", 17);
            var rows = root.putArray("series");
            for (var input : request.path("series")) {
                var row = rows.addObject().put("seriesId", input.path("seriesId").textValue());
                for (String field : List.of("central", "q10", "q50", "q90")) {
                    var values = row.putArray(field);
                    for (int p = 0; p < request.path("horizonPoints").asInt(); p++) {
                        values.add(field.equals("q10") ? 1 : field.equals("q90") ? 9 : 5);
                    }
                }
            }
            mutate.accept(root);
            byte[] body = rawResponse != null ? rawResponse : JSON.writeValueAsBytes(root);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, stalled ? 0 : body.length);
            headersSent.countDown();
            if (stalled) {
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { unblock.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        var properties = new TimesFmInferenceProperties();
        properties.setServiceUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken(TOKEN);
        properties.setTimeout(timeout);
        meters = new SimpleMeterRegistry();
        client = new TimesFmClient(properties, meters);
    }

    static PreparedMetricSeries context(String id, PreparedMetricSeries.Status status) {
        var profile = SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_LAST);
        var points = new ArrayList<PreparedMetricSeries.Point>();
        for (int i = 1; i <= 512; i++) points.add(new PreparedMetricSeries.Point(i * 60_000L, (double) i, false, 2));
        return new PreparedMetricSeries(status, "", id.repeat(64), "definition", "messages", "messages", 0,
            512 * 60_000L, profile, "policy", "input", 512, 0, 0, points);
    }

    @Test void realHttpContractPreservesProvenanceAndForecastsTheNextBucket() throws Exception {
        start(Duration.ofSeconds(2));
        var result = client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY),
            context("b", PreparedMetricSeries.Status.READY)), 2);
        assertEquals(2, result.size());
        assertEquals("b".repeat(64), result.get(1).seriesId());
        var forecast = result.getFirst();
        assertEquals(513 * 60_000L, forecast.points().getFirst().at());
        assertEquals(514 * 60_000L, forecast.points().get(1).at());
        assertEquals(5, forecast.points().getFirst().central());
        assertEquals(1, forecast.points().getFirst().q10());
        assertEquals(9, forecast.points().getFirst().q90());
        assertEquals("MEDIAN", forecast.centralStatistic());
        assertEquals("input", forecast.inputFingerprint());
        assertEquals("policy", forecast.profileFingerprint());
        assertEquals(1.0, meters.get("explorer_forecasting_requests_total").counter().count());
        assertEquals(1.0, meters.get("explorer_forecasting_duration_seconds").timer().count());
        assertEquals("definition", forecast.definitionVersion());
        assertEquals("messages", forecast.outputUnit());
        assertEquals("Bearer " + TOKEN, capturedToken.get());
        assertEquals(512, captured.get().path("series").get(0).path("values").size());
        assertEquals(2, captured.get().path("series").get(0).size());
    }

    @ParameterizedTest @ValueSource(strings = {"requestId", "modelId", "modelRevision", "adapterVersion", "centralStatistic", "schemaVersion", "durationMillis", "seriesId", "shape", "text", "crossed", "median", "missing_series"})
    void rejectsInvalidRemoteContract(String defect) throws Exception {
        mutate = root -> {
            if (List.of("requestId", "modelId", "modelRevision", "adapterVersion", "centralStatistic").contains(defect)) root.put(defect, "wrong");
            else if (defect.equals("schemaVersion")) root.put("schemaVersion", 2);
            else if (defect.equals("durationMillis")) root.put("durationMillis", -1);
            else if (defect.equals("missing_series")) root.putArray("series");
            else {
                var row = (ObjectNode) root.path("series").get(0);
                switch (defect) {
                    case "seriesId" -> row.put("seriesId", "b".repeat(64));
                    case "shape" -> row.putArray("q10").add(1);
                    case "text" -> row.putArray("q10").add("1").add("1");
                    case "crossed" -> row.putArray("q10").add(10).add(10);
                    case "median" -> row.putArray("central").add(4).add(4);
                }
            }
        };
        start(Duration.ofSeconds(2));
        var error = assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2));
        assertEquals(INVALID_OUTPUT, error.state());
        assertFalse(error.getMessage().contains(TOKEN));
    }

    @Test void rejectsOversizedResponseBeforeDeserialization() throws Exception {
        rawResponse = new byte[256 * 1024 + 1];
        start(Duration.ofSeconds(2));
        assertEquals(INVALID_OUTPUT, assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2)).state());
    }

    @Test void timesOutTheEntireBodyAndRefusesASecondConcurrentCall() throws Exception {
        stalled = true;
        start(Duration.ofSeconds(1));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2)));
        assertTrue(headersSent.await(2, TimeUnit.SECONDS));
        assertEquals(BUSY, assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("b", PreparedMetricSeries.Status.READY)), 2)).state());
        assertEquals(TIMEOUT, first.get(3, TimeUnit.SECONDS).state());
        assertEquals(1, calls.get());
        assertEquals(1.0, meters.get("explorer_forecasting_busy_total").counter().count());
        assertEquals(1.0, meters.get("explorer_forecasting_timeouts_total").counter().count());
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 429, 502, 503, 504})
    void mapsSafeDependencyStatesWithoutReturningRemoteBody(int code) throws Exception {
        status = code;
        rawResponse = "sensitive remote error".getBytes(StandardCharsets.UTF_8);
        start(Duration.ofSeconds(2));
        var error = assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2));
        assertEquals(code == 429 ? BUSY : code == 502 ? INVALID_OUTPUT : code == 504 ? TIMEOUT : UNAVAILABLE, error.state());
        assertFalse(error.getMessage().contains("sensitive"));
    }

    @Test void rejectsNonJsonSuccessAndMalformedJson() throws Exception {
        contentType = "text/html";
        start(Duration.ofSeconds(2));
        assertEquals(INVALID_OUTPUT, assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2)).state());
        contentType = "application/json";
        rawResponse = "{".getBytes(StandardCharsets.UTF_8);
        assertEquals(INVALID_OUTPUT, assertThrows(TimesFmInferenceException.class,
            () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.READY)), 2)).state());
    }

    /** The last {@code n} points of a full context, as the preparer cuts one still filling. */
    static PreparedMetricSeries partial(String id, int n) {
        var full = context(id, PreparedMetricSeries.Status.READY);
        long from = (512 - n) * 60_000L;
        return new PreparedMetricSeries(full.status(), "", full.seriesId(), "definition", "messages", "messages", from,
            full.toExclusive(), full.profile(), "policy", "input", n, 0, 0, full.points().subList(512 - n, 512));
    }

    @Test void aPartialContextOfAtLeastTheFloorIsSentWhole() throws Exception {
        start(Duration.ofSeconds(2));
        var forecast = client.forecast(List.of(partial("a", SeriesPreparationProfile.MIN_CONTEXT_POINTS)), 2).getFirst();
        assertEquals(SeriesPreparationProfile.MIN_CONTEXT_POINTS, captured.get().path("series").get(0).path("values").size());
        assertEquals(513 * 60_000L, forecast.points().getFirst().at());
        assertThrows(IllegalArgumentException.class,
            () -> client.forecast(List.of(partial("a", SeriesPreparationProfile.MIN_CONTEXT_POINTS - 1)), 2));
        assertEquals(1, calls.get());
    }

    @Test void refusesInadmissibleHistoryBeforeAnyHttpCall() throws Exception {
        start(Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> client.forecast(List.of(context("a", PreparedMetricSeries.Status.WARMING_UP)), 2));
        var series = context("a", PreparedMetricSeries.Status.READY);
        assertThrows(IllegalArgumentException.class, () -> client.forecast(List.of(series, series), 2));
        assertThrows(IllegalArgumentException.class, () -> client.forecast(List.of(series), 61));
        assertEquals(0, calls.get());
    }
}
