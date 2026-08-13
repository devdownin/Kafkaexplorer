// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.config.ProcessMiningConfig;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.PayloadDigest;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class KafkaSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(KafkaSnapshotReader.class);

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;
    private final ProcessMiningConfig processMiningConfig;
    private final PayloadDigestService payloadDigestService;

    public KafkaSnapshotReader(KafkaConfig kafkaConfig, KafkaAdminService kafkaAdminService,
                                ProcessMiningConfig processMiningConfig,
                                PayloadDigestService payloadDigestService) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.processMiningConfig = processMiningConfig;
        this.payloadDigestService = payloadDigestService;
    }

    /** Reads raw messages. Callers that only need structure and a few fields should prefer
     *  {@link #readDigested} — this one holds every payload in memory as a String. */
    public List<KafkaMessage> read(List<String> topics, SnapshotConfig config) {
        List<KafkaMessage> messages = new ArrayList<>();
        consume(topics, config, StringDeserializer.class, (ConsumerRecord<String, String> record) ->
            messages.add(new KafkaMessage(
                record.topic(), record.partition(), record.offset(), record.timestamp(),
                record.key(), record.value())));
        messages.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));
        return messages;
    }

    /**
     * Reads and digests in one pass: each payload is summarized as it arrives and the raw bytes
     * are released immediately, so a snapshot of MB-sized documents costs a bounded amount of
     * heap regardless of how many messages are sampled.
     *
     * @param sampleFieldLimit how many non-mapped scalar values to keep per message — profiling
     *                         wants a wide view, live analysis a narrow one
     */
    public List<PayloadDigest> readDigested(List<String> topics, SnapshotConfig config,
                                             FieldMapping fieldMapping, int sampleFieldLimit) {
        List<PayloadDigest> digests = new ArrayList<>();
        Map<String, Set<String>> pathsByTopic = new LinkedHashMap<>();

        consume(topics, config, ByteArrayDeserializer.class, (ConsumerRecord<String, byte[]> record) -> {
            Set<String> mapped = pathsByTopic.computeIfAbsent(record.topic(),
                topic -> payloadDigestService.mappedPaths(fieldMapping, topic));
            digests.add(payloadDigestService.digest(
                record.topic(), record.partition(), record.offset(), record.timestamp(),
                record.key(), record.value(), mapped, sampleFieldLimit));
        });

        digests.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));
        return digests;
    }

    private <V> void consume(List<String> topics, SnapshotConfig config,
                              Class<?> valueDeserializer, Consumer<ConsumerRecord<String, V>> handler) {
        Properties props = buildConsumerProperties(config, valueDeserializer);

        KafkaConsumer<String, V> consumer = null;
        try {
            consumer = new KafkaConsumer<>(props);

            // Assign all partitions for the given topics
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : topics) {
                List<TopicPartition> tps = consumer.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
                partitions.addAll(tps);
            }
            consumer.assign(partitions);

            // Seek according to mode
            switch (config.mode()) {
                case "EARLIEST" -> consumer.seekToBeginning(partitions);
                case "LATEST_N" -> seekToLatestN(consumer, partitions, config.maxMessages());
                case "TIMESTAMP" -> seekToTimestamp(consumer, partitions, config.fromTimestamp());
                default -> consumer.seekToBeginning(partitions);
            }

            long deadline = System.currentTimeMillis() + 30_000L;
            Map<String, Integer> collectedByTopic = new HashMap<>();
            topics.forEach(topic -> collectedByTopic.put(topic, 0));
            int maxMessagesPerTopic = config.maxMessages();

            while (!allTopicsReachedLimit(collectedByTopic, maxMessagesPerTopic)
                && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, V> record : records) {
                    int currentCount = collectedByTopic.getOrDefault(record.topic(), 0);
                    if (currentCount >= maxMessagesPerTopic) {
                        continue;
                    }
                    handler.accept(record);
                    collectedByTopic.put(record.topic(), currentCount + 1);
                }
            }

        } catch (Exception e) {
            log.error("Error reading Kafka snapshot for topics {}: {}", topics, e.getMessage(), e);
        } finally {
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("Error closing consumer: {}", e.getMessage());
                }
            }
        }
    }

    private Properties buildConsumerProperties(SnapshotConfig config,
                                                Class<?> valueDeserializer) {
        Properties props = new Properties();
        // Copy base Kafka properties
        kafkaConfig.getKafkaProperties().forEach(props::put);

        // Un seul point d'entrée : le nom du groupe et l'interdiction de commiter sont posés
        // ensemble, sinon les deux dérivent — c'est exactement ce que cette classe existe pour
        // empêcher, et les poser à la main ici rouvrait la porte.
        ExplorerConsumerGroups.configure(props, "snapshot");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(Math.min(Math.max(config.maxMessages(), 1) * 10, 1000)));
        // Records at or above max.partition.fetch.bytes (1 MB by default) are fetched one per
        // round trip; MB-sized payloads need a bigger fetch to batch normally.
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
            String.valueOf(processMiningConfig.getMaxPartitionFetchBytes()));
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,
            String.valueOf(processMiningConfig.getFetchMaxBytes()));

        String offsetReset = switch (config.mode()) {
            case "EARLIEST" -> "earliest";
            case "TIMESTAMP" -> "earliest";
            default -> "latest";
        };
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);

        return props;
    }

    private <V> void seekToLatestN(KafkaConsumer<String, V> consumer,
                                    List<TopicPartition> partitions, int n) {
        consumer.seekToEnd(partitions);
        Map<String, Long> partitionsPerTopic = partitions.stream()
            .collect(java.util.stream.Collectors.groupingBy(TopicPartition::topic, java.util.stream.Collectors.counting()));

        for (TopicPartition tp : partitions) {
            int perPartition = Math.max(1, (int) Math.ceil(
                (double) n / Math.max(1L, partitionsPerTopic.getOrDefault(tp.topic(), 1L))
            ));
            long endOffset = consumer.position(tp);
            long startOffset = Math.max(0, endOffset - perPartition);
            consumer.seek(tp, startOffset);
        }
    }

    private <V> void seekToTimestamp(KafkaConsumer<String, V> consumer,
                                      List<TopicPartition> partitions, Long fromTimestamp) {
        if (fromTimestamp == null) {
            consumer.seekToBeginning(partitions);
            return;
        }
        Map<TopicPartition, Long> timestampsToSearch = new HashMap<>();
        for (TopicPartition tp : partitions) {
            timestampsToSearch.put(tp, fromTimestamp);
        }
        Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(timestampsToSearch);
        for (TopicPartition tp : partitions) {
            OffsetAndTimestamp oat = offsets.get(tp);
            if (oat != null) {
                consumer.seek(tp, oat.offset());
            } else {
                consumer.seekToEnd(List.of(tp));
            }
        }
    }

    private boolean allTopicsReachedLimit(Map<String, Integer> collectedByTopic, int maxMessagesPerTopic) {
        return collectedByTopic.values().stream().allMatch(count -> count >= maxMessagesPerTopic);
    }
}
