// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The field mappings validated by the Process Mining pipeline.
 *
 * <p>This used to be a {@code ConcurrentHashMap} field on {@code ProcessMiningController}, which
 * made it unreachable to anything else — and the mapping is the one place in this application that
 * knows a topic's <em>real</em> correlation key rather than an assumed {@code id}. The Metrics
 * suggestions want exactly that, so the cache became a component both can read.
 *
 * <p>It is <b>persisted</b>, keyed by mapping id, to {@code explorer.field-mapping-topic} — the
 * pattern {@code MetricService} and {@code AuditService} already follow with their own
 * {@code internal.*} topics. It was the only human-validated artefact this application produced
 * and then threw away: a restart lost every mapping, the Metrics suggestions reported one they
 * could no longer resolve, and recovering it meant re-running two model calls to re-derive
 * something an operator had already corrected by hand. The model's proposal is cheap to replay;
 * the correction is not.
 *
 * <p>Everything about that persistence is best-effort and <b>bounded</b>. A restore that fails
 * leaves an empty store and a log line, never a startup that hangs on an unreachable broker; a
 * save that fails leaves the mapping usable in memory for this process, which is exactly what the
 * behaviour was before. Writes do not block the caller: a validation is an interactive gesture,
 * and holding it for a broker round trip would trade a real second for a hypothetical one.
 *
 * <p>In memory it stays bounded and access-ordered, so the {@link #MAX_ENTRIES} kept are the ones
 * still being used rather than the oldest ever created — a mapping is re-read on every analysis it
 * drives.
 */
@Component
public class FieldMappingStore {

    private static final Logger log = LoggerFactory.getLogger(FieldMappingStore.class);

    /**
     * Generous on purpose, and the number comes from the other half of this fix.
     *
     * <p>Two changes bounded this cache at once — one in place on the controller, one by moving it
     * here — and their merge produced a file that did not compile. Resolving it kept the better
     * argued cap of the two: losing a mapping mid-pipeline costs a re-validation, and since it is
     * the *use* that refreshes an entry, the mapping of a live session running for hours must not
     * be evicted from under it by newer ones. A mapping is a few hundred bytes.
     */
    static final int MAX_ENTRIES = 200;

    /** The restore runs during startup, so it is bounded on both the calls and the whole read. */
    private static final long RESTORE_BUDGET_MS = 10_000;

    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper()
        // A mapping written by a later version must still load here: an unknown property is a
        // field this build does not use, not a reason to lose every mapping on the topic.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private volatile Producer<String, String> producer;

    private final Map<String, FieldMapping> mappings = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FieldMapping> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    public FieldMappingStore(KafkaConfig kafkaConfig, ExplorerConfig explorerConfig) {
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
    }

    @PostConstruct
    public void init() {
        restoreFromKafka();
    }

    public void put(FieldMapping mapping) {
        if (mapping == null || mapping.id() == null) return;
        mappings.put(mapping.id(), mapping);
        persist(mapping);
    }

    /**
     * The mapping with that id, or empty.
     *
     * <p>Still empty in three real cases — the id was never validated here, the mapping aged out
     * of a topic with retention, or the broker could not be read at startup — so callers must keep
     * saying which answer they got rather than treating an absent mapping as "nothing to say".
     */
    public Optional<FieldMapping> find(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(mappings.get(id));
    }

    // ── Kafka persistence ─────────────────────────────────────────────────────

    private void persist(FieldMapping mapping) {
        try {
            String payload = objectMapper.writeValueAsString(mapping);
            // Asynchronous on purpose: this runs on the request thread of a validation the
            // operator is waiting on, and the in-memory copy is already usable. A failure is
            // logged and the producer dropped so the next save reconnects with fresh state.
            producer().send(
                new ProducerRecord<>(explorerConfig.getFieldMappingTopic(), mapping.id(), payload),
                (metadata, error) -> {
                    if (error != null) {
                        log.warn("Field mapping {} was not persisted: {}", mapping.id(), error.toString());
                        closeProducer();
                    }
                });
        } catch (Exception e) {
            log.warn("Field mapping {} could not be persisted: {}", mapping.id(), e.toString());
            closeProducer();
        }
    }

    private Producer<String, String> producer() {
        Producer<String, String> existing = producer;
        if (existing != null) return existing;
        synchronized (this) {
            if (producer == null) producer = createProducer();
            return producer;
        }
    }

    /** Test seam. */
    Producer<String, String> createProducer() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Without this a send() on an unreachable broker blocks the caller for a minute inside
        // the buffer allocation, which is the one thing the asynchronous send exists to avoid.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        return new KafkaProducer<>(props);
    }

    /** Test seam. */
    Consumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "field-mapping-restorer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Fail fast when the broker is unreachable — this runs during application startup.
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "4000");
        return new KafkaConsumer<>(props);
    }

    void restoreFromKafka() {
        try (Consumer<String, String> consumer = createConsumer()) {
            String topic = explorerConfig.getFieldMappingTopic();
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return;   // the topic does not exist yet — nothing was ever validated
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            // Driven by the end offsets, never by an empty poll: the first poll after an assignment
            // is very often empty while the fetch is in flight, and treating that as "exhausted"
            // is how a restore silently returns a fraction of what is stored.
            long deadline = System.currentTimeMillis() + RESTORE_BUDGET_MS;
            while (System.currentTimeMillis() < deadline && !reachedEnd(consumer, endOffsets)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    restoreOne(record);
                }
            }
            if (!mappings.isEmpty()) {
                log.info("Restored {} Process Mining field mapping(s) from {}", mappings.size(), topic);
            }
        } catch (Exception e) {
            // Never fatal: an unreachable broker at startup must not keep the app down, and the
            // pipeline can always re-validate a mapping.
            log.debug("Field mappings could not be restored: {}", e.toString());
        }
    }

    private void restoreOne(ConsumerRecord<String, String> record) {
        try {
            if (record.value() == null) {       // tombstone, whether ours or a compaction artefact
                mappings.remove(record.key());
                return;
            }
            FieldMapping mapping = objectMapper.readValue(record.value(), FieldMapping.class);
            if (mapping.id() != null) mappings.put(mapping.id(), mapping);
        } catch (Exception e) {
            // One unreadable record must not abort the restore — the others are still valid.
            log.warn("Skipping an unreadable field mapping at {}:{}: {}",
                record.partition(), record.offset(), e.toString());
        }
    }

    private boolean reachedEnd(Consumer<String, String> consumer, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    @PreDestroy
    public synchronized void shutdown() {
        closeProducer();
    }

    private synchronized void closeProducer() {
        if (producer == null) return;
        try {
            // Bounded: the no-arg close() waits without a deadline for buffered records, and
            // shutdown is exactly when the broker is likely to be gone.
            producer.close(Duration.ofSeconds(5));
        } catch (Exception ignored) {
            // Nothing useful to do while shutting down.
        }
        producer = null;
    }
}
