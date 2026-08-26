// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.service.PayloadDigestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The seeded demo order pipeline, as a fixture — the golden dataset the eval is written against.
 *
 * <p>Nothing measured whether any of the Process Mining prompt work does what it claims. That
 * failure mode is silent by construction: a plausible answer about an invented pipeline reads
 * exactly like a correct one, and the only way to tell them apart is to run the thing against a
 * pipeline whose right answer is already known. {@code setup-demo.sh} seeds one — the order flow
 * through six topics, ORD-102 dropping off after validation, ORD-103 and ORD-105 redelivered,
 * two truncated records inside an otherwise healthy topic, and payments and shipments correlated
 * to the orders <em>by header only</em>.
 *
 * <p>Three decisions about the fixture are load-bearing.
 *
 * <p><b>It holds records, not digests.</b> A digest is what this application computes; committing
 * one would let the fixture and the digester drift together and agree with each other about a
 * payload neither had read. What is committed is the payloads the seeder writes, verbatim, and
 * the real {@link PayloadDigestService} turns them into digests here — so the eval exercises the
 * ingestion path rather than stepping around it.
 *
 * <p><b>It is not captured from a live cluster.</b> A capture is a snapshot nobody can regenerate
 * without a broker, and it would rot in silence. This is written from the seeder and
 * <em>checked against it</em> by {@code docs/check-eval-fixture.py}, which resolves every topic
 * and every key claimed here against {@code setup-demo.sh}. A fixture that describes a dataset the
 * seeder no longer produces fails the build rather than quietly evaluating the wrong thing.
 *
 * <p><b>The clock is the broker's, and that is a property of the dataset rather than a shortcut.</b>
 * The seeder stamps every step of one order with the <em>same</em> {@code event_time}, so a mapped
 * business timestamp would tie across all six hops and measure nothing; what produces the real hop
 * timing there is {@code DEMO_HOP_DELAY}, a pause between produce calls. The mapping below
 * therefore declares a correlation id and no timestamp, which is exactly the case
 * {@code ProcessModel.TimeSource.RECORD_TIMESTAMP} exists to report — and the eval asserts that it
 * is reported rather than passed off as event time.
 */
public final class DemoPipelineFixture {

    private static final String RESOURCE = "/eval/demo-order-pipeline.json";
    private static final PayloadDigestService DIGESTS =
        new PayloadDigestService(new ProcessMiningConfig());

    /** The six order topics, in pipeline order — the chain the eval expects to be measured. */
    public static final List<String> ORDER_TOPICS = List.of(
        "demo.orders.1.received",
        "demo.orders.2.validated",
        "demo.orders.3.enriched",
        "demo.orders.4.transformed",
        "demo.orders.5.shipped",
        "demo.orders.6.delivered");

    /** The two topics correlated to the orders by header only, so their records fall outside the log. */
    public static final List<String> HEADER_CORRELATED_TOPICS = List.of(
        "demo.payments.authorized",
        "demo.payments.captured",
        "demo.shipments.dispatched",
        "demo.shipments.delivered");

    /** The keys the seeder deliberately redelivers, which is what the audit's duplicate check is for. */
    public static final List<String> REDELIVERED_KEYS = List.of("ORD-103", "ORD-105");

    private final List<PayloadDigest> digests;
    private final List<String> topics;
    private final String correlationPath;

    private DemoPipelineFixture(List<PayloadDigest> digests, List<String> topics, String correlationPath) {
        this.digests = digests;
        this.topics = topics;
        this.correlationPath = correlationPath;
    }

    public static DemoPipelineFixture load() {
        JsonNode root;
        try (InputStream in = DemoPipelineFixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The eval fixture is missing from the classpath: " + RESOURCE);
            }
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        long base = root.path("baseTimestampMs").asLong();
        String correlationPath = root.path("correlationIdPath").asText("id");
        Set<String> mappedPaths = Set.of(correlationPath);

        List<PayloadDigest> digests = new ArrayList<>();
        Set<String> topics = new LinkedHashSet<>();
        long offset = 0;
        for (JsonNode record : root.path("records")) {
            String topic = record.path("topic").asText();
            String key = record.path("key").isNull() ? null : record.path("key").asText();
            byte[] value = record.path("value").asText().getBytes(StandardCharsets.UTF_8);
            // One partition and a running offset: the fixture is about what the records say, and
            // the reader's partition assignment is KafkaSnapshotReaderTest's subject, not this one.
            digests.add(DIGESTS.digest(topic, 0, offset++, base + record.path("atMs").asLong(),
                key, value, mappedPaths));
            topics.add(topic);
        }
        return new DemoPipelineFixture(List.copyOf(digests), List.copyOf(topics), correlationPath);
    }

    public List<PayloadDigest> digests() {
        return digests;
    }

    /** Every topic the fixture carries, in the order it first appears. */
    public List<String> topics() {
        return topics;
    }

    /**
     * The mapping an operator would validate on this cluster: the order id, on the order topics.
     *
     * <p>No timestamp and no status, and both absences are the dataset's rather than an omission —
     * see the class comment for the clock, and the seeder writes the stage into the topic name
     * rather than relying on the {@code state} field, which is what makes the topic the activity.
     */
    public FieldMapping mapping() {
        Map<String, String> correlation = new LinkedHashMap<>();
        for (String topic : ORDER_TOPICS) {
            correlation.put(topic, correlationPath);
        }
        return new FieldMapping("eval-demo", correlation, Map.of(), Map.of(), Map.of());
    }
}
