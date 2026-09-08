// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.service.ExplorerConsumerGroups;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the call trail back off {@code explorer.mcp.audit-topic}.
 *
 * <p>The console's feed is a bounded ring: it answers "what is happening" and cannot answer "what
 * happened last Tuesday". The button that asks the second question answered {@code 501} for three
 * phases, which was better than a route that 404s and still not an answer. This is the answer.
 *
 * <p><b>What it reports about its own reach is the point.</b> The scan is bounded — a topic with a
 * year of calls cannot be read into a response — so a window that returned nothing is either a
 * window in which nothing happened or a window the scan never reached, and those are opposite
 * conclusions from the same empty list. {@link Replay} carries {@code scanReachedWindowStart}
 * exactly so the two cannot be confused, and it is computed from the oldest record actually seen
 * rather than assumed from the record count.
 *
 * <p><b>It seeks by timestamp, not from the beginning.</b> {@code offsetsForTimes} puts the
 * consumer at the window's start on the broker's own index, so a narrow window over an old topic
 * costs a seek rather than a full scan. A partition whose index has no offset at that time has
 * nothing at or after it, which is not an error — it is the honest empty.
 */
public class McpAuditReplayService {

    private static final Logger log = LoggerFactory.getLogger(McpAuditReplayService.class);

    /** Records read per replay, whatever the window. A response is not an archive. */
    static final int MAX_RECORDS = 2000;

    /** How long the scan may poll before giving up on a partition that is not answering. */
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration BUDGET = Duration.ofSeconds(10);

    /**
     * A replayed window.
     *
     * @param calls                  the records, oldest first
     * @param recordsScanned         how many records the scan read, matching or not
     * @param scanReachedWindowStart true when the scan saw a record at or before {@code from}, so an
     *                               empty result means "nothing happened", not "not reached"
     * @param topicExists            false when the topic has never been created — no call has ever
     *                               been appended, which is not the same as none in this window
     * @param warnings               what bounded or degraded this read
     */
    public record Replay(
            List<JsonNode> calls,
            int recordsScanned,
            boolean scanReachedWindowStart,
            boolean topicExists,
            List<String> warnings
    ) {
        static Replay noTopic(String topic) {
            return new Replay(List.of(), 0, false, false, List.of(
                    "Topic " + topic + " does not exist yet: no MCP call has ever been appended to "
                            + "it. This is not an empty window — it is an empty trail."));
        }
    }

    private final KafkaConfig kafkaConfig;
    private final McpProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpAuditReplayService(KafkaConfig kafkaConfig, McpProperties properties) {
        this.kafkaConfig = kafkaConfig;
        this.properties = properties;
    }

    /** Every call appended between {@code from} and {@code to}, oldest first. */
    public Replay replay(Instant from, Instant to) {
        String topic = properties.getAuditTopic();
        List<String> warnings = new ArrayList<>();

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        // A fresh group that commits nothing: reading the trail must not move anyone's offsets.
        ExplorerConsumerGroups.configure(props, "mcp-audit-replay");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (Consumer<byte[], byte[]> consumer = createConsumer(props)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null || infos.isEmpty()) {
                return Replay.noTopic(topic);
            }

            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);

            // Seek each partition to the window's start on the broker's own time index. A partition
            // with no offset at that time holds nothing at or after it, so it is left at its end.
            Map<TopicPartition, Long> targets = new HashMap<>();
            partitions.forEach(partition -> targets.put(partition, from.toEpochMilli()));
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);

            boolean reachedStart = true;
            for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp> entry
                    : consumer.offsetsForTimes(targets).entrySet()) {
                TopicPartition partition = entry.getKey();
                long beginning = beginningOffsets.getOrDefault(partition, 0L);
                if (entry.getValue() == null) {
                    // No record at or after the window's start: this partition has nothing to give.
                    // The window's start is still covered — everything in it is simply older than
                    // this partition's newest record — unless retention has eaten into it.
                    consumer.seek(partition, endOffsets.getOrDefault(partition, 0L));
                    reachedStart = reachedStart && beginning == 0L;
                    continue;
                }
                consumer.seek(partition, entry.getValue().offset());
                // The seek landing exactly on the partition's first surviving record is the case
                // retention hides: there is nothing older left, so whether anything in the window
                // preceded it cannot be known. An offset strictly past the beginning means an older
                // record survives, which proves the window's start is inside what is held.
                reachedStart = reachedStart
                        && (beginning == 0L || entry.getValue().offset() > beginning);
            }

            List<JsonNode> calls = new ArrayList<>();
            int scanned = 0;
            long deadline = System.currentTimeMillis() + BUDGET.toMillis();
            boolean capped = false;
            while (System.currentTimeMillis() < deadline && calls.size() < MAX_RECORDS) {
                ConsumerRecords<byte[], byte[]> batch = consumer.poll(POLL);
                if (batch.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<byte[], byte[]> record : batch) {
                    scanned++;
                    if (record.timestamp() > to.toEpochMilli()) {
                        continue;
                    }
                    if (calls.size() >= MAX_RECORDS) {
                        capped = true;
                        break;
                    }
                    try {
                        calls.add(mapper.readTree(record.value()));
                    } catch (Exception e) {
                        warnings.add("One record at offset " + record.offset() + " is not readable "
                                + "JSON and was skipped.");
                    }
                }
                if (capped) {
                    break;
                }
            }
            if (capped || calls.size() >= MAX_RECORDS) {
                warnings.add("The replay stopped at " + MAX_RECORDS + " records; narrow the window "
                        + "to see the rest.");
            }
            if (!reachedStart) {
                warnings.add("The scan did not reach the start of the window — retention has "
                        + "removed records from it, so an absence here is not evidence of absence.");
            }
            return new Replay(calls, scanned, reachedStart, true, warnings);

        } catch (Exception e) {
            log.warn("MCP audit replay failed: {}", e.getMessage());
            return new Replay(List.of(), 0, false, true,
                    List.of("The audit topic could not be read: " + e.getMessage()));
        }
    }

    /** Overridable so the test can drive a mock consumer without a broker. */
    protected Consumer<byte[], byte[]> createConsumer(Properties props) {
        return new KafkaConsumer<>(props);
    }
}
