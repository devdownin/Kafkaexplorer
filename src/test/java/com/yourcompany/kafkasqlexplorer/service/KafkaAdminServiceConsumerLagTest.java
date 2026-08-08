package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.yourcompany.kafkasqlexplorer.domain.PartitionLag;
import com.yourcompany.kafkasqlexplorer.domain.TopicConsumers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListGroupsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.GroupType;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Consumer-group lag on one topic, driven through a mocked AdminClient.
 *
 * <p>What is worth pinning here is not the arithmetic but the distinctions the record makes: a
 * partition with no committed offset is {@code null}, not zero; a group with no position at all on
 * the topic is absent rather than listed at zero; and a read that could not be performed reports a
 * reason instead of an empty list, which would claim nobody consumes the topic.
 */
class KafkaAdminServiceConsumerLagTest {

    private static final String TOPIC = "demo.orders";
    private static final Node NODE = new Node(1, "broker", 9092);

    private AdminClient admin;
    private KafkaAdminService service;

    @BeforeEach
    void setUp() {
        admin = mock(AdminClient.class);
        service = new KafkaAdminService(new KafkaConfig());
        service.setAdminClientForTest(admin);
    }

    // ---- harness ------------------------------------------------------------------------

    private void topicWithPartitions(int count) {
        List<TopicPartitionInfo> partitions = IntStream.range(0, count)
                .mapToObj(i -> new TopicPartitionInfo(i, NODE, List.of(NODE), List.of(NODE)))
                .collect(Collectors.toList());
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
                Map.of(TOPIC, new TopicDescription(TOPIC, false, partitions))));
        when(admin.describeTopics(anyCollection())).thenReturn(result);
    }

    private void groups(GroupListing... listings) {
        ListGroupsResult result = mock(ListGroupsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(List.of(listings)));
        when(admin.listGroups(any())).thenReturn(result);
    }

    private static GroupListing group(String id, GroupType type) {
        return new GroupListing(id, Optional.of(type), type.toString(), Optional.of(GroupState.STABLE));
    }

    private void described(Map<String, ConsumerGroupDescription> descriptions) {
        DescribeConsumerGroupsResult result = mock(DescribeConsumerGroupsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(descriptions));
        when(admin.describeConsumerGroups(anyCollection())).thenReturn(result);
    }

    private static ConsumerGroupDescription description(String groupId, MemberDescription... members) {
        return new ConsumerGroupDescription(groupId, false, List.of(members), "range",
                GroupType.CLASSIC, GroupState.STABLE, NODE, Set.of(), Optional.empty(), Optional.empty());
    }

    private static MemberDescription member(String id, String host, int... assignedPartitions) {
        Set<TopicPartition> assignment = IntStream.of(assignedPartitions)
                .mapToObj(p -> new TopicPartition(TOPIC, p))
                .collect(Collectors.toSet());
        return new MemberDescription(id, Optional.empty(), id + "-client", host,
                new MemberAssignment(assignment));
    }

    private void committed(Map<String, Map<Integer, Long>> offsetsByGroup) {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> all = new LinkedHashMap<>();
        offsetsByGroup.forEach((groupId, byPartition) -> {
            Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
            byPartition.forEach((partition, offset) ->
                    offsets.put(new TopicPartition(TOPIC, partition),
                            offset == null ? null : new OffsetAndMetadata(offset)));
            all.put(groupId, offsets);
        });
        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(all));
        when(admin.listConsumerGroupOffsets(any(Map.class))).thenReturn(result);
    }

    private void endOffsets(Map<Integer, Long> byPartition) {
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = new LinkedHashMap<>();
        byPartition.forEach((partition, offset) -> ends.put(new TopicPartition(TOPIC, partition),
                new ListOffsetsResult.ListOffsetsResultInfo(offset, -1L, Optional.empty())));
        ListOffsetsResult result = mock(ListOffsetsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(ends));
        when(admin.listOffsets(any(Map.class))).thenReturn(result);
    }

    private static PartitionLag partition(TopicConsumers consumers, String groupId, int partition) {
        return consumers.groups().stream()
                .filter(g -> g.groupId().equals(groupId))
                .flatMap(g -> g.partitions().stream())
                .filter(p -> p.partition() == partition)
                .findFirst().orElseThrow();
    }

    // ---- tests --------------------------------------------------------------------------

    @Test
    void reportsLagPerPartitionAndItsTotal() {
        topicWithPartitions(2);
        groups(group("orders-service", GroupType.CLASSIC));
        described(Map.of("orders-service", description("orders-service", member("m1", "h1", 0, 1))));
        committed(Map.of("orders-service", Map.of(0, 90L, 1, 200L)));
        endOffsets(Map.of(0, 100L, 1, 200L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(1, consumers.groups().size());
        ConsumerGroupLag group = consumers.groups().get(0);
        assertEquals("orders-service", group.groupId());
        assertEquals(10L, group.totalLag());
        assertEquals(0, group.partitionsWithoutCommit());
        assertEquals(10L, partition(consumers, "orders-service", 0).lag());
        assertEquals(0L, partition(consumers, "orders-service", 1).lag());
        assertEquals("m1", partition(consumers, "orders-service", 0).memberId());
        assertEquals("h1", partition(consumers, "orders-service", 0).host());
        assertEquals(1, group.members());
        assertEquals(1, group.assignedMembers());
    }

    @Test
    void aPartitionWithoutACommitHasNoLagRatherThanZero() {
        topicWithPartitions(3);
        groups(group("partial", GroupType.CONSUMER));
        described(Map.of("partial", description("partial", member("m1", "h1", 0))));
        // Partition 2 was never committed on: absent from the map, not committed at 0.
        committed(Map.of("partial", Map.of(0, 50L, 1, 10L)));
        endOffsets(Map.of(0, 100L, 1, 100L, 2, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);
        ConsumerGroupLag group = consumers.groups().get(0);

        assertNull(partition(consumers, "partial", 2).committedOffset());
        assertNull(partition(consumers, "partial", 2).lag(),
                "a partition nobody committed on is not caught up at zero, it is unread");
        assertEquals(1, group.partitionsWithoutCommit());
        // The total counts only what exists — 50 + 90, never the 100 of the unread partition.
        assertEquals(140L, group.totalLag());
    }

    @Test
    void aGroupWithNoPositionOnTheTopicIsNotListed() {
        topicWithPartitions(1);
        groups(group("elsewhere", GroupType.CLASSIC), group("here", GroupType.CLASSIC));
        described(Map.of("here", description("here", member("m1", "h1", 0))));
        Map<String, Map<Integer, Long>> offsets = new LinkedHashMap<>();
        offsets.put("elsewhere", Map.of());
        offsets.put("here", Map.of(0, 5L));
        committed(offsets);
        endOffsets(Map.of(0, 5L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(List.of("here"), consumers.groups().stream().map(ConsumerGroupLag::groupId).toList());
        // …but the scope is still stated, so an empty list can never pass for "nobody consumes it".
        assertEquals(2, consumers.groupsExamined());
        assertEquals(2, consumers.groupsInCluster());
    }

    @Test
    void ordersTheWorstLagFirst() {
        topicWithPartitions(1);
        groups(group("a-small", GroupType.CLASSIC), group("z-huge", GroupType.CLASSIC));
        described(Map.of(
                "a-small", description("a-small", member("m1", "h1", 0)),
                "z-huge", description("z-huge", member("m2", "h2", 0))));
        Map<String, Map<Integer, Long>> offsets = new LinkedHashMap<>();
        offsets.put("a-small", Map.of(0, 990L));
        offsets.put("z-huge", Map.of(0, 10L));
        committed(offsets);
        endOffsets(Map.of(0, 1000L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(List.of("z-huge", "a-small"),
                consumers.groups().stream().map(ConsumerGroupLag::groupId).toList());
    }

    @Test
    void skipsShareGroupsAndSaysSo() {
        topicWithPartitions(1);
        groups(group("classic", GroupType.CLASSIC), group("shared", GroupType.SHARE));
        described(Map.of("classic", description("classic", member("m1", "h1", 0))));
        committed(Map.of("classic", Map.of(0, 1L)));
        endOffsets(Map.of(0, 3L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(1, consumers.groupsExamined());
        assertEquals(2, consumers.groupsInCluster());
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("share group")),
                "a group skipped for a structural reason has to be named: " + consumers.warnings());
    }

    @Test
    void capsTheNumberOfGroupsReadAndSaysSo() {
        topicWithPartitions(1);
        groups(group("g1", GroupType.CLASSIC), group("g2", GroupType.CLASSIC), group("g3", GroupType.CLASSIC));
        described(Map.of("g1", description("g1", member("m1", "h1", 0))));
        committed(Map.of("g1", Map.of(0, 1L)));
        endOffsets(Map.of(0, 2L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 1);

        assertTrue(consumers.truncated());
        assertEquals(1, consumers.groupsExamined());
        assertEquals(3, consumers.groupsInCluster());
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("would not appear here")));
    }

    @Test
    void reportsANegativeLagInsteadOfHidingIt() {
        topicWithPartitions(1);
        groups(group("reset", GroupType.CLASSIC));
        described(Map.of("reset", description("reset", member("m1", "h1", 0))));
        committed(Map.of("reset", Map.of(0, 500L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(-400L, partition(consumers, "reset", 0).lag());
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("past the end")),
                "a committed offset beyond the end deserves an explanation: " + consumers.warnings());
    }

    @Test
    void stillReportsLagWhenTheGroupsCannotBeDescribed() {
        topicWithPartitions(1);
        groups(group("orders", GroupType.CLASSIC));
        when(admin.describeConsumerGroups(anyCollection()))
                .thenThrow(new IllegalStateException("coordinator unavailable"));
        committed(Map.of("orders", Map.of(0, 40L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(60L, consumers.groups().get(0).totalLag(), "the lag itself does not need members");
        assertEquals(0, consumers.groups().get(0).assignedMembers());
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("members could not be read")));
    }

    @Test
    void saysWhyRatherThanReturningAnEmptyListWhenTheGroupsCannotBeListed() {
        topicWithPartitions(1);
        when(admin.listGroups(any())).thenThrow(new IllegalStateException("broker unreachable"));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertTrue(consumers.groups().isEmpty());
        assertFalse(consumers.warnings().isEmpty(),
                "an empty list with no warning would claim nobody consumes the topic");
        assertTrue(consumers.warnings().get(0).contains("broker unreachable"));
    }

    @Test
    void saysWhenTheTopicDoesNotExist() {
        DescribeTopicsResult empty = mock(DescribeTopicsResult.class);
        when(empty.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of()));
        when(admin.describeTopics(anyCollection())).thenReturn(empty);

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertTrue(consumers.warnings().get(0).contains("does not exist"));
    }
}
