// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivity;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dashboard's activity curve, driven through a mocked AdminClient that behaves like a log.
 *
 * <p>The series is built from offsets alone — {@code listOffsets(forTimestamp)} per bucket
 * boundary, subtracted pairwise — so the fixture here is a real answer to that question rather
 * than a canned response: each partition is a list of record timestamps, and a boundary resolves
 * to the first record at or after it, exactly as a broker resolves one, or to nothing when every
 * record predates it.
 *
 * <p>What is pinned is mostly what the curve must <b>not</b> say. A topic nobody produced to and a
 * topic nobody could read are two different answers and only one of them is a row of zeros; a
 * window the retention has eaten into is reported as such rather than drawn flat; and a partition
 * that did not answer makes the series a floor, which the note says out loud.
 */
class KafkaAdminServiceActivityTest {

    private static final Node NODE = new Node(1, "broker", 9092);
    private static final String TOPIC = "demo.orders.1.received";
    private static final long HOUR = 3_600_000L;
    /** A fixed instant: the window is derived from the clock, so the clock is an input here. */
    private static final long NOW = 1_700_000_000_000L;
    private static final long BUCKET = HOUR;
    private static final long END = (NOW / BUCKET) * BUCKET;
    private static final long START = END - 4 * BUCKET;

    private AdminClient admin;
    private KafkaAdminService service;
    /** partition → the log it holds. */
    private final Map<TopicPartition, PartitionLog> logs = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        admin = mock(AdminClient.class);
        service = new KafkaAdminService(new KafkaConfig());
        service.setAdminClientForTest(admin);
        when(admin.listOffsets(any(Map.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<TopicPartition, OffsetSpec> request = (Map<TopicPartition, OffsetSpec>) invocation.getArgument(0);
            Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> answers = new LinkedHashMap<>();
            request.forEach((tp, spec) -> answers.put(tp, resolve(tp, spec)));
            return new ListOffsetsResult(answers);
        });
    }

    // ── the fake log ─────────────────────────────────────────────────────────

    /**
     * One partition: the offset its log starts at, and the timestamp of each record it still
     * holds. {@code beginOffset > 0} is what a partition trimmed by retention looks like.
     */
    private record PartitionLog(long beginOffset, List<Long> timestamps, boolean readable) {}

    private void partition(int index, long beginOffset, long... timestamps) {
        logs.put(new TopicPartition(TOPIC, index), new PartitionLog(beginOffset,
                java.util.Arrays.stream(timestamps).boxed().toList(), true));
    }

    private void unreadablePartition(int index) {
        logs.put(new TopicPartition(TOPIC, index), new PartitionLog(0, List.of(), false));
    }

    /** The broker's own answer: earliest, latest, or the first record at or after a timestamp. */
    private KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo> resolve(TopicPartition tp, OffsetSpec spec) {
        PartitionLog log = logs.get(tp);
        if (log == null || !log.readable()) {
            KafkaFutureImpl<ListOffsetsResult.ListOffsetsResultInfo> failed = new KafkaFutureImpl<>();
            failed.completeExceptionally(new org.apache.kafka.common.errors.LeaderNotAvailableException(
                    "no leader for " + tp));
            return failed;
        }
        long end = log.beginOffset() + log.timestamps().size();
        String kind = spec.getClass().getSimpleName();
        if (kind.equals("EarliestSpec")) return info(log.beginOffset(), -1L);
        if (kind.equals("LatestSpec")) return info(end, -1L);

        long target = timestampOf(spec);
        for (int i = 0; i < log.timestamps().size(); i++) {
            if (log.timestamps().get(i) >= target) {
                return info(log.beginOffset() + i, log.timestamps().get(i));
            }
        }
        // Every record predates the boundary: the broker answers with no offset at all, which is
        // the case a caller must not read as "offset 0".
        return info(-1L, -1L);
    }

    private static KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo> info(long offset, long timestamp) {
        return KafkaFuture.completedFuture(
                new ListOffsetsResult.ListOffsetsResultInfo(offset, timestamp, Optional.empty()));
    }

    /** {@code TimestampSpec.timestamp()} is package-private in kafka-clients. */
    private static long timestampOf(OffsetSpec spec) {
        try {
            Method method = spec.getClass().getDeclaredMethod("timestamp");
            method.setAccessible(true);
            return (long) method.invoke(spec);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the timestamp out of " + spec, e);
        }
    }

    private void topicsExist(String... names) {
        Map<String, KafkaFuture<TopicDescription>> values = new LinkedHashMap<>();
        for (String name : names) {
            List<TopicPartitionInfo> partitions = logs.keySet().stream()
                    .filter(tp -> tp.topic().equals(name))
                    .map(tp -> new TopicPartitionInfo(tp.partition(), NODE, List.of(NODE), List.of(NODE)))
                    .collect(Collectors.toList());
            if (partitions.isEmpty()) {
                partitions = List.of(new TopicPartitionInfo(0, NODE, List.of(NODE), List.of(NODE)));
            }
            values.put(name, KafkaFuture.completedFuture(new TopicDescription(name, false, partitions)));
        }
        describeAnswers(values);
    }

    private void describeAnswers(Map<String, KafkaFuture<TopicDescription>> values) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(values);
        when(admin.describeTopics(anyCollection())).thenReturn(result);
    }

    private TopicActivity read(String topic) {
        return read(List.of(topic), 4 * HOUR, 4, 10_000).topics().get(topic);
    }

    private TopicActivityResponse read(List<String> topics, long windowMs, int buckets, int maxLookups) {
        return service.getTopicActivity(topics, windowMs, buckets, maxLookups, NOW);
    }

    private static List<Long> counts(long... values) {
        List<Long> out = new ArrayList<>();
        for (long value : values) out.add(value);
        return out;
    }

    // ── what the curve counts ────────────────────────────────────────────────

    @Test
    void countsWhatEachBucketReceived() {
        partition(0, 0, START + 10 * 60_000, START + 20 * 60_000,          // bucket 0
                        START + 2 * HOUR + 60_000, START + 2 * HOUR + 120_000, START + 2 * HOUR + 180_000,
                        START + 3 * HOUR + 60_000);                        // buckets 2 and 3
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertEquals(counts(2, 0, 3, 1), activity.counts());
        assertEquals(6L, activity.total());
        assertEquals(START, activity.windowStartMs());
        assertEquals(END, activity.windowEndMs());
        assertEquals(BUCKET, activity.bucketMs());
        assertTrue(activity.complete());
        assertNull(activity.note());
    }

    @Test
    void sumsThePartitionsOfOneTopic() {
        partition(0, 0, START + 10 * 60_000);
        partition(1, 0, START + HOUR + 60_000, START + HOUR + 120_000);
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertEquals(counts(1, 2, 0, 0), activity.counts());
        assertEquals(2, activity.partitionsMeasured());
        assertEquals(2, activity.partitionsTotal());
    }

    /** A topic nobody produced to is a flat line, and it is a measurement — not a failure. */
    @Test
    void aQuietTopicIsAvailableAndFlat() {
        partition(0, 0);
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertTrue(activity.available());
        assertEquals(counts(0, 0, 0, 0), activity.counts());
        assertEquals(0L, activity.total());
        assertNull(activity.coveredFromMs());
    }

    /** Records produced before the window are outside it, and must not land in its first bucket. */
    @Test
    void ignoresWhatWasProducedBeforeTheWindow() {
        partition(0, 0, START - 5 * HOUR, START - HOUR, START + 30 * 60_000);
        topicsExist(TOPIC);

        assertEquals(counts(1, 0, 0, 0), read(TOPIC).counts());
    }

    // ── what the curve does not measure ──────────────────────────────────────

    /**
     * The half of the window retention has deleted would otherwise read as a quiet night: the same
     * zeros, with nothing saying they describe records that existed.
     */
    @Test
    void reportsAWindowRetentionHasEatenInto() {
        partition(0, 100, START + 2 * HOUR + 30 * 60_000, START + 3 * HOUR + 60_000);
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertEquals(START + 2 * HOUR + 30 * 60_000, activity.coveredFromMs());
        assertFalse(activity.complete());
        assertTrue(activity.note().contains("retention"), activity.note());
        assertEquals(counts(0, 0, 1, 1), activity.counts());
    }

    /** A trimmed log whose oldest record still predates the window covers the whole window. */
    @Test
    void doesNotClaimRetentionWhenTheWholeWindowSurvives() {
        partition(0, 100, START - HOUR, START + 30 * 60_000);
        topicsExist(TOPIC);

        assertNull(read(TOPIC).coveredFromMs());
    }

    @Test
    void aPartitionThatDidNotAnswerMakesTheSeriesAFloor() {
        partition(0, 0, START + 10 * 60_000, START + 20 * 60_000);
        unreadablePartition(1);
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertTrue(activity.available());
        assertEquals(counts(2, 0, 0, 0), activity.counts());
        assertEquals(1, activity.partitionsMeasured());
        assertEquals(2, activity.partitionsTotal());
        assertTrue(activity.note().contains("floor"), activity.note());
    }

    /** Nothing readable is not a quiet topic, and the difference has to survive to the UI. */
    @Test
    void aTopicThatCouldNotBeReadIsNotAFlatLine() {
        unreadablePartition(0);
        topicsExist(TOPIC);

        TopicActivity activity = read(TOPIC);

        assertFalse(activity.available());
        assertTrue(activity.counts().isEmpty());
        assertNotNull(activity.note());
        assertTrue(activity.note().contains("partition"), activity.note());
    }

    @Test
    void oneUnknownTopicCostsItsOwnRowOnly() {
        partition(0, 0, START + 10 * 60_000);
        KafkaFutureImpl<TopicDescription> missing = new KafkaFutureImpl<>();
        missing.completeExceptionally(new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("gone"));
        Map<String, KafkaFuture<TopicDescription>> values = new LinkedHashMap<>();
        values.put(TOPIC, KafkaFuture.completedFuture(new TopicDescription(TOPIC, false,
                List.of(new TopicPartitionInfo(0, NODE, List.of(NODE), List.of(NODE))))));
        values.put("gone.topic", missing);
        describeAnswers(values);

        TopicActivityResponse response = read(List.of(TOPIC, "gone.topic"), 4 * HOUR, 4, 10_000);

        assertTrue(response.available());
        assertEquals(1L, response.topics().get(TOPIC).total());
        assertFalse(response.topics().containsKey("gone.topic"));
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("gone.topic")), response.warnings().toString());
    }

    // ── bounds ───────────────────────────────────────────────────────────────

    /** What a budget cuts is named: an absent curve otherwise reads as a topic without traffic. */
    @Test
    void namesTheTopicsTheLookupBudgetLeftOut() {
        partition(0, 0, START + 10 * 60_000);
        topicsExist(TOPIC, "demo.orders.2.validated");

        TopicActivityResponse response = read(List.of(TOPIC, "demo.orders.2.validated"), 4 * HOUR, 4, 1);

        assertTrue(response.topics().containsKey(TOPIC));
        assertFalse(response.topics().containsKey("demo.orders.2.validated"));
        assertTrue(response.warnings().stream()
                .anyMatch(w -> w.contains("demo.orders.2.validated") && w.contains("activity-max-lookups")),
                response.warnings().toString());
    }

    @Test
    void clampsAWindowAndABucketCountThatMakeNoCurve() {
        partition(0, 0);
        topicsExist(TOPIC);

        TopicActivityResponse wide = read(List.of(TOPIC), 400L * 24 * HOUR, 1000, 100_000);
        assertEquals(KafkaAdminService.ACTIVITY_MAX_BUCKETS, wide.buckets());
        assertEquals(KafkaAdminService.ACTIVITY_MAX_WINDOW_MS / KafkaAdminService.ACTIVITY_MAX_BUCKETS,
                wide.bucketMs());

        TopicActivityResponse narrow = read(List.of(TOPIC), 1L, 1, 100_000);
        assertEquals(KafkaAdminService.ACTIVITY_MIN_BUCKETS, narrow.buckets());
        assertEquals(KafkaAdminService.ACTIVITY_MIN_WINDOW_MS / KafkaAdminService.ACTIVITY_MIN_BUCKETS,
                narrow.bucketMs());
        assertEquals(narrow.buckets(), narrow.topics().get(TOPIC).counts().size());
    }

    @Test
    void asksTheBrokerForNothingWhenAskedForNoTopic() {
        TopicActivityResponse response = read(List.of(), 4 * HOUR, 4, 10_000);

        assertTrue(response.available());
        assertTrue(response.topics().isEmpty());
        org.mockito.Mockito.verify(admin, org.mockito.Mockito.never()).describeTopics(any(Collection.class));
    }
}
