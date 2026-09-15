// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The call trail, appended to {@code explorer.mcp.audit-topic}.
 *
 * <p>The ring buffer the console reads is a live feed with a capacity, so it forgets. An audit
 * trail that forgets is not one: the question it exists to answer — "what did this agent do last
 * Tuesday?" — is asked after the fact, by definition. Until now the setting shipped and nothing
 * wrote to it, and the console said so, which was honest and still left an operator with no history.
 *
 * <p><b>Every call, including every refusal.</b> A control that blocks silently is a control nobody
 * ever tunes, and the refusals are the half an incident review actually needs. The parameters are
 * already redacted by the interceptor before they reach the recorder, and the approval token is
 * removed rather than masked — a bearer credential in a durable log outlives the fifteen minutes it
 * was minted for.
 *
 * <p><b>A failed append never fails the call.</b> It increments
 * {@code explorer_mcp_audit_write_errors_total}, which the console shows, and that gauge moving is
 * the signal that the trail has holes. Refusing the tool instead would make an unreachable broker
 * into an outage of the whole MCP surface, and a trail is not worth that; a trail with a counted
 * hole is honest, a surface that goes down when the trail does is not.
 *
 * <p><b>Fire and forget, keyed by identity.</b> {@code get()} on the send future would put a broker
 * round trip in front of every tool response, which is the cost of a guarantee this sink does not
 * make. The key is the caller's identity so a compacted topic keeps one record per caller and a
 * partitioned one keeps a caller's calls in order — the order being what a review reads.
 */
public class KafkaMcpAuditSink implements McpAuditSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaMcpAuditSink.class);

    private final KafkaConfig kafkaConfig;
    private final McpProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * The same series {@link McpCallRecorder} increments, by name.
     *
     * <p>Micrometer returns one meter per id, so the two registrations are one counter: the
     * recorder counts what {@code append} threw, this counts what the broker then refused, and
     * `explorer_mcp_audit_write_errors_total` is the number of holes in the trail either way. The
     * alternative — the sink calling back into the recorder — is a cycle between two beans, one of
     * which is constructed with the other.
     */
    private final Counter writeErrors;

    private volatile KafkaProducer<String, String> producer;

    /**
     * Appends the broker did not take, counted where they actually happen.
     *
     * <p>The recorder counts what {@code append} throws; this is the other half, and it is the
     * commoner one — a broker that is away, a topic that does not exist, retries spent. It was
     * logged and counted nowhere, so the gauge that says the trail has holes read zero through the
     * outage that makes them.
     */
    private final AtomicLong asyncWriteErrors = new AtomicLong();

    public KafkaMcpAuditSink(KafkaConfig kafkaConfig, McpProperties properties, MeterRegistry meters) {
        this.kafkaConfig = kafkaConfig;
        this.properties = properties;
        this.writeErrors = Counter.builder("explorer_mcp_audit_write_errors_total")
                .description("MCP calls whose audit append failed; the call itself still succeeded")
                .register(meters);
    }

    @Override
    public void append(McpCallRecord call) {
        String value;
        try {
            value = mapper.writeValueAsString(call);
        } catch (Exception e) {
            // Thrown, so the recorder counts it: a record that cannot be serialised is a hole in
            // the trail exactly as a broker that will not take it is.
            throw new IllegalStateException("MCP audit record could not be serialised", e);
        }
        try {
            producer().send(new ProducerRecord<>(properties.getAuditTopic(), call.identity(), value),
                    (metadata, failure) -> {
                        if (failure != null) {
                            // Asynchronous, so the recorder's own catch cannot see it: counted here
                            // instead, and read back through asyncWriteErrors().
                            //
                            // The producer is NOT dropped here. It was, on the reasoning that the
                            // next call would reconnect — but this runs on the producer's own I/O
                            // thread, where close() cannot join itself, and a client that already
                            // reconnects and retries on its own was being rebuilt on every
                            // transient timeout, at the cost of its buffer and a metadata fetch, on
                            // the thread serving tool calls.
                            asyncWriteErrors.incrementAndGet();
                            writeErrors.increment();
                            log.warn("MCP audit append failed for {}: {}", call.correlationId(),
                                    failure.getMessage());
                        }
                    });
        } catch (Exception e) {
            closeProducer();
            throw new IllegalStateException("MCP audit append failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long asyncWriteErrors() {
        return asyncWriteErrors.get();
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> existing = producer;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (producer == null) {
                Properties props = new Properties();
                props.putAll(kafkaConfig.getKafkaProperties());
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                // Same 500 ms as the audit-history producer: a broker that is away must not park
                // the thread serving a tool call.
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
                producer = new KafkaProducer<>(props);
            }
            return producer;
        }
    }

    @PreDestroy
    public synchronized void closeProducer() {
        if (producer != null) {
            try {
                // Bounded: this runs on shutdown and on the failure path, both of which are exactly
                // when the broker may be unreachable, and an unbounded close would hang shutdown
                // until the container was killed.
                producer.close(Duration.ofSeconds(5));
            } catch (Exception ignored) {
                // Nothing to do on shutdown; the next line drops the reference either way.
            }
            producer = null;
        }
    }
}
