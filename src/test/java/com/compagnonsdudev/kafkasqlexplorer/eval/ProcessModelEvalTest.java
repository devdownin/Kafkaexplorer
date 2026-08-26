// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;
import com.compagnonsdudev.kafkasqlexplorer.service.ProcessModelBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic half of the Process Mining eval — the aggregate, asserted exactly.
 *
 * <p>Nothing measured whether the prompt work of W1–W3 does what it claims, and the failure mode is
 * silent by construction: a plausible answer about an invented pipeline reads exactly like a
 * correct one. The way to tell them apart is a dataset whose right answer is known, and
 * {@code setup-demo.sh} seeds one.
 *
 * <p>This half needs no model at all, so it is an ordinary unit test and runs in {@code mvn verify}.
 * {@link ProcessModelBuilder} is pure, so the numbers below are not a sample of behaviour — they
 * <em>are</em> the behaviour, and every one of them is a fact about what the seeder writes rather
 * than a figure copied out of a run. {@code LlmAnalysisEvalTest} is the other half: it asks a real
 * model to read this same aggregate, asserts loosely, and is excluded from the build.
 *
 * <p>Writing it found a defect, which is the whole argument for having it. See
 * {@code aTruncatedRecordIsNotACaseThatEndedEarly}.
 */
class ProcessModelEvalTest {

    private DemoPipelineFixture fixture;
    private ProcessModel model;

    @BeforeEach
    void setUp() {
        fixture = DemoPipelineFixture.load();
        model = new ProcessModelBuilder(new ProcessMiningConfig())
            .build(fixture.digests(), fixture.mapping());
    }

    /**
     * The seeder writes six orders — ORD-101 through ORD-106 — and nothing else that carries an
     * order id in its payload. Anything other than six is the measurement counting something the
     * dataset does not contain.
     */
    @Test
    void theSixSeededOrdersAreTheCases() {
        assertTrue(model.available(), model.unavailableReason());
        assertEquals(6, model.cases());
        assertEquals(14, model.events(), "8 received + 2 validated + 1 each through 3..6");
    }

    /**
     * The pipeline the seeder builds, edge by edge. This is the assertion the whole eval exists
     * for: before W1 the model was asked to infer this from topic names, and an inferred chain and
     * a measured one look identical in the answer.
     */
    @Test
    void theOrderPipelineIsMeasuredEdgeByEdge() {
        for (int i = 1; i < DemoPipelineFixture.ORDER_TOPICS.size(); i++) {
            String from = DemoPipelineFixture.ORDER_TOPICS.get(i - 1);
            String to = DemoPipelineFixture.ORDER_TOPICS.get(i);
            assertTrue(edge(from, to).isPresent(),
                "the measured graph is missing " + from + " → " + to + ": " + model.edges());
        }
    }

    /**
     * The 3 → 4 hop is the slow one, and it is slow on purpose: {@code STEP_PAUSE} multiplies
     * {@code DEMO_HOP_DELAY} by three there. A latency measurement that could not see the one hop
     * the dataset made stand out would measure nothing worth measuring.
     */
    @Test
    void theHopTheSeederMadeSlowIsTheSlowestOne() {
        ProcessModel.Edge slowest = model.edges().stream()
            .max((a, b) -> Long.compare(a.p95Ms(), b.p95Ms()))
            .orElseThrow();

        assertEquals("demo.orders.3.enriched", slowest.from());
        assertEquals("demo.orders.4.transformed", slowest.to());
        assertEquals(6_000, slowest.p95Ms(), "three times the 2 s of every other hop");
    }

    /**
     * ORD-102 is rejected at validation and goes no further. Reported as a <em>distribution</em> of
     * end activities — nothing is called an orphan, because which activity ought to end this
     * process is a business fact the application does not have.
     */
    @Test
    void theRejectedOrderShowsAsACaseThatEndedAtValidation() {
        assertEquals(1, endCases("demo.orders.2.validated"));
        assertEquals(1, endCases("demo.orders.6.delivered"), "only ORD-101 goes the whole way");
        assertEquals(4, endCases("demo.orders.1.received"),
            "ORD-103 through ORD-106 are seeded for aggregation and never move on");
    }

    /** ORD-103 and ORD-105 are produced twice with an identical payload: an at-least-once retry. */
    @Test
    void theRedeliveredOrdersShowAsARepeatedStep() {
        ProcessModel.Repeat repeat = model.repeats().stream()
            .filter(r -> r.activity().equals("demo.orders.1.received"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no repeat reported: " + model.repeats()));

        assertEquals(DemoPipelineFixture.REDELIVERED_KEYS.size(), repeat.casesAffected());
        assertEquals(2, repeat.maxOccurrencesInOneCase());
    }

    /**
     * <b>The defect this eval found.</b> {@code setup-demo.sh} plants two truncated records inside
     * {@code demo.orders.3.enriched}, and a streaming parser reads the fields it reaches before the
     * payload breaks off — {@code id} being the first of them. So each one arrived carrying a
     * correlation id and became a one-event case that <em>ended</em> at enrichment: the measurement
     * reported a pipeline stalling at its third stage where a producer had written two bad records.
     * Two findings, two different places to go, and the wrong one was on screen. Worse, {@code
     * ORD-666} was nominated as a spotlight case and inlined into the prompt as a worked example.
     */
    @Test
    void aTruncatedRecordIsNotACaseThatEndedEarly() {
        assertFalse(model.variants().stream().anyMatch(v -> v.example().startsWith("ORD-66")),
            "a corrupt record became a case: " + model.variants());
        assertFalse(model.spotlightCases().stream().anyMatch(c -> c.startsWith("ORD-66")),
            "a corrupt record was nominated as a worked example: " + model.spotlightCases());
        assertEquals(0, endCases("demo.orders.3.enriched"),
            "nothing ends at enrichment on this dataset — ORD-101 goes on to transformation");

        // Excluded, and said so: dropping records without a word is the mirror defect of counting
        // them, and this measurement is written against exactly that pair.
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("payload that then broke off")),
            model.notes().toString());
    }

    /**
     * The payments and shipments are correlated to the orders <b>by header only</b> — their
     * payloads carry {@code PAY-}/{@code SHP-} references and never the order id. A mapping over
     * payload paths therefore cannot group them, and the honest outcome is that they stay outside
     * the log and are counted, rather than being silently absent or invented into the chain.
     */
    @Test
    void theHeaderCorrelatedTopicsStayOutsideTheLogAndAreCounted() {
        for (String topic : DemoPipelineFixture.HEADER_CORRELATED_TOPICS) {
            assertTrue(model.activities().stream().noneMatch(a -> a.name().equals(topic)),
                topic + " reached the graph, but its payload never carries the order id");
        }
        assertEquals(8, model.eventsWithoutCase(),
            "6 payment/shipment records plus the 2 truncated ones");
    }

    /**
     * The clock is the broker's here, and it is stated rather than assumed. The seeder stamps every
     * step of one order with the <em>same</em> {@code event_time}, so a mapped business timestamp
     * would tie across all six hops; the hop timing comes from {@code DEMO_HOP_DELAY}, a real pause
     * between produce calls. A transport delay and a business duration are different measurements,
     * and the model has to say which one it took.
     */
    @Test
    void theModelStatesThatItMeasuredProduceTime() {
        assertEquals(ProcessModel.TimeSource.RECORD_TIMESTAMP, model.eventTimeSource());
        assertTrue(model.notes().stream().anyMatch(n -> n.contains("produce time, not event time")),
            model.notes().toString());
    }

    private Optional<ProcessModel.Edge> edge(String from, String to) {
        return model.edges().stream()
            .filter(e -> e.from().equals(from) && e.to().equals(to))
            .findFirst();
    }

    private int endCases(String activity) {
        List<ProcessModel.Endpoint> ends = model.ends();
        return ends.stream()
            .filter(e -> e.activity().equals(activity))
            .mapToInt(ProcessModel.Endpoint::cases)
            .findFirst()
            .orElse(0);
    }
}
