// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchResponse;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
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
import org.apache.kafka.common.utils.Utils;
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
    /** Characters examined when deciding whether a value is text at all. */
    private static final int BINARY_PROBE_CHARS = 256;

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
        // A path search that never applied is the difference between "no record holds that value"
        // and "no record scanned is in a format the path can read". Both used to look identical.
        int formatMismatch = 0;
        int parseFailures = 0;
        int binaryPayloads = 0;
        String stopReason = "EXHAUSTED";

        Properties props = buildConsumerProperties();
        try (Consumer<byte[], byte[]> consumer = createConsumer(props)) {
            List<TopicPartition> partitions = resolvePartitions(consumer, topic, request, warnings);
            if (partitions.isEmpty()) {
                warnings.add("No partition to scan for topic " + topic + ".");
                return new TopicSearchResponse(hits, 0, 0, 0, true, "EXHAUSTED", nextCursor, warnings);
            }

            consumer.assign(partitions);
            seek(consumer, partitions, request, maxScan);

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
                    // Built once per record: needed by the matcher only when headers are searched,
                    // and by a hit for its response payload.
                    Map<String, String> headers = matcher.needsHeaders() ? headersOf(record) : null;

                    if (looksBinary(value)) {
                        binaryPayloads++;
                    }

                    switch (matcher.evaluate(key, value, headers)) {
                        case MATCH -> {
                            matched++;
                            if (hits.size() < maxHits) {
                                hits.add(TopicMessage.of(record.partition(), record.offset(),
                                    record.timestamp(), key, value,
                                    headers != null ? headers : headersOf(record), maxValueChars));
                            }
                        }
                        case FORMAT_MISMATCH -> formatMismatch++;
                        case PARSE_ERROR -> parseFailures++;
                        case NO_MATCH -> { /* nothing to record */ }
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
            addPathWarnings(matcher, scanned, formatMismatch, parseFailures, warnings);
            addBinaryWarning(scanned, binaryPayloads, warnings);
            long elapsed = System.currentTimeMillis() - startedAt;
            return new TopicSearchResponse(hits, scanned, matched, elapsed, exhausted, stopReason,
                nextCursor, warnings);

        } catch (Exception e) {
            log.error("Error searching topic {}: {}", LogSafe.name(topic), LogSafe.text(e.getMessage()), e);
            warnings.add("Search failed: " + e.getMessage());
            return new TopicSearchResponse(hits, scanned, matched,
                System.currentTimeMillis() - startedAt, false, "ERROR", nextCursor, warnings);
        }
    }

    /**
     * One record, read by its coordinates and returned whole.
     *
     * <p>A search hit is truncated to {@code search-max-value-chars} so that a hundred of them stay
     * a small response — which leaves no way to read a large payload at all, only a badge saying it
     * was cut. Reading one record on purpose is the opposite need, so it gets its own budget
     * ({@code record-max-value-chars}); the response still carries {@code truncated} and the
     * original size, because a cap that lies about itself would be the same bug one order of
     * magnitude further out.</p>
     *
     * @return the record, or {@code null} when that offset holds none — out of range, or compacted
     *     away, which are the two cases a caller has to be able to tell from a server error
     */
    public TopicMessage readRecord(String topic, int partition, long offset) {
        TopicPartition tp = new TopicPartition(topic, partition);
        long deadline = System.currentTimeMillis() + explorerConfig.getSearchTimeoutMs();

        try (Consumer<byte[], byte[]> consumer = createConsumer(buildConsumerProperties())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null || infos.stream().noneMatch(info -> info.partition() == partition)) {
                return null;
            }
            List<TopicPartition> assignment = List.of(tp);
            consumer.assign(assignment);

            long beginning = consumer.beginningOffsets(assignment).getOrDefault(tp, 0L);
            long end = consumer.endOffsets(assignment).getOrDefault(tp, 0L);
            if (offset < beginning || offset >= end) {
                return null;
            }
            consumer.seek(tp, offset);

            int emptyPolls = 0;
            while (System.currentTimeMillis() < deadline && emptyPolls < MAX_EMPTY_POLLS) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    if (record.offset() == offset) {
                        return TopicMessage.of(record.partition(), record.offset(), record.timestamp(),
                            record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                            kafkaAdminService.deserializeValue(record.topic(), record.value()),
                            headersOf(record), explorerConfig.getRecordMaxValueChars());
                    }
                    // On a compacted topic the offset can simply no longer exist: the reader lands
                    // on the next surviving record, and reading past the target proves it is gone.
                    if (record.offset() > offset) {
                        return null;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Error reading {}-{} at offset {}: {}",
                LogSafe.name(topic), partition, offset, LogSafe.text(e.getMessage()), e);
            throw new IllegalStateException("Could not read record: " + e.getMessage(), e);
        }
    }

    /**
     * States when a path search could not be applied at all. Zero hits because every payload was
     * plain text is a different answer from zero hits because the value is not there, and the user
     * can act on the first one (clear the path, or fix it).
     */
    static void addPathWarnings(MessageMatcher matcher, int scanned, int formatMismatch,
                                 int parseFailures, List<String> warnings) {
        if (!matcher.isPathScoped()) {
            return;
        }
        if (scanned > 0 && formatMismatch == scanned) {
            warnings.add("The path could not be applied: none of the " + scanned
                + " record(s) scanned is " + matcher.expectedFormat() + ".");
        } else if (formatMismatch > 0) {
            warnings.add(formatMismatch + " of " + scanned + " record(s) scanned are not "
                + matcher.expectedFormat() + "; the path was not applied to them.");
        }
        if (parseFailures > 0) {
            warnings.add(parseFailures + " malformed payload(s) were skipped.");
        }
    }

    /**
     * A value that did not survive being read as UTF-8 (protobuf, a compressed blob, an unregistered
     * Avro record): the replacement character or a NUL is the give-away. A text search can never
     * match such a record, and saying so beats a confident zero. Only the head of the value is
     * examined — the question is what kind of payload this is, not where the first bad byte sits.
     */
    static boolean looksBinary(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int limit = Math.min(value.length(), BINARY_PROBE_CHARS);
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '\uFFFD' || (c == '\0')) {
                return true;
            }
        }
        return false;
    }

    static void addBinaryWarning(int scanned, int binaryPayloads, List<String> warnings) {
        if (binaryPayloads == 0) {
            return;
        }
        warnings.add(binaryPayloads + " of " + scanned + " record(s) scanned are not text "
            + "(binary payload, or a format this cluster cannot decode); no text search can match them.");
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
            return narrowToKeyPartition(all, request, warnings);
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
     * Scans only the partition the default partitioner would have chosen for the key.
     *
     * <p>Worth a twentieth of the work on a twenty-partition topic, and unsound in exactly two
     * cases the search cannot detect: a custom partitioner, or a partition count that changed after
     * the record was produced. Hence opt-in, restricted to an exact key comparison — a substring
     * match says nothing about which key was hashed — and always stated in the response, because a
     * narrowed scan that found nothing is not the same answer as a full scan that found nothing.
     */
    private List<TopicPartition> narrowToKeyPartition(List<TopicPartition> all,
                                                       TopicSearchRequest request,
                                                       List<String> warnings) {
        if (!request.isKeyPartitioning()) {
            return all;
        }
        boolean exactKeySearch = "KEY".equals(request.resolvedMode())
            && "EQ".equals(request.resolvedOperator());
        if (!exactKeySearch) {
            warnings.add("Key partitioning was requested but ignored: it only applies to an exact "
                + "key search (mode KEY, operator =). Every partition was scanned.");
            return all;
        }
        String key = request.value();
        if (key == null || key.isBlank() || all.isEmpty()) {
            return all;
        }
        int partition = Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % all.size();
        List<TopicPartition> target = all.stream()
            .filter(tp -> tp.partition() == partition)
            .toList();
        if (target.isEmpty()) {
            return all;
        }
        warnings.add("Scanned partition " + partition + " of " + all.size()
            + " only, assuming the default partitioner and an unchanged partition count. "
            + "Turn key partitioning off to scan every partition.");
        return target;
    }

    /**
     * Positions the scan. A cursor always wins: resuming a search must continue where the previous
     * pass stopped, whatever the original starting mode was.
     */
    private void seek(Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                       TopicSearchRequest request, int maxScan) {
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
            // The most recent records, spread over the partitions. Clamped to the beginning offset:
            // on a topic trimmed by retention, seeking below it is an out-of-range position and the
            // consumer resets to auto.offset.reset, silently returning nothing.
            //
            // A time window raises that floor instead of replacing it. Seeking to the start of the
            // window and reading forward — what TIMESTAMP does — spends the record budget on the
            // OLDEST records of the window, so on a busy topic a match from a minute ago was missed
            // while the scan read messages from an hour ago. Starting at whichever is later reads
            // the most recent records that are still inside the window.
            case "LAST_N" -> {
                Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
                Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
                Map<TopicPartition, Long> windowStart = hasTimeWindow(request)
                    ? windowOffsets(consumer, partitions, resolveTimestamp(request))
                    : Map.of();
                long perPartition = Math.max(1L, (long) maxScan / Math.max(partitions.size(), 1)) + 1;
                for (TopicPartition tp : partitions) {
                    long endOffset = end.getOrDefault(tp, 0L);
                    long start = Math.max(beginning.getOrDefault(tp, 0L), endOffset - perPartition);
                    Long floor = windowStart.get(tp);
                    if (floor != null) {
                        start = Math.max(start, floor);
                    }
                    consumer.seek(tp, start);
                }
            }
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

    private static boolean hasTimeWindow(TopicSearchRequest request) {
        return request.fromTimestamp() != null
            || (request.sinceMinutes() != null && request.sinceMinutes() > 0);
    }

    /** First offset at or after {@code timestamp}, per partition; absent when there is none. */
    private Map<TopicPartition, Long> windowOffsets(Consumer<byte[], byte[]> consumer,
                                                     List<TopicPartition> partitions, long timestamp) {
        Map<TopicPartition, Long> query = new LinkedHashMap<>();
        partitions.forEach(tp -> query.put(tp, timestamp));
        Map<TopicPartition, Long> resolved = new LinkedHashMap<>();
        consumer.offsetsForTimes(query).forEach((tp, found) -> {
            if (found != null) {
                resolved.put(tp, found.offset());
            }
        });
        return resolved;
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
        ExplorerConsumerGroups.configure(props, "topic-search");
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
