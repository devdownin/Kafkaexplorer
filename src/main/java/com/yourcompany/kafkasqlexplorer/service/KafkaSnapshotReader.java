// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Component
public class KafkaSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(KafkaSnapshotReader.class);

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;

    public KafkaSnapshotReader(KafkaConfig kafkaConfig, KafkaAdminService kafkaAdminService) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
    }

    public List<KafkaMessage> read(List<String> topics, SnapshotConfig config) {
        String groupId = "snapshot-reader-" + UUID.randomUUID();
        Properties props = buildConsumerProperties(groupId, config);

        KafkaConsumer<String, String> consumer = null;
        List<KafkaMessage> messages = new ArrayList<>();

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
            int collected = 0;
            int maxMessages = config.maxMessages();

            while (collected < maxMessages && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    messages.add(new KafkaMessage(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.timestamp(),
                        record.key(),
                        record.value()
                    ));
                    collected++;
                    if (collected >= maxMessages) break;
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

        // Sort by timestamp ascending
        messages.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));
        return messages;
    }

    private Properties buildConsumerProperties(String groupId, SnapshotConfig config) {
        Properties props = new Properties();
        // Copy base Kafka properties
        kafkaConfig.getKafkaProperties().forEach(props::put);

        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(Math.min(config.maxMessages(), 500)));

        String offsetReset = switch (config.mode()) {
            case "EARLIEST" -> "earliest";
            case "TIMESTAMP" -> "earliest";
            default -> "latest";
        };
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);

        return props;
    }

    private void seekToLatestN(KafkaConsumer<String, String> consumer,
                                List<TopicPartition> partitions, int n) {
        consumer.seekToEnd(partitions);
        // Calculate per-partition offset to go back n messages total
        int perPartition = Math.max(1, n / Math.max(1, partitions.size()));
        for (TopicPartition tp : partitions) {
            long endOffset = consumer.position(tp);
            long startOffset = Math.max(0, endOffset - perPartition);
            consumer.seek(tp, startOffset);
        }
    }

    private void seekToTimestamp(KafkaConsumer<String, String> consumer,
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
}
