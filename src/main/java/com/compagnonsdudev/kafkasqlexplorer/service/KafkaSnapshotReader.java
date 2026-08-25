// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.apache.kafka.clients.consumer.Consumer;
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
import java.util.concurrent.TimeUnit;

@Component
public class KafkaSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(KafkaSnapshotReader.class);

    /**
     * How long one poll waits. Short, because the loop below is driven by the end offsets and not
     * by what any single poll happens to return.
     */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    /**
     * How long the broker may say nothing, while records are known to be missing, before the read
     * gives up — a safety net against an endpoint that has stopped answering, never the normal
     * termination condition. Expressed as time rather than as a count of empty polls: what makes it
     * safe to stop is that nothing has arrived for a while, and a count only means that through
     * whatever the poll timeout happens to be.
     */
    private static final long SILENCE_BUDGET_MS = 5_000L;

    /** Wall-clock budget for one snapshot read. */
    private static final long READ_BUDGET_MS = 30_000L;

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
                              Class<?> valueDeserializer, java.util.function.Consumer<ConsumerRecord<String, V>> handler) {
        Properties props = buildConsumerProperties(config, valueDeserializer);

        Consumer<String, V> consumer = null;
        try {
            consumer = createConsumer(props);

            // Assign all partitions for the given topics
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : topics) {
                List<TopicPartition> tps = consumer.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
                partitions.addAll(tps);
            }
            consumer.assign(partitions);

            // Each mode reports the offset it seeked each partition to, and that is what the loop
            // below starts its cursor from. Asking the consumer instead — `position(tp)` — is what
            // the previous two attempts did, and it is wrong under both client implementations for
            // different reasons: on the classic consumer it is the prefetch position and runs ahead
            // of what has been delivered, and on the KIP-848 consumer (`group.protocol=consumer`,
            // which every bundled stack sets) a seek is applied asynchronously, so reading it back
            // straight away can still answer the end of the log. Seeded from it, the cursor started
            // at the end and the read returned nothing at all. Nobody has to ask where the read
            // begins: this code chose it.
            Map<TopicPartition, Long> startOffsets = switch (config.mode()) {
                case "LATEST_N" -> seekToLatestN(consumer, partitions, config.maxMessages());
                case "TIMESTAMP" -> seekToTimestamp(consumer, partitions, config.fromTimestamp());
                default -> seekToBeginning(consumer, partitions);
            };

            // Termination is driven by the offsets this read has actually been *handed*, and by
            // nothing else. Two things it must not be driven by, both of which were:
            //
            //   - an empty poll. A fresh consumer's first poll very often returns nothing while
            //     metadata resolves and the assignment settles, and `if (records.isEmpty()) break;`
            //     read that as "these topics are exhausted". Three runs of one identical profiling
            //     request sampled 1 topic, then 0, then 6, and nothing distinguishes the outcomes
            //     from outside: a snapshot that read nothing and a cluster holding nothing are the
            //     same empty list. Same defect `KafkaAdminService.drain()` was fixed for.
            //
            //   - `consumer.position(tp)`, which is what drain() compares and which is *not* a
            //     consumption watermark. It is the fetch position: the client prefetches, in the
            //     background and across every assigned partition, and advances it as responses are
            //     buffered — not as records are returned to the caller. Measured against the demo
            //     broker, one poll delivered 2 records of one topic and left position() reporting
            //     the log end for all eighteen partitions, twenty-odd records still sitting
            //     undelivered in the client's buffer. Reading a multi-topic snapshot then stopped
            //     after a single poll, which is the shape the original defect had — one topic
            //     sampled, five reported empty — so fixing only the empty poll left it in place.
            //     drain() is single-topic, which is what has been masking it there.
            //
            // So the read keeps its own per-partition cursor: seeded from the seek, advanced only
            // by records the handler was given, compared against end offsets taken once up front.
            Map<TopicPartition, Long> nextOffsets = new HashMap<>(startOffsets);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            // What this read is about to do, before it does it. Its absence is what made three
            // separate causes of "the topics came back empty" indistinguishable from one another:
            // every one of them ended at the top of the loop below, which is the one exit that has
            // nothing to say. A read that returns nothing must be able to state whether it found no
            // partitions, found them all already at their end offsets, or was handed nothing.
            if (log.isDebugEnabled()) {
                long withRecords = partitions.stream()
                    .filter(tp -> endOffsets.getOrDefault(tp, 0L) > nextOffsets.getOrDefault(tp, 0L))
                    .count();
                log.debug("Snapshot read of {} topic(s): {} partition(s) assigned, {} holding "
                    + "records to read; offsets {}", topics.size(), partitions.size(), withRecords,
                    partitions.stream()
                        .map(tp -> tp + "=" + nextOffsets.get(tp) + ".." + endOffsets.get(tp))
                        .toList());
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_BUDGET_MS);
            Map<String, Integer> collectedByTopic = new HashMap<>();
            topics.forEach(topic -> collectedByTopic.put(topic, 0));
            int maxMessagesPerTopic = config.maxMessages();
            long silentSince = System.nanoTime();

            while (!allTopicsReachedLimit(collectedByTopic, maxMessagesPerTopic)) {
                if (!hasUnreadOffsets(partitions, endOffsets, nextOffsets,
                        collectedByTopic, maxMessagesPerTopic)) {
                    break; // Every partition still wanted has been delivered to its end.
                }
                if (System.nanoTime() >= deadline) {
                    log.debug("Snapshot read budget of {}ms spent for topics {}",
                        READ_BUDGET_MS, topics);
                    break;
                }
                ConsumerRecords<String, V> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    if (System.nanoTime() - silentSince
                            >= TimeUnit.MILLISECONDS.toNanos(SILENCE_BUDGET_MS)) {
                        log.debug("Snapshot read gave up after {}ms without a record for topics {}",
                            SILENCE_BUDGET_MS, topics);
                        break;
                    }
                    continue;
                }
                silentSince = System.nanoTime();
                for (ConsumerRecord<String, V> record : records) {
                    // Advanced whether or not the record is kept: it was delivered either way, and
                    // a cursor that stalled on a topic which has reached its cap would keep the
                    // loop alive for ever.
                    nextOffsets.merge(new TopicPartition(record.topic(), record.partition()),
                        record.offset() + 1, Math::max);
                    int currentCount = collectedByTopic.getOrDefault(record.topic(), 0);
                    if (currentCount >= maxMessagesPerTopic) {
                        continue;
                    }
                    handler.accept(record);
                    collectedByTopic.put(record.topic(), currentCount + 1);
                }
            }
            log.debug("Snapshot read collected {}", collectedByTopic);

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

    /**
     * Seeks back {@code n} records per topic, spread over its partitions.
     *
     * <p><b>Clamped to each partition's beginning offset, never to zero.</b> Retention deletes the
     * oldest segments, so on a topic that has been trimmed offset 0 no longer exists: seeking there
     * is out of range, the consumer applies {@code auto.offset.reset} — {@code latest} in this mode
     * — and the position jumps to the <em>end</em> of the log. Nothing is then delivered, and the
     * read reports an empty topic that is full of records. Measured on the demo cluster after the
     * seed data had aged out and been reseeded: five of six topics came back empty and the sixth,
     * the only one whose log still began at 0, came back complete. It is the same rule
     * {@code KafkaAdminService} already follows for its recent-record seeks, applied here too.
     */
    private <V> Map<TopicPartition, Long> seekToLatestN(Consumer<String, V> consumer,
                                                        List<TopicPartition> partitions, int n) {
        Map<String, Long> partitionsPerTopic = partitions.stream()
            .collect(java.util.stream.Collectors.groupingBy(TopicPartition::topic, java.util.stream.Collectors.counting()));
        // Read rather than inferred from a seekToEnd + position() pair: that pair asks the client
        // where it thinks it is, and this asks the broker where the log actually stops.
        Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions);
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

        Map<TopicPartition, Long> startOffsets = new HashMap<>();
        for (TopicPartition tp : partitions) {
            int perPartition = Math.max(1, (int) Math.ceil(
                (double) n / Math.max(1L, partitionsPerTopic.getOrDefault(tp.topic(), 1L))
            ));
            long endOffset = ends.getOrDefault(tp, 0L);
            long floor = beginnings.getOrDefault(tp, 0L);
            long startOffset = Math.max(floor, endOffset - perPartition);
            consumer.seek(tp, startOffset);
            startOffsets.put(tp, startOffset);
        }
        return startOffsets;
    }

    /**
     * Seeks to the oldest surviving record of each partition, explicitly.
     *
     * <p>{@code seekToBeginning} would do the same thing lazily, but the read needs to *know* where
     * it starts — see the switch in {@link #consume} — and reading that back off the consumer is
     * the thing this class no longer does.
     */
    private <V> Map<TopicPartition, Long> seekToBeginning(Consumer<String, V> consumer,
                                                          List<TopicPartition> partitions) {
        Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions);
        Map<TopicPartition, Long> startOffsets = new HashMap<>();
        for (TopicPartition tp : partitions) {
            long start = beginnings.getOrDefault(tp, 0L);
            consumer.seek(tp, start);
            startOffsets.put(tp, start);
        }
        return startOffsets;
    }

    private <V> Map<TopicPartition, Long> seekToTimestamp(Consumer<String, V> consumer,
                                                          List<TopicPartition> partitions,
                                                          Long fromTimestamp) {
        if (fromTimestamp == null) {
            return seekToBeginning(consumer, partitions);
        }
        Map<TopicPartition, Long> timestampsToSearch = new HashMap<>();
        for (TopicPartition tp : partitions) {
            timestampsToSearch.put(tp, fromTimestamp);
        }
        Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(timestampsToSearch);
        // No offset at or after the requested instant means every record in that partition predates
        // it: the partition has nothing to contribute, and seeking it to the end says so — as an
        // offset the loop can compare rather than as a position it would have to read back.
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

        Map<TopicPartition, Long> startOffsets = new HashMap<>();
        for (TopicPartition tp : partitions) {
            OffsetAndTimestamp oat = offsets.get(tp);
            long start = oat != null ? oat.offset() : ends.getOrDefault(tp, 0L);
            consumer.seek(tp, start);
            startOffsets.put(tp, start);
        }
        return startOffsets;
    }

    private boolean allTopicsReachedLimit(Map<String, Integer> collectedByTopic, int maxMessagesPerTopic) {
        return collectedByTopic.values().stream().allMatch(count -> count >= maxMessagesPerTopic);
    }

    /**
     * Whether any partition this read still wants holds records it has not been handed.
     *
     * <p>Pure, and that is the point: it compares the caller's own cursor — seeded from the seek,
     * advanced only by delivered records — against end offsets taken once. It deliberately does not
     * ask the consumer where it is, because {@code position()} answers about the client's prefetch
     * and not about what this read has received; see the loop above for what that cost.
     *
     * <p>"Still wants" is what the topic cap adds: the budget is per topic, so once a topic has its
     * quota its partitions stop keeping the loop alive — otherwise a busy topic filled in the first
     * poll would hold the read open on behalf of a quiet one already finished.
     *
     * <p>A partition with no known end offset counts as read: the offsets are taken once, up front,
     * and turning a missing entry into an unbounded read is the opposite of the bounding this
     * method exists for.
     */
    private static boolean hasUnreadOffsets(List<TopicPartition> partitions,
                                            Map<TopicPartition, Long> endOffsets,
                                            Map<TopicPartition, Long> nextOffsets,
                                            Map<String, Integer> collectedByTopic,
                                            int maxMessagesPerTopic) {
        for (TopicPartition tp : partitions) {
            if (collectedByTopic.getOrDefault(tp.topic(), 0) >= maxMessagesPerTopic) {
                continue;
            }
            Long end = endOffsets.get(tp);
            Long next = nextOffsets.get(tp);
            if (end != null && next != null && next < end) {
                return true;
            }
        }
        return false;
    }

    /** Seam for tests: overridden to inject a MockConsumer instead of a real one. */
    protected <V> Consumer<String, V> createConsumer(Properties props) {
        return new KafkaConsumer<>(props);
    }
}
