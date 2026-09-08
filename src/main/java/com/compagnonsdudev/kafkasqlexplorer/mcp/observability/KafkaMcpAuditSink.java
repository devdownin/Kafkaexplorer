// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;

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

    private volatile KafkaProducer<String, String> producer;

    public KafkaMcpAuditSink(KafkaConfig kafkaConfig, McpProperties properties) {
        this.kafkaConfig = kafkaConfig;
        this.properties = properties;
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
                            // Asynchronous, so it cannot be counted by the recorder's own catch;
                            // logged and the producer dropped so the next call reconnects.
                            log.warn("MCP audit append failed for {}: {}", call.correlationId(),
                                    failure.getMessage());
                            closeProducer();
                        }
                    });
        } catch (Exception e) {
            closeProducer();
            throw new IllegalStateException("MCP audit append failed: " + e.getMessage(), e);
        }
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
