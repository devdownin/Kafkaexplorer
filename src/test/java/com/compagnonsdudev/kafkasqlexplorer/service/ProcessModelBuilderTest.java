// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The measured half of Process Mining.
 *
 * <p>Everything here used to be asked of the model, from a sample drawn per topic that could not
 * support the question — so these are the assertions that were previously made by reading an answer
 * and hoping. They are ordinary counting now, which is the point: a directly-follows graph either
 * has the edge or it does not.
 */
class ProcessModelBuilderTest {

    private static final PayloadDigestService DIGESTS =
        new PayloadDigestService(new ProcessMiningConfig());
    /** The paths the mapping declares, in the digest's own normalized form. */
    private static final Set<String> MAPPED = Set.of("id", "at", "status");
    /**
     * 2026-01-01T00:00:00Z in epoch millis. Business timestamps are written relative to it rather
     * than from zero, because {@code EventTime} reads anything below 10^10 as epoch *seconds* — so
     * an {@code "at": 1000} is the year 1970 and a latency of one second becomes one thousand.
     * The rule is right; small round numbers are what is wrong as a fixture.
     */
    private static final long T0 = 1_767_225_600_000L;

    private final ProcessModelBuilder builder = new ProcessModelBuilder(new ProcessMiningConfig());

    /** One record, digested exactly as the snapshot reader digests it. */
    private static PayloadDigest event(String topic, long offset, long recordTs, String json) {
        return DIGESTS.digest(topic, 0, offset, recordTs, "k" + offset,
            json.getBytes(StandardCharsets.UTF_8), MAPPED);
    }

    /** {@code {"id": "...", "at": <epochMillis>}}, dated {@code afterT0} millis into the window. */
    private static PayloadDigest event(String topic, long offset, String caseId, long afterT0) {
        long at = T0 + afterT0;
        return event(topic, offset, at, "{\"id\":\"" + caseId + "\",\"at\":" + at + "}");
    }

    private static FieldMapping mapping(String... topics) {
        return mapping(false, topics);
    }

    private static FieldMapping mapping(boolean withStatus, String... topics) {
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> times = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (String topic : topics) {
            ids.put(topic, "$.id");
            times.put(topic, "$.at");
            if (withStatus) {
                statuses.put(topic, "$.status");
            }
        }
        return new FieldMapping("m1", ids, times, statuses, null);
    }

    private static ProcessModel.Edge edge(ProcessModel model, String from, String to) {
        return model.edges().stream()
            .filter(e -> e.from().equals(from) && e.to().equals(to))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no edge " + from + " -> " + to
                + " in " + model.edges()));
    }

    // ─────────────────────────────────────────────────────── not available is a state

    /**
     * The rule this whole record was written for. Without a case id there is no event log, and a
     * process with zero cases is a different claim from "we could not group these records" — the
     * first sends an operator to the cluster, the second to the mapping step.
     */
    @Test
    void withoutAMappingThereIsNoProcessRatherThanAnEmptyOne() {
        ProcessModel model = builder.build(
            List.of(event("orders", 1, "ORD-1", 1_000L)), null);

        assertFalse(model.available());
        assertTrue(model.unavailableReason().contains("correlation id"), model.unavailableReason());
        assertEquals(0, model.cases());
        assertEquals(1, model.eventsWithoutCase(), "the records were read — they are just not a log");
    }

    /** A mapping that points at a path no payload carries is the same kind of nothing. */
    @Test
    void aMappedPathNoRecordCarriesIsReportedRatherThanSilentlyEmpty() {
        FieldMapping wrongPath = new FieldMapping("m1",
            Map.of("orders", "$.orderReference"), Map.of(), Map.of(), null);

        ProcessModel model = builder.build(
            List.of(event("orders", 1, "ORD-1", 1_000L)), wrongPath);

        assertFalse(model.available());
        assertTrue(model.unavailableReason().contains("no record carried a value"),
            model.unavailableReason());
        assertEquals(1, model.eventsWithoutCase());
    }

    /** Records with no correlation id sit outside the log and are counted, never dropped in silence. */
    @Test
    void aRecordWithNoCaseIdIsCountedOutsideTheLog() {
        List<PayloadDigest> digests = List.of(
            event("orders", 1, "ORD-1", 1_000L),
            event("orders", 2, T0 + 2_000L, "{\"unrelated\":true}"));

        ProcessModel model = builder.build(digests, mapping("orders"));

        assertTrue(model.available());
        assertEquals(1, model.events());
        assertEquals(1, model.eventsWithoutCase());
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("outside this log")),
            model.notes().toString());
    }

    // ─────────────────────────────────────────────────────── the graph

    /**
     * The directly-follows graph, counted across topics.
     *
     * <p>This is the case the old sampling could not see at all: two topics, and the question is
     * whether a given case appears in both. Drawing sixty messages per topic on offset order left
     * that to chance.
     */
    @Test
    void transitionsAreCountedAcrossTopicsPerCase() {
        List<PayloadDigest> digests = List.of(
            event("received", 1, "ORD-1", 0L),
            event("validated", 1, "ORD-1", 1_000L),
            event("enriched", 1, "ORD-1", 3_000L),
            event("received", 2, "ORD-2", 100L),
            event("validated", 2, "ORD-2", 1_600L));

        ProcessModel model = builder.build(digests, mapping("received", "validated", "enriched"));

        assertTrue(model.available());
        assertEquals(2, model.cases());
        assertEquals(5, model.events());
        assertEquals(2, edge(model, "received", "validated").occurrences());
        assertEquals(2, edge(model, "received", "validated").cases());
        assertEquals(1, edge(model, "validated", "enriched").occurrences());
        assertEquals(T0, model.windowStartMs());
        assertEquals(T0 + 3_000L, model.windowEndMs());
    }

    /** Latency per transition, which the prompt used to ask a model to eyeball from a sample. */
    @Test
    void everyTransitionCarriesItsLatencyDistribution() {
        List<PayloadDigest> digests = new ArrayList<>();
        long[] latencies = {1_000L, 2_000L, 3_000L};
        for (int i = 0; i < latencies.length; i++) {
            digests.add(event("received", i, "ORD-" + i, 0L));
            digests.add(event("validated", i, "ORD-" + i, latencies[i]));
        }

        ProcessModel.Edge measured =
            edge(builder.build(digests, mapping("received", "validated")), "received", "validated");

        assertEquals(3, measured.occurrences());
        assertEquals(2_000L, measured.p50Ms(), "nearest-rank median of 1s/2s/3s");
        assertEquals(3_000L, measured.p95Ms());
        assertEquals(3_000L, measured.maxMs());
    }

    /**
     * Two clocks disagreeing is a finding, not a glitch to smooth away.
     *
     * <p>The log is sorted by event time, so a backwards hop can never surface as a negative
     * duration — what it surfaces as is the broker having seen the pair in the opposite order.
     * Here {@code validated} carries the later business timestamp and the earlier record timestamp,
     * which is what a skewed producer or a back-dated event looks like.
     */
    @Test
    void aPairProducedInTheOppositeOrderIsCountedNotHidden() {
        List<PayloadDigest> digests = List.of(
            event("received", 1, T0 + 9_000L, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"),
            event("validated", 2, T0 + 5_000L, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 2_000) + "}"));

        ProcessModel.Edge measured =
            edge(builder.build(digests, mapping("received", "validated")), "received", "validated");

        assertEquals(1, measured.occurrences());
        assertEquals(1_000L, measured.maxMs(), "the business-time gap is what the latency measures");
        assertEquals(1, measured.outOfOrderCount());
    }

    // ─────────────────────────────────────────────────────── variants and endpoints

    /** Variants separate the nominal path from the deviation — the finding on most real pipelines. */
    @Test
    void variantsCountTheDistinctPathsAndNameAnExample() {
        List<PayloadDigest> digests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            digests.add(event("received", i, "OK-" + i, 0L));
            digests.add(event("validated", i, "OK-" + i, 1_000L));
        }
        digests.add(event("received", 99, "SKIP-1", 0L));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(2, model.variants().size());
        ProcessModel.Variant nominal = model.variants().get(0);
        assertEquals(List.of("received", "validated"), nominal.path());
        assertEquals(5, nominal.cases());
        assertTrue(nominal.example().startsWith("OK-"), nominal.example());
        assertEquals("SKIP-1", model.variants().get(1).example());
    }

    /**
     * How cases ended is reported as a distribution, and nothing is called an orphan.
     *
     * <p>Which activity ought to end a process is a business fact this application does not have,
     * and deriving it from the data is circular. The reading is the model's half of the work; the
     * counting is this one's.
     */
    @Test
    void endActivitiesAreADistributionRatherThanAVerdict() {
        List<PayloadDigest> digests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            digests.add(event("received", i, "OK-" + i, 0L));
            digests.add(event("validated", i, "OK-" + i, 1_000L));
        }
        digests.add(event("received", 99, "STUCK-1", 0L));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(List.of("received"), model.starts().stream()
            .map(ProcessModel.Endpoint::activity).toList());
        assertEquals(4, model.starts().get(0).cases());
        assertEquals(3, model.ends().get(0).cases());
        assertEquals("validated", model.ends().get(0).activity());
        assertEquals("received", model.ends().get(1).activity());
        assertEquals(1, model.ends().get(1).cases());
    }

    /** The same (case, activity) twice — redelivery or rework, named for what was observed. */
    @Test
    void anActivityACaseVisitedTwiceIsReported() {
        List<PayloadDigest> digests = List.of(
            event("received", 1, "ORD-1", 0L),
            event("received", 2, "ORD-1", 500L),
            event("validated", 3, "ORD-1", 1_000L));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(1, model.repeats().size());
        assertEquals("received", model.repeats().get(0).activity());
        assertEquals(1, model.repeats().get(0).casesAffected());
        assertEquals(2, model.repeats().get(0).maxOccurrencesInOneCase());
    }

    // ─────────────────────────────────────────────────────── activities and the status

    /** A mapped status is a deliberate statement that the status is what moves. */
    @Test
    void aMappedStatusRefinesTheActivity() {
        List<PayloadDigest> digests = List.of(
            event("events", 1, T0, "{\"id\":\"ORD-1\",\"at\":" + T0 + ",\"status\":\"received\"}"),
            event("events", 2, T0 + 1_000L,
                "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + ",\"status\":\"SHIPPED\"}"));

        ProcessModel model = builder.build(digests, mapping(true, "events"));

        assertEquals(2, model.activities().size());
        assertNotNull(edge(model, "events · RECEIVED", "events · SHIPPED"),
            "case is normalised, so `received` and `RECEIVED` are one step");
    }

    /**
     * A field with a hundred distinct values is not a status.
     *
     * <p>Multiplying it into the graph turns a readable flowchart into a hairball, so past the cap
     * the topic is its own activity again — and the model is told, because a silent collapse would
     * present a graph with no internal transitions as the process itself.
     */
    @Test
    void aStatusWithTooManyValuesFallsBackToTheTopicAndSaysSo() {
        ProcessMiningConfig tight = new ProcessMiningConfig();
        tight.setMaxStatusActivities(2);
        List<PayloadDigest> digests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            digests.add(event("events", i, T0 + i * 1_000L,
                "{\"id\":\"ORD-1\",\"at\":" + (T0 + i * 1_000) + ",\"status\":\"S" + i + "\"}"));
        }

        ProcessModel model = new ProcessModelBuilder(tight).build(digests, mapping(true, "events"));

        assertEquals(List.of("events"), model.activities().stream()
            .map(ProcessModel.Activity::name).toList());
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("too many distinct values")),
            model.notes().toString());
    }

    // ─────────────────────────────────────────────────────── which clock

    /** A mapped business timestamp orders the log, and the log says which clock it used. */
    @Test
    void theBusinessTimestampOrdersTheLogAheadOfTheBrokerClock() {
        // Record timestamps run backwards against the business ones, so ordering by the wrong clock
        // would reverse the edge.
        List<PayloadDigest> digests = List.of(
            event("received", 1, T0 + 9_000L, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"),
            event("validated", 2, T0 + 8_000L, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 2_000) + "}"));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(ProcessModel.TimeSource.MAPPED_FIELD, model.eventTimeSource());
        assertEquals(1, edge(model, "received", "validated").occurrences());
    }

    /** With no timestamp mapped the broker's clock is what is left — and that changes the meaning. */
    @Test
    void aRunWithNoMappedTimestampSaysItIsMeasuringProduceTime() {
        FieldMapping idOnly = new FieldMapping("m1",
            Map.of("received", "$.id", "validated", "$.id"), Map.of(), Map.of(), null);

        ProcessModel model = builder.build(List.of(
            event("received", 1, T0 + 1_000L, "{\"id\":\"ORD-1\"}"),
            event("validated", 2, T0 + 4_000L, "{\"id\":\"ORD-1\"}")), idOnly);

        assertEquals(ProcessModel.TimeSource.RECORD_TIMESTAMP, model.eventTimeSource());
        assertEquals(3_000L, edge(model, "received", "validated").maxMs());
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("produce time")),
            model.notes().toString());
    }

    /** Falling back for some events only is its own answer, and it is stated. */
    @Test
    void aPartialFallbackOntoTheBrokerClockIsReported() {
        List<PayloadDigest> digests = List.of(
            event("received", 1, T0 + 1_000L, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"),
            event("validated", 2, T0 + 4_000L, "{\"id\":\"ORD-1\"}"));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(ProcessModel.TimeSource.MIXED, model.eventTimeSource());
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("fell back")),
            model.notes().toString());
    }

    /** An ISO-8601 business timestamp resolves like an epoch one — see {@code EventTime}. */
    @Test
    void anIsoTimestampIsResolvedLikeAnEpochOne() {
        List<PayloadDigest> digests = List.of(
            event("received", 1, T0, "{\"id\":\"ORD-1\",\"at\":\"2026-01-01T00:00:00Z\"}"),
            event("validated", 2, T0, "{\"id\":\"ORD-1\",\"at\":\"2026-01-01T00:00:30Z\"}"));

        ProcessModel model = builder.build(digests, mapping("received", "validated"));

        assertEquals(ProcessModel.TimeSource.MAPPED_FIELD, model.eventTimeSource());
        assertEquals(30_000L, edge(model, "received", "validated").maxMs());
    }

    // ─────────────────────────────────────────────────────── W2: the worked examples

    /**
     * The examples the prompt inlines are chosen to be different from one another.
     *
     * <p>Ranking variants by frequency and taking the top N drops exactly what an audit looks for:
     * the nominal path is the one everybody already knows, and the deviation four cases in nine
     * hundred took is the finding. So the selection takes from both ends, and the spotlight follows
     * it.
     */
    @Test
    void spotlightCasesComeFromBothEndsOfTheVariantDistribution() {
        ProcessMiningConfig tight = new ProcessMiningConfig();
        tight.setMaxVariantsInPrompt(2);
        tight.setMaxTraceCasesInPrompt(2);

        List<PayloadDigest> digests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {                      // the nominal path, 10 cases
            digests.add(event("received", i, "OK-" + i, 0L));
            digests.add(event("validated", i, "OK-" + i, 1_000L));
        }
        for (int i = 0; i < 4; i++) {                       // a middling deviation, 4 cases
            digests.add(event("received", 100 + i, "MID-" + i, 0L));
        }
        digests.add(event("received", 200, "RARE-1", 0L));  // the rarity, 1 case
        digests.add(event("validated", 201, "RARE-1", 1_000L));
        digests.add(event("enriched", 202, "RARE-1", 2_000L));

        ProcessModel model = new ProcessModelBuilder(tight)
            .build(digests, mapping("received", "validated", "enriched"));

        assertEquals(2, model.spotlightCases().size());
        assertTrue(model.spotlightCases().get(0).startsWith("OK-"),
            "the most frequent path is one of the examples: " + model.spotlightCases());
        assertEquals("RARE-1", model.spotlightCases().get(1),
            "and so is the rarest, which a top-N cut would have dropped");
        assertEquals(1, model.variantsOmitted(), "the variant left out is counted, not hidden");
    }

    // ─────────────────────────────────────────────────────── reproducibility

    /**
     * Two runs over one window must draw the same graph.
     *
     * <p>A burst sharing a millisecond is ordinary on a live topic, and ordering it by timestamp
     * alone leaves the sort to chance — which is the defect the Stream Flow chain rule was written
     * for. Partition and offset break the tie.
     */
    @Test
    void anIdenticalWindowProducesAnIdenticalModel() {
        List<PayloadDigest> digests = List.of(
            event("received", 3, T0, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"),
            event("validated", 1, T0, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"),
            event("enriched", 2, T0, "{\"id\":\"ORD-1\",\"at\":" + (T0 + 1_000) + "}"));

        ProcessModel first = builder.build(digests, mapping("received", "validated", "enriched"));
        ProcessModel again = builder.build(new ArrayList<>(digests).reversed(),
            mapping("received", "validated", "enriched"));

        assertEquals(first.variants().get(0).path(), again.variants().get(0).path());
        assertEquals(first.edges().size(), again.edges().size());
    }

    /** The caps are reported, because a truncated graph that looks whole is the worse failure. */
    @Test
    void whatTheCapDroppedIsCounted() {
        ProcessMiningConfig tight = new ProcessMiningConfig();
        tight.setMaxEdgesInPrompt(1);
        List<PayloadDigest> digests = List.of(
            event("a", 1, "ORD-1", 0L),
            event("b", 2, "ORD-1", 1_000L),
            event("c", 3, "ORD-1", 2_000L));

        ProcessModel model = new ProcessModelBuilder(tight).build(digests, mapping("a", "b", "c"));

        assertEquals(1, model.edges().size());
        assertEquals(1, model.edgesOmitted());
    }
}
