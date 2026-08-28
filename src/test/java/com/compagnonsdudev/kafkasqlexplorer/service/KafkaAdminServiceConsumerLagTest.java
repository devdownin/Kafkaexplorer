// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
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
import java.util.concurrent.ExecutionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private ListConsumerGroupOffsetsResult offsetsResult;
    private Map<String, KafkaFuture<ConsumerGroupDescription>> describeFutures = new LinkedHashMap<>();

    private void groups(GroupListing... listings) {
        ListGroupsResult result = mock(ListGroupsResult.class);
        when(result.all()).thenReturn(KafkaFuture.completedFuture(List.of(listings)));
        when(admin.listGroups(any())).thenReturn(result);
    }

    /** Un groupe sans membre : EMPTY est ce qu'un groupe abandonné laisse derrière lui. */
    private static GroupListing dormantGroup(String id) {
        return new GroupListing(id, Optional.of(GroupType.CLASSIC), GroupType.CLASSIC.toString(),
                Optional.of(GroupState.EMPTY));
    }

    private static GroupListing group(String id, GroupType type) {
        return new GroupListing(id, Optional.of(type), type.toString(), Optional.of(GroupState.STABLE));
    }

    /*
     * Un futur par groupe : le service ne lit plus `.all()`, qui échouait en bloc dès qu'un seul
     * groupe échouait — et coûtait alors les membres des deux cents autres.
     */
    private void described(Map<String, ConsumerGroupDescription> descriptions) {
        DescribeConsumerGroupsResult result = mock(DescribeConsumerGroupsResult.class);
        describeFutures = new LinkedHashMap<>();
        descriptions.forEach((id, description) -> describeFutures.put(id, KafkaFuture.completedFuture(description)));
        when(result.describedGroups()).thenReturn(describeFutures);
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
        // The 9-argument constructor is the only one kafka-clients has not deprecated for
        // removal; the four shorter overloads all are. Spelled out with the accessor names
        // because a call site of nine arguments, four of them Optional.empty(), is otherwise
        // unreadable: groupInstanceId, rackId, targetAssignment, memberEpoch and upgraded
        // are all absent from what this helper needs to describe.
        return new MemberDescription(
                id,                             // consumerId
                Optional.empty(),               // groupInstanceId
                Optional.empty(),               // rackId
                id + "-client",                 // clientId
                host,
                new MemberAssignment(assignment),
                Optional.empty(),               // targetAssignment
                Optional.empty(),               // memberEpoch
                Optional.empty());              // upgraded
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
        // Un groupe sans entrée n'a pas d'offset commité sur ce topic : c'est une réponse, pas un
        // échec. La distinction compte, puisque l'échec produit désormais une ligne à part.
        when(result.partitionsToOffsetAndMetadata(anyString()))
                .thenReturn(KafkaFuture.completedFuture(Map.of()));
        all.forEach((groupId, offsets) ->
                when(result.partitionsToOffsetAndMetadata(groupId))
                        .thenReturn(KafkaFuture.completedFuture(offsets)));
        when(result.all()).thenReturn(KafkaFuture.completedFuture(all));
        when(admin.listConsumerGroupOffsets(any(Map.class))).thenReturn(result);
        offsetsResult = result;
    }

    /** Un groupe dont les offsets ne se lisent pas — l'appel entier ne doit pas tomber avec lui. */
    private void offsetsFailFor(String groupId, String reason) {
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failed = mock(KafkaFuture.class);
        try {
            when(failed.get(anyLong(), any())).thenThrow(
                    new ExecutionException(new org.apache.kafka.common.errors.TimeoutException(reason)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(offsetsResult.partitionsToOffsetAndMetadata(groupId)).thenReturn(failed);
    }

    /** Un groupe dont la description ne se lit pas : on perd ses membres, pas ceux des autres. */
    private void describeFailsFor(String groupId, String reason) {
        KafkaFuture<ConsumerGroupDescription> failed = mock(KafkaFuture.class);
        try {
            when(failed.get(anyLong(), any())).thenThrow(
                    new ExecutionException(new org.apache.kafka.common.errors.TimeoutException(reason)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        describeFutures.put(groupId, failed);
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

    /*
     * L'application ne doit pas figurer parmi les consommateurs du topic qu'on inspecte. Ses
     * lecteurs transitoires avaient commité des offsets (auto-commit laissé à son défaut `true`),
     * donc ils apparaissaient ici avec du retard et aucun membre — la forme exacte que
     * `health()` note STALLED et que l'audit remonte en constat *critique*.
     */
    @Test
    void excludesTheApplicationsOwnReaders() {
        topicWithPartitions(1);
        groups(group("orders-service", GroupType.CLASSIC),
               group("kafka-explorer-metadata-8f2c", GroupType.CLASSIC),
               group("kafka-sql-explorer-timestamps-1a9e", GroupType.CLASSIC),
               group("topic-search-77b0", GroupType.CLASSIC));
        described(Map.of("orders-service", description("orders-service", member("m1", "h1", 0))));
        committed(Map.of("orders-service", Map.of(0, 90L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(List.of("orders-service"),
            consumers.groups().stream().map(ConsumerGroupLag::groupId).toList());
        // Exclu, mais dit : un filtrage muet est ce que le reste de l'application s'interdit.
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("3 group(s) belonging to this application")),
            "the exclusion should be stated: " + consumers.warnings());
    }

    /** Le compte de groupes du cluster reste celui du cluster, pas celui d'après filtrage. */
    @Test
    void stillReportsHowManyGroupsTheClusterHolds() {
        topicWithPartitions(1);
        groups(group("orders-service", GroupType.CLASSIC),
               group("kafka-explorer-metadata-8f2c", GroupType.CLASSIC));
        described(Map.of("orders-service", description("orders-service", member("m1", "h1", 0))));
        committed(Map.of("orders-service", Map.of(0, 100L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(2, consumers.groupsInCluster());
        assertEquals(1, consumers.groupsExamined());
    }

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
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("does not appear here even if it is the one lagging")));
    }

    /*
     * `.all()` échouait en bloc : un seul groupe illisible coûtait les membres des deux cents
     * autres, alors que l'appartenance est ce qui sépare STALLED de BEHIND.
     */
    @Test
    void oneUndescribableGroupDoesNotBlindTheOthers() {
        topicWithPartitions(1);
        groups(group("orders", GroupType.CLASSIC), group("payments", GroupType.CLASSIC));
        described(Map.of("orders", description("orders", member("m1", "h1", 0)),
                         "payments", description("payments", member("m2", "h2", 0))));
        describeFailsFor("payments", "coordinator moved");
        committed(Map.of("orders", Map.of(0, 40L), "payments", Map.of(0, 10L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        ConsumerGroupLag orders = consumers.groups().stream()
                .filter(g -> g.groupId().equals("orders")).findFirst().orElseThrow();
        assertEquals(1, orders.assignedMembers(), "the healthy group keeps its members");
        assertEquals(60L, orders.totalLag());
        assertTrue(consumers.warnings().stream().anyMatch(w -> w.contains("1 of the 2 group(s)")),
            "the degradation should be scoped, not global: " + consumers.warnings());
    }

    /** Un groupe dont les offsets échouent est nommé, pas confondu avec « ne lit pas ce topic ». */
    @Test
    void oneGroupWithUnreadableOffsetsIsReportedRatherThanDropped() {
        topicWithPartitions(1);
        groups(group("orders", GroupType.CLASSIC), group("payments", GroupType.CLASSIC));
        described(Map.of("orders", description("orders", member("m1", "h1", 0))));
        committed(Map.of("orders", Map.of(0, 40L)));
        offsetsFailFor("payments", "request timed out");
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        ConsumerGroupLag payments = consumers.groups().stream()
                .filter(g -> g.groupId().equals("payments")).findFirst()
                .orElseThrow(() -> new AssertionError("the failed group should still be listed: " + consumers.groups()));
        assertEquals(ConsumerGroupLag.Health.UNKNOWN, payments.health());
        assertTrue(payments.error().contains("timed out"), payments.error());
        // Et le groupe sain répond quand même — c'est tout l'objet du changement.
        assertEquals(60L, consumers.groups().stream()
                .filter(g -> g.groupId().equals("orders")).findFirst().orElseThrow().totalLag());
    }

    /*
     * La troncature se faisait par ordre alphabétique, et son propre avertissement l'avouait. Un
     * groupe qui a des membres est bien plus probablement celui qu'on cherche qu'un groupe vide
     * depuis une semaine.
     */
    @Test
    void theCapKeepsGroupsWithMembersBeforeDormantOnes() {
        topicWithPartitions(1);
        groups(dormantGroup("aaa-idle"), group("zzz-live", GroupType.CLASSIC));
        described(Map.of("zzz-live", description("zzz-live", member("m1", "h1", 0))));
        committed(Map.of("zzz-live", Map.of(0, 40L), "aaa-idle", Map.of(0, 90L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 1);

        assertEquals(List.of("zzz-live"),
            consumers.groups().stream().map(ConsumerGroupLag::groupId).toList(),
            "the live group must survive the cap even though it sorts last");
    }

    /*
     * La sélection des groupes supprimables : c'est le seul endroit où cette application écrit sur
     * un cluster, donc ce qu'elle refuse de toucher compte plus que ce qu'elle supprime.
     */
    @Test
    void onlyOffersItsOwnDormantGroupsForDeletion() {
        groups(
            dormantGroup("kafka-explorer-metadata-1"),      // à nous, vide → supprimable
            dormantGroup("kafka-sql-explorer-timestamps-9"), // ancien schéma, vide → supprimable
            group("kafka-explorer-live-session-7", GroupType.CONSUMER), // à nous, mais STABLE
            dormantGroup("orders-service"),                  // vide, mais pas à nous
            group("payments", GroupType.CLASSIC));           // ni l'un ni l'autre

        // Les résidus des anciens builds passent devant — voir l'ordre du plafond plus bas.
        assertEquals(List.of("kafka-sql-explorer-timestamps-9", "kafka-explorer-metadata-1"),
            service.listDeletableExplorerGroups(100));
    }

    /*
     * Ce que le plafond coupe en premier. La coupe était alphabétique sur des identifiants qui
     * sont des UUID — une coupe au hasard déguisée en ordre. Ce qu'une passe plafonnée doit
     * enlever d'abord, c'est l'arriéré qu'aucun build actuel ne peut recréer : les groupes aux
     * schémas de nommage d'avant le préfixe, dont un cluster ayant fait tourner une ancienne
     * version porte des milliers.
     */
    @Test
    void removesTheLeftoversOfOlderBuildsFirstWhenTheCapHasToCut() {
        groups(dormantGroup("kafka-explorer-aaa"),
               dormantGroup("kafka-explorer-bbb"),
               dormantGroup("snapshot-reader-zzz"),      // schéma hérité
               dormantGroup("topic-search-yyy"));        // schéma hérité

        assertEquals(List.of("snapshot-reader-zzz", "topic-search-yyy"),
            service.listDeletableExplorerGroups(2));
    }

    /*
     * Le cas qui distingue « c'est nous qui l'avons fait exister » de « c'est à nous ». Le DDL
     * généré porte `properties.group.id = flink_table_<table>` et il est publié *pour être copié* :
     * un job Flink de production de l'utilisateur tourne sous cet id. À l'arrêt, il est vide — donc
     * indiscernable d'un résidu, sauf qu'il ne nous appartient pas. Le supprimer violerait la règle
     * que ce nettoyage énonce lui-même : ne jamais toucher un groupe qui n'est pas le nôtre.
     */
    @Test
    void neverOffersTheGroupItOnlySuggestedInGeneratedDdl() {
        groups(dormantGroup("flink_table_demo_orders_1_received"),
               dormantGroup("kafka-explorer-metadata-1"));

        assertEquals(List.of("kafka-explorer-metadata-1"),
            service.listDeletableExplorerGroups(100),
            "an idle flink_table_* group may be the user's own stopped job");
    }

    /** Le plafond est une borne dure, pas une indication. */
    @Test
    void neverOffersMoreThanTheCap() {
        groups(dormantGroup("kafka-explorer-a"), dormantGroup("kafka-explorer-b"),
               dormantGroup("kafka-explorer-c"));

        assertEquals(2, service.listDeletableExplorerGroups(2).size());
        assertEquals(List.of(), service.listDeletableExplorerGroups(0));
    }

    /** Un broker qui ne répond pas ne fait pas supprimer au hasard : la liste est vide. */
    @Test
    void offersNothingWhenTheGroupsCannotBeListed() {
        when(admin.listGroups(any())).thenThrow(new IllegalStateException("broker unreachable"));

        assertEquals(List.of(), service.listDeletableExplorerGroups(100));
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

    /*
     * Et surtout : ne pas conclure de cette absence de membres que rien ne draine le topic. Un
     * groupe non décrit arrive à zéro membre faute de réponse, pas faute de membre — le noter
     * STALLED, c'est trancher sur la foi d'un appel qui n'a rien dit, et l'audit en faisait un
     * constat *critique*. Le cas est courant : `describeConsumerGroups` ne répond pas pour un
     * groupe Kafka Streams, dont les offsets se lisent pourtant très bien.
     */
    @Test
    void doesNotCallAGroupStalledWhenItsMembershipCouldNotBeRead() {
        topicWithPartitions(1);
        groups(group("streams-app", GroupType.CLASSIC));
        described(Map.of());
        describeFailsFor("streams-app", "group id not found");
        committed(Map.of("streams-app", Map.of(0, 40L)));
        endOffsets(Map.of(0, 100L));

        ConsumerGroupLag group = service.getTopicConsumers(TOPIC, 200).groups().get(0);

        assertFalse(group.membersKnown(), "nothing answered about this group's members");
        assertEquals(ConsumerGroupLag.Health.BEHIND, group.health(),
                "unknown membership cannot decide the stalled question either way");
    }

    @Test
    void aGroupWithNoAssignedMemberIsStillStalledWhenThatWasActuallyRead() {
        topicWithPartitions(1);
        groups(dormantGroup("abandoned"));
        described(Map.of("abandoned", description("abandoned")));
        committed(Map.of("abandoned", Map.of(0, 40L)));
        endOffsets(Map.of(0, 100L));

        ConsumerGroupLag group = service.getTopicConsumers(TOPIC, 200).groups().get(0);

        assertTrue(group.membersKnown());
        assertEquals(ConsumerGroupLag.Health.STALLED, group.health());
    }

    /*
     * « On n'a pas pu lire » et « il n'y a rien » se rendaient à l'identique : zéro groupe sur
     * zéro examiné. Le panneau en tirait « le cluster n'a aucun groupe », affirmation sur une
     * question jamais posée, et l'export Prometheus ne savait pas s'il devait garder sa dernière
     * valeur ou l'oublier.
     */
    @Test
    void separatesAFailedReadFromAnEmptyAnswer() {
        topicWithPartitions(1);
        when(admin.listGroups(any())).thenThrow(new IllegalStateException("broker unreachable"));

        assertFalse(service.getTopicConsumers(TOPIC, 200).available());

        setUp();
        topicWithPartitions(1);
        groups();
        endOffsets(Map.of(0, 100L));

        TopicConsumers empty = service.getTopicConsumers(TOPIC, 200);
        assertTrue(empty.available(), "the cluster answered: it simply holds no group");
        assertTrue(empty.groups().isEmpty());
    }

    /*
     * Lire un topic ne doit pas rapatrier les offsets de tous les autres. La photo partagée par
     * l'audit, elle, est volontairement non restreinte — c'est ce qui la rend réutilisable — donc
     * rien n'empêche structurellement le chemin mono-topic d'hériter de cette absence de
     * restriction : d'où ce test, qui vérifie que la demande porte bien les partitions du topic.
     */
    @SuppressWarnings("unchecked")
    @Test
    void restrictsTheOffsetFetchToTheTopicItWasAskedAbout() {
        topicWithPartitions(2);
        groups(group("orders-service", GroupType.CLASSIC));
        described(Map.of("orders-service", description("orders-service", member("m1", "h1", 0))));
        committed(Map.of("orders-service", Map.of(0, 40L)));
        endOffsets(Map.of(0, 100L, 1, 100L));

        service.getTopicConsumers(TOPIC, 200);

        ArgumentCaptor<Map<String, ListConsumerGroupOffsetsSpec>> specs =
                ArgumentCaptor.forClass(Map.class);
        verify(admin).listConsumerGroupOffsets(specs.capture());
        assertEquals(List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1)),
                specs.getValue().get("orders-service").topicPartitions(),
                "an unrestricted fetch would return every topic's offsets to answer about one");
    }

    /*
     * Le dénominateur honnête de `groupsExamined` est le nombre de groupes éligibles, pas celui du
     * cluster : le panneau annonçait « tous les N groupes du cluster lus » en ayant écarté les
     * share groups et ceux de l'application.
     */
    @Test
    void reportsEligibleGroupsApartFromTheClustersTotal() {
        topicWithPartitions(1);
        groups(group("orders-service", GroupType.CLASSIC),
                group("kafka-explorer-metadata-1", GroupType.CLASSIC),
                group("share-reader", GroupType.SHARE));
        described(Map.of("orders-service", description("orders-service", member("m1", "h1", 0))));
        committed(Map.of("orders-service", Map.of(0, 40L)));
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(3, consumers.groupsInCluster());
        assertEquals(1, consumers.groupsEligible(), "one share group and one of ours were excluded");
        assertEquals(1, consumers.groupsExamined());
    }

    /*
     * Une ligne dont le retard n'a pas pu être lu porte un zéro. Trier sur le seul chiffre la
     * rangeait donc parmi les groupes à jour — le seul endroit où elle ne doit pas être.
     */
    @Test
    void putsUnreadableGroupsAboveTheMeasuredOnes() {
        topicWithPartitions(1);
        groups(group("busy", GroupType.CLASSIC), group("broken", GroupType.CLASSIC));
        described(Map.of("busy", description("busy", member("m1", "h1", 0)),
                "broken", description("broken", member("m2", "h2", 0))));
        committed(Map.of("busy", Map.of(0, 40L), "broken", Map.of(0, 10L)));
        offsetsFailFor("broken", "coordinator moved");
        endOffsets(Map.of(0, 100L));

        TopicConsumers consumers = service.getTopicConsumers(TOPIC, 200);

        assertEquals(List.of("broken", "busy"),
                consumers.groups().stream().map(ConsumerGroupLag::groupId).toList());
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
