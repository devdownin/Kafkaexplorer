// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The termination rule of a snapshot read.
 *
 * <p>Everything here is about one distinction the caller cannot make for itself: a snapshot that
 * read nothing and a cluster that holds nothing come back as the same empty list. The loop used to
 * stop on the first empty poll, which a fresh consumer very often returns while metadata resolves —
 * so Process Mining profiled whichever subset of topics happened to arrive before that poll and
 * presented it as the answer. Measured on the demo stack: three runs of one identical request
 * sampled 1 topic, then 0, then 6.
 */
class KafkaSnapshotReaderTest {

    private static final String TOPIC_A = "demo.orders.1.received";
    private static final String TOPIC_B = "demo.orders.2.validated";

    private ProbeConsumer mockConsumer;
    private KafkaSnapshotReader reader;

    /**
     * A MockConsumer that records where the reader seeks, and that can be made to answer
     * {@code position()} the way a real client does once it has prefetched.
     */
    private static final class ProbeConsumer extends MockConsumer<String, String> {
        private final Map<TopicPartition, Long> seeks = new HashMap<>();
        private final Set<TopicPartition> endOffsetWithheld = new HashSet<>();
        private final Set<TopicPartition> pauses = new HashSet<>();
        private boolean noOffsetForTime;
        private boolean positionIsPoisoned;

        /**
         * Makes the broker fail to report this partition's end offset, the way a partial response
         * does — the entry is simply absent from the map, which is not the same as a zero.
         */
        void withholdEndOffset(TopicPartition partition) {
            endOffsetWithheld.add(partition);
        }

        /** Makes {@code offsetsForTimes} answer that no record sits at or after the instant. */
        void answerNoOffsetForTime() {
            noOffsetForTime = true;
        }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
            Map<TopicPartition, Long> ends = new HashMap<>(super.endOffsets(partitions));
            endOffsetWithheld.forEach(ends::remove);
            return ends;
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(
                Map<TopicPartition, Long> timestampsToSearch) {
            if (!noOffsetForTime) {
                return super.offsetsForTimes(timestampsToSearch);
            }
            // A real client puts the partition in the map with a null value rather than leaving it
            // out, which is the shape the reader has to survive.
            Map<TopicPartition, OffsetAndTimestamp> answer = new HashMap<>();
            timestampsToSearch.keySet().forEach(tp -> answer.put(tp, null));
            return answer;
        }

        ProbeConsumer() {
            super("earliest");
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            seeks.put(partition, offset);
            super.seek(partition, offset);
        }

        @Override
        public synchronized void pause(Collection<TopicPartition> partitions) {
            pauses.addAll(partitions);
            super.pause(partitions);
        }

        /**
         * The reader must never ask this. {@code position()} is the client's own idea of where it
         * is, and it is unusable here under either implementation: on the classic consumer it is
         * the prefetch position and runs ahead of what has been delivered, and on the KIP-848
         * consumer a seek is applied asynchronously, so reading it back can still answer the end of
         * the log. Both were measured, and each produced a different wrong answer from the same
         * code. Throwing is what pins "does not ask" — the reader catches it, so a caller that
         * consults this ends up with a short read rather than an error, which is exactly the
         * silent-truncation shape these tests exist to forbid.
         */
        @Override
        public synchronized long position(TopicPartition partition) {
            if (positionIsPoisoned) {
                throw new IllegalStateException("the read must not ask the consumer where it is");
            }
            return super.position(partition);
        }
    }

    @BeforeEach
    void setUp() {
        mockConsumer = new ProbeConsumer();
        KafkaConfig kafkaConfig = new KafkaConfig();
        ProcessMiningConfig processMiningConfig = new ProcessMiningConfig();
        reader = new KafkaSnapshotReader(kafkaConfig, null, processMiningConfig,
            new PayloadDigestService(processMiningConfig)) {
            @Override
            @SuppressWarnings("unchecked")
            protected <V> Consumer<String, V> createConsumer(Properties props) {
                return (Consumer<String, V>) mockConsumer;
            }
        };
    }

    /** One partition per topic, with the log bounds the reader now steers by. */
    private void seedTopology(Map<String, Long> endOffsetByTopic) {
        seedTopology(endOffsetByTopic, 0L);
    }

    /** As above, with a log whose oldest surviving record is {@code beginning} — a trimmed topic. */
    private void seedTopology(Map<String, Long> endOffsetByTopic, long beginning) {
        Map<TopicPartition, Long> beginnings = new HashMap<>();
        Map<TopicPartition, Long> ends = new HashMap<>();
        endOffsetByTopic.forEach((topic, end) -> {
            TopicPartition tp = new TopicPartition(topic, 0);
            mockConsumer.updatePartitions(topic, List.of(
                new PartitionInfo(topic, 0, null, new org.apache.kafka.common.Node[0],
                    new org.apache.kafka.common.Node[0])));
            beginnings.put(tp, beginning);
            ends.put(tp, end);
        });
        mockConsumer.updateBeginningOffsets(beginnings);
        mockConsumer.updateEndOffsets(ends);
    }

    private static ConsumerRecord<String, String> record(String topic, long offset) {
        return new ConsumerRecord<>(topic, 0, offset, "key-" + offset, "{\"id\":\"ORD-" + offset + "\"}");
    }

    /**
     * The defect itself: nothing on the first poll, records on the second. A consumer that has just
     * been assigned its partitions answers exactly like this, and it must not be read as "there is
     * nothing in these topics".
     */
    @Test
    void anEmptyFirstPollIsNotAnExhaustedTopic() {
        seedTopology(Map.of(TOPIC_A, 2L));
        mockConsumer.schedulePollTask(() -> { /* metadata still settling: nothing to hand back */ });
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.addRecord(record(TOPIC_A, 0));
            mockConsumer.addRecord(record(TOPIC_A, 1));
        });

        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(200));

        assertEquals(2, messages.size(),
            "the read gave up on the first empty poll and reported an empty topic that has records");
    }

    /**
     * The multi-topic shape this was found in: one topic answers straight away, the other only
     * after a lull. The old loop kept the first and silently dropped the second — and the analysis
     * that followed concluded, in as many words, that only one topic contained messages.
     */
    @Test
    void aTopicThatAnswersLateIsStillRead() {
        seedTopology(Map.of(TOPIC_A, 1L, TOPIC_B, 1L));
        mockConsumer.schedulePollTask(() -> mockConsumer.addRecord(record(TOPIC_A, 0)));
        mockConsumer.schedulePollTask(() -> { /* a lull between the two topics */ });
        mockConsumer.schedulePollTask(() -> mockConsumer.addRecord(record(TOPIC_B, 0)));

        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A, TOPIC_B), SnapshotConfig.earliest(200));

        assertEquals(List.of(TOPIC_A, TOPIC_B), messages.stream().map(KafkaMessage::topic).sorted().toList(),
            "a topic whose records arrive after a quiet poll must still be sampled");
    }

    /**
     * The end offsets are what ends the read, so a genuinely empty cluster still returns promptly
     * rather than sitting out the whole budget.
     */
    @Test
    void anEmptyTopicEndsTheReadWithoutWaitingOutTheBudget() {
        seedTopology(Map.of(TOPIC_A, 0L));

        long startedAt = System.currentTimeMillis();
        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(200));

        assertTrue(messages.isEmpty());
        assertTrue(System.currentTimeMillis() - startedAt < 5_000L,
            "with nothing to read the offsets say so immediately — no poll needs to be waited on");
    }

    /**
     * The safety net, and the reason it is a net rather than the rule: a broker that stops
     * answering while records remain unread cannot hold the read open for the whole budget.
     */
    @Test
    void givesUpAfterSeveralConsecutiveEmptyPollsRatherThanHanging() {
        seedTopology(Map.of(TOPIC_A, 5L));   // records exist, but none is ever handed back

        long startedAt = System.currentTimeMillis();
        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(200));

        assertTrue(messages.isEmpty());
        assertTrue(System.currentTimeMillis() - startedAt < 10_000L,
            "three empty polls end it — the 30s budget is the outer bound, not the usual wait");
    }

    /**
     * The per-topic cap still holds, and it is what {@code hasUnreadOffsets} consults: a topic with
     * its quota must stop keeping the loop alive on behalf of one that is already finished.
     */
    @Test
    void stopsAtThePerTopicLimit() {
        seedTopology(Map.of(TOPIC_A, 10L));
        mockConsumer.schedulePollTask(() -> {
            for (long offset = 0; offset < 10; offset++) {
                mockConsumer.addRecord(record(TOPIC_A, offset));
            }
        });

        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(3));

        assertEquals(3, messages.size(), "the sample budget is per topic and is still enforced");
    }

    /**
     * A LATEST_N seek is clamped to the partition's first surviving record, never to zero.
     *
     * <p>Retention deletes the oldest segments, so on a trimmed topic offset 0 is gone: seeking
     * there is out of range, the consumer applies {@code auto.offset.reset} — {@code latest} in
     * this mode — and the position jumps to the <em>end</em> of the log. Nothing is delivered and
     * the topic is reported empty while being full. Measured on the demo cluster once its seed data
     * had aged out and been reseeded: five of six topics came back empty, and the sixth was the one
     * whose log still began at 0.
     */
    @Test
    void seeksNoEarlierThanTheOldestSurvivingRecord() {
        seedTopology(Map.of(TOPIC_A, 105L), 100L);   // retention trimmed everything below 100

        reader.read(List.of(TOPIC_A), new SnapshotConfig("LATEST_N", 200, null, null));

        assertEquals(100L, mockConsumer.seeks.get(new TopicPartition(TOPIC_A, 0)),
            "seeking below the log start resets to the end of the log and delivers nothing");
    }

    /**
     * Termination must not consult {@code position()} once polling has started.
     *
     * <p>It is the client's <em>fetch</em> position: the consumer prefetches across every assigned
     * partition in the background and advances it as responses are buffered, not as records are
     * returned. Measured against the demo broker, one poll delivered 2 records of one topic while
     * {@code position()} reported the log end for all eighteen partitions, twenty-odd records still
     * undelivered — so a loop that steers by it stops after a single poll and calls the rest empty.
     * This consumer makes that call fatal, which is the only way to pin "does not ask".
     */
    @Test
    void neverAsksTheConsumerWhereItIs() {
        seedTopology(Map.of(TOPIC_A, 4L));
        mockConsumer.positionIsPoisoned = true;
        mockConsumer.schedulePollTask(() -> {        // delivered two records at a time
            mockConsumer.addRecord(record(TOPIC_A, 0));
            mockConsumer.addRecord(record(TOPIC_A, 1));
        });
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.addRecord(record(TOPIC_A, 2));
            mockConsumer.addRecord(record(TOPIC_A, 3));
        });

        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(200));

        assertEquals(4, messages.size(),
            "the read must terminate on the offsets it was handed, not on where the client has "
                + "prefetched to");
    }

    /** Sanity: the payload really travels, so the counts above are not counting empty shells. */
    @Test
    void carriesThePayloadThrough() {
        seedTopology(Map.of(TOPIC_A, 1L));
        mockConsumer.schedulePollTask(() -> mockConsumer.addRecord(record(TOPIC_A, 0)));

        KafkaMessage message = reader.read(List.of(TOPIC_A), SnapshotConfig.earliest(200)).get(0);

        assertEquals(TOPIC_A, message.topic());
        assertEquals("key-0", message.key());
        assertTrue(message.value().contains("ORD-0"),
            new String(message.value().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    /**
     * A topic the broker describes no partition for costs that topic, and nothing else.
     *
     * <p>It used to cost the whole read: {@code partitionsFor} answers null for a topic that does
     * not exist, {@code .stream()} on it threw, and the catch returned an empty list for every
     * topic in the request. So one deleted topic — or one typo in a selection of eight — produced
     * an analysis of nothing at all, logged as an error nobody reads and rendered as a cluster
     * holding no messages.
     */
    @Test
    void anUnknownTopicCostsItselfAndNotTheWholeRead() {
        seedTopology(Map.of(TOPIC_A, 2L));   // TOPIC_B is deliberately never declared
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.addRecord(record(TOPIC_A, 0));
            mockConsumer.addRecord(record(TOPIC_A, 1));
        });

        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A, TOPIC_B),
            SnapshotConfig.earliest(200));

        assertEquals(2, messages.size(),
            "a topic with no metadata must not take the topics that do have some down with it");
        assertTrue(messages.stream().allMatch(m -> TOPIC_A.equals(m.topic())));
    }

    /**
     * And it says so. "This topic does not resolve here" and "this topic is empty" are the same
     * empty list to a caller, and they send the reader to two different places — a name to fix
     * against a cluster to go and look at.
     */
    @Test
    void theReadNamesTheTopicsItCouldNotResolve() {
        seedTopology(Map.of(TOPIC_A, 0L));   // declared, and holding nothing

        SnapshotRead read = reader.readSnapshot(List.of(TOPIC_A, TOPIC_B),
            SnapshotConfig.earliest(200), null, 20);

        assertEquals(List.of(TOPIC_B), read.unreadableTopics());
        assertEquals(List.of(TOPIC_A), read.emptyTopics());
        assertEquals(0, read.messagesByTopic().get(TOPIC_A),
            "every requested topic is keyed, so a caller can tell an absent row from a zero");
        assertNull(read.readError());
    }

    /**
     * Every topic unknown is still a complete answer rather than a failure — and one that reaches
     * it without spending the silence budget polling an empty assignment.
     */
    @Test
    void aRequestNamingOnlyUnknownTopicsAnswersImmediately() {
        SnapshotRead read = reader.readSnapshot(List.of(TOPIC_A, TOPIC_B),
            SnapshotConfig.earliest(200), null, 20);

        assertTrue(read.isEmpty());
        assertEquals(List.of(TOPIC_A, TOPIC_B), read.unreadableTopics());
        assertTrue(read.emptyTopics().isEmpty());
        assertFalse(read.budgetExhausted(), "nothing was waited for, so nothing timed out");
    }

    /**
     * A topic that has its quota cannot hold the read open on behalf of one that has stopped
     * answering.
     *
     * <p>The silence net exists for exactly this shape: one topic being served normally, another
     * whose partition has no leader and will never deliver. But every poll was counted as activity,
     * including the ones carrying nothing but records of a topic already at its cap — which are
     * discarded a few lines after they arrive. So the clock never expired, and the read waited out
     * the whole 30 s wall clock instead of the 5 s it was given, on the Process Mining hot path.
     *
     * <p>Two things stop it, and the test names both: the busy topic's partitions are paused once
     * it has its quota, so that poll mostly stops happening, and the clock is restarted by a record
     * this read <em>kept</em> rather than by one it was handed. The flooding task reschedules
     * itself, because a flood that runs out would end the test through the very silence it is
     * supposed to prevent.
     */
    @Test
    void aFloodOfRecordsItIsDiscardingDoesNotKeepTheReadAlive() {
        seedTopology(Map.of(TOPIC_A, 500L, TOPIC_B, 10L));
        TopicPartition busy = new TopicPartition(TOPIC_A, 0);
        floodFrom(TOPIC_A);

        long startedAt = System.nanoTime();
        List<KafkaMessage> messages = reader.read(List.of(TOPIC_A, TOPIC_B), SnapshotConfig.earliest(2));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(elapsedMs < 15_000L,
            "the read must give up on its silence budget, not wait out the 30s wall clock: "
                + elapsedMs + "ms");
        assertTrue(mockConsumer.pauses.contains(busy),
            "a topic that has its quota must stop being fetched, not go on costing bandwidth that "
                + "belongs to the topics still wanted");
        assertEquals(2, messages.size(), "the per-topic cap still holds");
    }

    /** Keeps handing the reader records of one topic, poll after poll, for as long as it asks. */
    private void floodFrom(String topic) {
        mockConsumer.schedulePollTask(new Runnable() {
            private long offset;

            @Override
            public void run() {
                mockConsumer.addRecord(record(topic, offset++));
                mockConsumer.schedulePollTask(this);
            }
        });
    }

    /**
     * A partition whose end offset the broker did not report is not read from the start of the log.
     *
     * <p>When {@code offsetsForTimes} answers nothing for a partition, every record it holds
     * predates the instant asked for and the partition must contribute nothing. Saying so as a
     * number means seeking it to its end — but the end offset is itself a measurement, and a
     * missing one used to default to {@code 0}, which says the exact opposite: read the partition
     * from the beginning and hand back records that are all older than the filter. In this mode
     * {@code auto.offset.reset} is {@code earliest}, so leaving the partition unseeked lands in the
     * same place; the end has to be asked for lazily instead.
     *
     * <p>The mock lies the way a real client lies — the entry is absent from the map rather than
     * zero — because a broker answering partially is the only way this state arises.
     */
    @Test
    void aPartitionWithNoKnownEndOffsetIsNotReadFromTheStartOfTheLog() {
        seedTopology(Map.of(TOPIC_A, 5L));
        TopicPartition tp = new TopicPartition(TOPIC_A, 0);
        mockConsumer.withholdEndOffset(tp);
        mockConsumer.answerNoOffsetForTime();

        reader.read(List.of(TOPIC_A), new SnapshotConfig("TIMESTAMP", 100, null, 1_000L));

        assertNotEquals(Long.valueOf(0L), mockConsumer.seeks.get(tp),
            "a partition with nothing to contribute must not be seeked to the beginning of its log");
        assertNull(mockConsumer.seeks.get(tp),
            "with no end offset to name, the seek is left to the client rather than given a number");
    }
}
