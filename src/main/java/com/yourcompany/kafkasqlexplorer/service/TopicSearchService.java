// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.TopicMessage;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Bounded server-side search across a topic's records.
 *
 * <p>The scan never promises completeness: it reads until one of three budgets is spent (hits,
 * records scanned, wall clock) and returns a cursor. That keeps a search on a topic with millions
 * of records responsive and resumable — the UI shows what was found, says how much ground was
 * covered, and asks for more on demand — instead of blocking on a request that may never finish.</p>
 */
@Service
public class TopicSearchService {

    private static final Logger log = LoggerFactory.getLogger(TopicSearchService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    /** Consecutive empty polls before a partition set is considered drained. */
    private static final int MAX_EMPTY_POLLS = 2;

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;

    public TopicSearchService(KafkaConfig kafkaConfig, KafkaAdminService kafkaAdminService,
                              ExplorerConfig explorerConfig) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
    }

    public TopicSearchResponse search(String topic, TopicSearchRequest request) {
        MessageMatcher matcher = MessageMatcher.from(request);   // throws on bad regex / missing field

        int maxHits = clamp(request.maxHits(), explorerConfig.getSearchMaxHits(), 1, 1_000);
        int maxScan = clamp(request.maxScan(), explorerConfig.getSearchMaxScan(), 1, 1_000_000);
        int timeoutMs = clamp(request.timeoutMs(), explorerConfig.getSearchTimeoutMs(), 500, 120_000);
        int maxValueChars = explorerConfig.getSearchMaxValueChars();

        List<TopicMessage> hits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Long> nextCursor = new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        int scanned = 0;
        int matched = 0;
        String stopReason = "EXHAUSTED";

        Properties props = buildConsumerProperties();
        try (Consumer<byte[], byte[]> consumer = createConsumer(props)) {
            List<TopicPartition> partitions = resolvePartitions(consumer, topic, request, warnings);
            if (partitions.isEmpty()) {
                warnings.add("No partition to scan for topic " + topic + ".");
                return new TopicSearchResponse(hits, 0, 0, 0, true, "EXHAUSTED", nextCursor, warnings);
            }

            consumer.assign(partitions);
            seek(consumer, partitions, request);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            for (TopicPartition tp : partitions) {
                nextCursor.put(String.valueOf(tp.partition()), consumer.position(tp));
            }

            int emptyPolls = 0;
            boolean done = false;
            while (!done) {
                if (System.currentTimeMillis() >= deadline) {
                    stopReason = "TIMEOUT";
                    break;
                }
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    if (reachedEnd(consumer, partitions, endOffsets) || ++emptyPolls >= MAX_EMPTY_POLLS) {
                        break;
                    }
                    continue;
                }
                emptyPolls = 0;

                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    scanned++;
                    nextCursor.put(String.valueOf(record.partition()), record.offset() + 1);

                    String value = kafkaAdminService.deserializeValue(record.topic(), record.value());
                    String key = record.key() == null
                        ? null
                        : new String(record.key(), StandardCharsets.UTF_8);

                    if (matcher.matches(key, value)) {
                        matched++;
                        if (hits.size() < maxHits) {
                            hits.add(TopicMessage.of(record.partition(), record.offset(),
                                record.timestamp(), key, value, headersOf(record), maxValueChars));
                        }
                    }

                    if (hits.size() >= maxHits) {
                        stopReason = "MAX_HITS";
                        done = true;
                        break;
                    }
                    if (scanned >= maxScan) {
                        stopReason = "MAX_SCAN";
                        done = true;
                        break;
                    }
                }
            }

            boolean exhausted = "EXHAUSTED".equals(stopReason)
                && reachedEnd(consumer, partitions, endOffsets);
            if (exhausted) {
                stopReason = "EXHAUSTED";
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            return new TopicSearchResponse(hits, scanned, matched, elapsed, exhausted, stopReason,
                nextCursor, warnings);

        } catch (Exception e) {
            log.error("Error searching topic {}: {}", topic, e.getMessage(), e);
            warnings.add("Search failed: " + e.getMessage());
            return new TopicSearchResponse(hits, scanned, matched,
                System.currentTimeMillis() - startedAt, false, "ERROR", nextCursor, warnings);
        }
    }

    /** Seam for tests: overridden to inject a MockConsumer instead of a real one. */
    protected Consumer<byte[], byte[]> createConsumer(Properties props) {
        return new KafkaConsumer<>(props);
    }

    private List<TopicPartition> resolvePartitions(Consumer<byte[], byte[]> consumer, String topic,
                                                    TopicSearchRequest request, List<String> warnings) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return List.of();
        }
        List<TopicPartition> all = infos.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .sorted((a, b) -> Integer.compare(a.partition(), b.partition()))
            .toList();

        if (request.partitions() == null || request.partitions().isEmpty()) {
            return all;
        }
        List<TopicPartition> selected = all.stream()
            .filter(tp -> request.partitions().contains(tp.partition()))
            .toList();
        if (selected.isEmpty()) {
            warnings.add("None of the requested partitions exist; scanning all of them instead.");
            return all;
        }
        return selected;
    }

    /**
     * Positions the scan. A cursor always wins: resuming a search must continue where the previous
     * pass stopped, whatever the original starting mode was.
     */
    private void seek(Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                       TopicSearchRequest request) {
        Map<String, Long> cursor = request.cursor();
        boolean seekedFromCursor = false;
        if (cursor != null && !cursor.isEmpty()) {
            seekedFromCursor = true;
            for (TopicPartition tp : partitions) {
                Long offset = cursor.get(String.valueOf(tp.partition()));
                if (offset == null) {
                    seekedFromCursor = false;
                    break;
                }
            }
        }
        if (seekedFromCursor) {
            for (TopicPartition tp : partitions) {
                consumer.seek(tp, cursor.get(String.valueOf(tp.partition())));
            }
            return;
        }

        switch (request.resolvedFrom()) {
            case "LATEST" -> consumer.seekToEnd(partitions);
            case "OFFSET" -> {
                long target = request.fromOffset() == null ? 0L : request.fromOffset();
                Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
                Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
                for (TopicPartition tp : partitions) {
                    long clamped = Math.min(
                        Math.max(target, beginning.getOrDefault(tp, 0L)),
                        end.getOrDefault(tp, Long.MAX_VALUE));
                    consumer.seek(tp, clamped);
                }
            }
            case "TIMESTAMP" -> seekToTimestamp(consumer, partitions, resolveTimestamp(request));
            default -> consumer.seekToBeginning(partitions);
        }
    }

    private long resolveTimestamp(TopicSearchRequest request) {
        if (request.fromTimestamp() != null) {
            return request.fromTimestamp();
        }
        int minutes = request.sinceMinutes() == null ? 60 : Math.max(1, request.sinceMinutes());
        return System.currentTimeMillis() - minutes * 60_000L;
    }

    private void seekToTimestamp(Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                  long timestamp) {
        Map<TopicPartition, Long> query = new LinkedHashMap<>();
        partitions.forEach(tp -> query.put(tp, timestamp));
        Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(query);
        for (TopicPartition tp : partitions) {
            OffsetAndTimestamp found = offsets.get(tp);
            if (found != null) {
                consumer.seek(tp, found.offset());
            } else {
                // No record at or after that instant: nothing to scan on this partition.
                consumer.seekToEnd(List.of(tp));
            }
        }
    }

    private boolean reachedEnd(Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                Map<TopicPartition, Long> endOffsets) {
        for (TopicPartition tp : partitions) {
            Long end = endOffsets.get(tp);
            if (end == null) {
                continue;
            }
            if (consumer.position(tp) < end) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> headersOf(ConsumerRecord<byte[], byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private Properties buildConsumerProperties() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        // Fresh group per search, no commits: a search must never move anyone's offsets.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "topic-search-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return props;
    }

    private static int clamp(Integer requested, int fallback, int min, int max) {
        int value = requested == null ? fallback : requested;
        return Math.min(Math.max(value, min), max);
    }
}
