// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.PartitionLag;
import com.yourcompany.kafkasqlexplorer.domain.TopicConsumers;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.cache.annotation.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeFeaturesResult;
import org.apache.kafka.clients.admin.DescribeMetadataQuorumOptions;
import org.apache.kafka.clients.admin.FeatureMetadata;
import org.apache.kafka.clients.admin.FinalizedVersionRange;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.admin.SupportedVersionRange;
import org.springframework.stereotype.Service;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.Collection;

/**
 * Service for interacting with Kafka at the administrative and consumer level.
 * It manages the lifecycle of the AdminClient and provides utility methods for
 * fetching metadata and sampling records.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);
    private final KafkaConfig kafkaConfig;

    /**
     * Shared AdminClient instance for the application.
     * We initialize it once and reuse it across multiple requests.
     */
    private AdminClient adminClient;

    /**
     * Test seam — {@code KafkaAdminServiceConsumerLagTest} drives a mocked AdminClient through it.
     * The real one is built in {@link #init()} from the connection settings, which a unit test has
     * no way to satisfy without a broker.
     */
    void setAdminClientForTest(AdminClient adminClient) {
        this.adminClient = adminClient;
    }
    private SchemaRegistryClient schemaRegistryClient;
    private KafkaAvroDeserializer avroDeserializer;

    public KafkaAdminService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    @PostConstruct
    public void init() {
        if (this.adminClient != null) {
            // Bounded for the same reason as close() below: this runs when the cluster is
            // repointed, which is very often *because* the previous one stopped answering.
            this.adminClient.close(Duration.ofSeconds(5));
        }
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        this.adminClient = AdminClient.create(props);

        if (kafkaConfig.getSchemaRegistryUrl() != null) {
            this.schemaRegistryClient = new CachedSchemaRegistryClient(kafkaConfig.getSchemaRegistryUrl(), 100);
            Map<String, Object> avroProps = new HashMap<>();
            avroProps.put("schema.registry.url", kafkaConfig.getSchemaRegistryUrl());
            this.avroDeserializer = new KafkaAvroDeserializer(schemaRegistryClient, avroProps);
        }
    }

    @PreDestroy
    public void close() {
        if (adminClient != null) {
            // Bounded on purpose. The no-arg close() waits for pending calls with no
            // deadline, so a describe still retrying against an unreachable broker — the
            // ordinary case when the whole stack is going down, or when the app has been
            // repointed at a cluster that is gone — would hold shutdown until Docker's
            // grace period expired and SIGKILLed the JVM mid-teardown.
            adminClient.close(Duration.ofSeconds(5));
        }
    }

    /**
     * Cheapest possible reachability probe: the cluster id, which the AdminClient answers
     * from the metadata it already holds. Used by the readiness health indicator, so it is
     * deliberately not cached and deliberately bounded — a readiness check that blocks is
     * worse than one that reports DOWN.
     *
     * @return the cluster id
     * @throws Exception when the cluster cannot be reached within {@code timeoutMs}
     */
    public String probeClusterId(long timeoutMs) throws Exception {
        return adminClient.describeCluster().clusterId().get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public Map<String, String> getBrokerConfigs() {
        Map<String, String> configs = new HashMap<>();
        try {
            DescribeClusterResult clusterResult = adminClient.describeCluster();
            Collection<Node> nodes = clusterResult.nodes().get(5, TimeUnit.SECONDS);
            if (nodes.isEmpty()) return configs;

            Node firstNode = nodes.iterator().next();
            ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(firstNode.id()));
            DescribeConfigsResult result = adminClient.describeConfigs(Collections.singletonList(resource));
            Config config = result.all().get(5, TimeUnit.SECONDS).get(resource);

            for (ConfigEntry entry : config.entries()) {
                configs.put(entry.name(), entry.value());
            }
        } catch (Exception e) {
            log.error("Failed to get broker configs", e);
        }
        return configs;
    }

    /** Public so the topic search can decode records with the same Avro / UTF-8 rules as sampling. */
    public String deserializeValue(String topic, byte[] value) {
        if (value == null) return null;
        // Check for Confluent Avro magic byte (0x00)
        if (value.length > 5 && value[0] == 0) {
            try {
                if (avroDeserializer != null) {
                    Object record = avroDeserializer.deserialize(topic, value);
                    if (record instanceof GenericRecord genericRecord) {
                        try {
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            org.apache.avro.io.DatumWriter<GenericRecord> writer = new org.apache.avro.generic.GenericDatumWriter<>(genericRecord.getSchema());
                            org.apache.avro.io.Encoder encoder = org.apache.avro.io.EncoderFactory.get().jsonEncoder(genericRecord.getSchema(), out);
                            writer.write(genericRecord, encoder);
                            encoder.flush();
                            return new String(out.toByteArray(), StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            return genericRecord.toString();
                        }
                    }
                    return String.valueOf(record);
                }
            } catch (Exception e) {
                log.debug("Failed to deserialize Avro for topic {}: {}", topic, e.getMessage());
            }
        }
        // Fallback to UTF-8 String
        return new String(value, StandardCharsets.UTF_8);
    }

    @Cacheable(value = "kafkaTopics", key = "'all'")
    public List<String> listTopics() throws ExecutionException, InterruptedException, TimeoutException {
        try {
            return new ArrayList<>(adminClient.listTopics(new ListTopicsOptions().listInternal(false)).names().get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            log.warn("listTopics timed out after 5s");
            throw e;
        }
    }

    /**
     * Cached (30s TTL): each call spins up a full KafkaConsumer plus a describeTopics
     * round-trip, and the dashboard polls this every 5 seconds for every topic.
     */
    @Cacheable(value = "topicSizes", key = "#topicNames")
    public Map<String, Long> getTopicsSize(List<String> topicNames) {
        Map<String, Long> sizes = new HashMap<>();
        if (topicNames.isEmpty()) return sizes;

        // Initialize with 0 to prevent null pointers or missing keys in UI
        topicNames.forEach(name -> sizes.put(name, 0L));

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "bulk-metadata");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> allPartitions = new ArrayList<>();
            Map<String, List<TopicPartition>> topicToPartitions = new HashMap<>();

            try {
                Map<String, TopicDescription> descriptions = adminClient.describeTopics(topicNames).allTopicNames().get(10, TimeUnit.SECONDS);
                for (String name : topicNames) {
                    TopicDescription desc = descriptions.get(name);
                    if (desc != null) {
                        List<TopicPartition> tps = desc.partitions().stream()
                                .map(p -> new TopicPartition(name, p.partition()))
                                .toList();
                        allPartitions.addAll(tps);
                        topicToPartitions.put(name, tps);
                    }
                }

                if (allPartitions.isEmpty()) return sizes;

                Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(allPartitions);
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(allPartitions);

                for (String name : topicNames) {
                    List<TopicPartition> tps = topicToPartitions.get(name);
                    if (tps != null) {
                        long size = tps.stream()
                                .mapToLong(tp -> {
                                    Long end = endOffsets.get(tp);
                                    Long start = beginningOffsets.get(tp);
                                    return (end != null && start != null) ? (end - start) : 0L;
                                })
                                .sum();
                        sizes.put(name, size);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to get topics size for: {}", topicNames, e);
            }
        }
        return sizes;
    }

    @Cacheable(value = "topicDescriptor", key = "#topicName")
    public TopicDescriptor getTopicDescriptor(String topicName) throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get(5, TimeUnit.SECONDS);
        TopicDescription desc = descriptions.get(topicName);

        List<TopicPartition> partitions = desc.partitions().stream()
                .map(p -> new TopicPartition(topicName, p.partition()))
                .collect(Collectors.toList());

        Map<Integer, Long> minOffsets = new HashMap<>();
        Map<Integer, Long> maxOffsets = new HashMap<>();

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "metadata");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            for (TopicPartition tp : partitions) {
                minOffsets.put(tp.partition(), beginningOffsets.get(tp));
                maxOffsets.put(tp.partition(), endOffsets.get(tp));
            }
        }

        long totalSize = maxOffsets.values().stream().mapToLong(Long::longValue).sum() -
                         minOffsets.values().stream().mapToLong(Long::longValue).sum();

        return new TopicDescriptor(
                topicName,
                desc.partitions().size(),
                minOffsets,
                maxOffsets,
                MessageFormat.AUTO, // Placeholder, format detection would be elsewhere
                totalSize
        );
    }

    /**
     * Cached (30s TTL): every call fans out into describeCluster + describeFeatures +
     * describeMetadataQuorum + listGroups + a full describeTopics + per-topic size scan —
     * far too heavy to run on each visit of the Cluster page.
     */
    @Cacheable("clusterDetails")
    public Map<String, Object> getClusterDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            DescribeClusterResult result = adminClient.describeCluster(new DescribeClusterOptions().timeoutMs(5000));
            String clusterId = result.clusterId().get(5, TimeUnit.SECONDS);
            Node controller = result.controller().get(5, TimeUnit.SECONDS);
            Collection<Node> nodes = result.nodes().get(5, TimeUnit.SECONDS);

            details.put("clusterId", clusterId);
            details.put("controllerId", controller != null ? controller.id() : null);
            details.put("brokerCount", nodes.size());

            List<Map<String, Object>> brokers = new ArrayList<>();
            for (Node node : nodes) {
                Map<String, Object> broker = new LinkedHashMap<>();
                broker.put("id", node.id());
                broker.put("host", node.host());
                broker.put("port", node.port());
                broker.put("rack", node.rack());
                broker.put("isController", controller != null && node.id() == controller.id());
                brokers.add(broker);
            }
            brokers.sort(Comparator.comparingInt(b -> (Integer) b.get("id")));
            details.put("brokers", brokers);

            // Features (Kafka 4 options / KRaft metadata)
            try {
                DescribeFeaturesResult featuresResult = adminClient.describeFeatures();
                FeatureMetadata featureMetadata = featuresResult.featureMetadata().get(5, TimeUnit.SECONDS);
                Map<String, Object> finalizedFeatures = new LinkedHashMap<>();
                for (Map.Entry<String, FinalizedVersionRange> entry : featureMetadata.finalizedFeatures().entrySet()) {
                    Map<String, Short> range = new LinkedHashMap<>();
                    range.put("min", entry.getValue().minVersionLevel());
                    range.put("max", entry.getValue().maxVersionLevel());
                    finalizedFeatures.put(entry.getKey(), range);
                }
                details.put("finalizedFeatures", finalizedFeatures);

                Map<String, Object> supportedFeatures = new LinkedHashMap<>();
                for (Map.Entry<String, SupportedVersionRange> entry : featureMetadata.supportedFeatures().entrySet()) {
                    Map<String, Short> range = new LinkedHashMap<>();
                    range.put("min", entry.getValue().minVersion());
                    range.put("max", entry.getValue().maxVersion());
                    supportedFeatures.put(entry.getKey(), range);
                }
                details.put("supportedFeatures", supportedFeatures);
            } catch (Exception e) {
                log.debug("Failed to retrieve feature metadata (possibly older Kafka version)", e);
            }

            // KRaft controller quorum (KIP-595) — unavailable on Zookeeper-based clusters
            Map<String, Object> kraftQuorum = getQuorumSnapshot();
            if (kraftQuorum != null) {
                details.put("kraftQuorum", kraftQuorum);
            }

            // All client groups regardless of type — classic, consumer (KIP-848),
            // share (KIP-932, Kafka 4.1+), streams. Kafka 4.x admin API.
            try {
                Collection<GroupListing> groups = adminClient
                        .listGroups(new ListGroupsOptions().timeoutMs(5000))
                        .all().get(5, TimeUnit.SECONDS);
                List<Map<String, Object>> groupList = new ArrayList<>();
                for (GroupListing group : groups) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("groupId", group.groupId());
                    item.put("type", group.type().map(Enum::name).orElse("UNKNOWN"));
                    item.put("state", group.groupState().map(Enum::name).orElse("UNKNOWN"));
                    // Listed, not hidden — this page is the raw view of the cluster — but named
                    // for what it is. A live Process Mining session *subscribes*, so it does
                    // register a group here, and the page's own empty-state text used to promise
                    // the opposite ("the explorer's consumers never register").
                    item.put("explorer", ExplorerConsumerGroups.isExplorerGroup(group.groupId()));
                    groupList.add(item);
                }
                groupList.sort(Comparator.comparing(g -> (String) g.get("groupId")));
                details.put("groups", groupList);
            } catch (Exception e) {
                log.debug("Failed to list groups (broker may not support the ListGroups API)", e);
            }

            // Topic stats
            List<String> topicNames = new ArrayList<>(
                adminClient.listTopics(new ListTopicsOptions().listInternal(false)).names().get(5, TimeUnit.SECONDS)
            );
            details.put("topicCount", topicNames.size());

            // Partition count via describeTopics
            if (!topicNames.isEmpty()) {
                Map<String, TopicDescription> descriptions = adminClient.describeTopics(topicNames).allTopicNames().get(10, TimeUnit.SECONDS);
                int totalPartitions = descriptions.values().stream().mapToInt(d -> d.partitions().size()).sum();
                details.put("partitionCount", totalPartitions);

                // Empty and single-partition topics
                long emptyTopics = descriptions.values().stream().filter(d -> d.partitions().isEmpty()).count();
                long singlePartitionTopics = descriptions.values().stream().filter(d -> d.partitions().size() == 1).count();
                details.put("emptyTopicCount", emptyTopics);
                details.put("singlePartitionTopicCount", singlePartitionTopics);

                // Topic sizes for top 10
                Map<String, Long> sizes = getTopicsSize(topicNames);
                long totalMessages = sizes.values().stream().mapToLong(Long::longValue).sum();
                details.put("totalMessages", totalMessages);

                List<Map<String, Object>> topTopics = sizes.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", e.getKey());
                        t.put("messages", e.getValue());
                        int partitions = descriptions.containsKey(e.getKey()) ? descriptions.get(e.getKey()).partitions().size() : 0;
                        t.put("partitions", partitions);
                        return t;
                    })
                    .collect(Collectors.toList());
                details.put("topTopics", topTopics);
            } else {
                details.put("partitionCount", 0);
                details.put("emptyTopicCount", 0);
                details.put("singlePartitionTopicCount", 0);
                details.put("totalMessages", 0L);
                details.put("topTopics", List.of());
            }

            details.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        } catch (Exception e) {
            log.error("Failed to get cluster details", e);
            details.put("error", e.getMessage());
        }
        return details;
    }

    /**
     * Who reads a topic, and how far behind they are.
     *
     * <p>Four admin calls, in this order and for a reason: list the groups, describe the
     * candidates (members and their assignments), read their committed offsets restricted to this
     * topic's partitions, and only <em>then</em> the log end offsets. Reading the end offsets last
     * means a consumer committing between the two calls can only make the lag look larger, never
     * negative — so a negative lag that does survive is a real one (an offset reset past the end,
     * a topic recreated under the same name) and is reported rather than clamped away.
     *
     * <p>SHARE groups (KIP-932) are excluded: their state lives in the share-group coordinator,
     * not in {@code __consumer_offsets}, so {@code listConsumerGroupOffsets} answers empty for
     * them and listing them would only manufacture "0 lag" rows for groups nobody measured.
     *
     * <p>Bounded by {@code explorer.consumer-group-max-groups}: a cluster can hold thousands of
     * groups and each one costs a coordinator lookup. The response says how many were examined
     * against how many exist, because an empty list must not read as "nobody consumes this topic"
     * when it means "we looked at the first two hundred of three thousand".
     *
     * <p>Cached (30s) like the other metadata reads — the page polls, and the numbers move on
     * every produce anyway. The key carries {@code maxGroups}: it is a configured value today, but
     * a cache keyed on the topic alone would serve a truncated answer to a caller that asked for a
     * wider one, which is precisely the confusion {@code truncated} exists to prevent.
     *
     * <p>A <strong>failed</strong> read is not cached ({@code unless}). A brief coordinator blip
     * otherwise froze the failure for half a minute, and the panel's Refresh button — the one
     * gesture that exists to retry — replayed the cached error instead of asking again. Caching an
     * answer is a bet that it is still true; there is no reason to make that bet on "we could not
     * ask", and the Prometheus poller re-reads on the same period.
     */
    @Cacheable(value = "topicConsumers", key = "#topic + '@' + #maxGroups",
               unless = "!#result.available()")
    public TopicConsumers getTopicConsumers(String topic, int maxGroups) {
        // The partitions are resolved *before* the snapshot, and not only to assemble the result:
        // they are what restricts the offset fetch to this topic. Taking the snapshot first would
        // leave nothing to restrict it with, and every group's offsets on every topic of the
        // cluster would travel back to answer a question about one topic.
        TopicPartitions resolved = resolvePartitions(topic);
        if (resolved.error() != null) return TopicConsumers.unavailable(topic, resolved.error());
        return assemble(topic, resolved.partitions(),
                groupSnapshot(maxGroups, resolved.partitions()));
    }

    /**
     * The cluster's groups, read once: listing, descriptions and committed offsets.
     *
     * <p>Split out of {@link #getTopicConsumers(String, int)} because the audit calls that method
     * <em>per topic</em>, and every call re-listed the whole cluster, re-described up to
     * {@code maxGroups} groups and re-read their offsets. On a cluster of three hundred topics and
     * two hundred groups that is three hundred {@code ListGroups}, sixty thousand group
     * descriptions and as many {@code OffsetFetch} round trips to answer a question whose answer
     * does not change between topics. The 30 s cache never helped: it is keyed per topic, and an
     * audit outlives it many times over.
     *
     * <p>{@code restrictTo} narrows the offset fetch to one topic's partitions when only that
     * topic is wanted; passing {@code null} fetches every topic's committed offsets in the same
     * call, which is what makes the snapshot reusable across an audit.
     */
    public GroupSnapshot groupSnapshot(int maxGroups, List<TopicPartition> restrictTo) {
        List<GroupListing> candidates;
        int inCluster;
        int shareGroups;
        int explorerGroups;
        try {
            Collection<GroupListing> all = adminClient
                    .listGroups(new ListGroupsOptions().timeoutMs(5000))
                    .all().get(5, TimeUnit.SECONDS);
            inCluster = all.size();
            List<GroupListing> notShare = all.stream()
                    .filter(g -> !"SHARE".equals(g.type().map(Enum::name).orElse("UNKNOWN")))
                    .toList();
            shareGroups = inCluster - notShare.size();
            // This application's own readers are not consumers of your pipeline. They used to be
            // counted as such — and, having committed offsets with no member left behind them,
            // graded STALLED and reported by the audit as a critical finding about a topic whose
            // only "stalled consumer" was the explorer itself.
            candidates = notShare.stream()
                    .filter(g -> !ExplorerConsumerGroups.isExplorerGroup(g.groupId()))
                    .sorted(Comparator.comparing(KafkaAdminService::isDormant).thenComparing(GroupListing::groupId))
                    .toList();
            explorerGroups = notShare.size() - candidates.size();
        } catch (Exception e) {
            // Not "no consumers": the question could not be asked at all.
            return GroupSnapshot.failed("Could not list the cluster's groups: " + rootMessage(e));
        }

        List<String> warnings = new ArrayList<>();
        if (shareGroups > 0) {
            warnings.add(shareGroups + " share group(s) were skipped: their positions live in the "
                    + "share-group coordinator, not in committed offsets, so no lag can be derived "
                    + "from them here.");
        }
        if (explorerGroups > 0) {
            warnings.add(explorerGroups + " group(s) belonging to this application were excluded — "
                    + "its own metadata and search readers, which consume nothing of yours.");
        }
        int eligible = candidates.size();
        boolean truncated = eligible > maxGroups;
        if (truncated) {
            warnings.add("Only " + maxGroups + " of the " + eligible
                    + " eligible groups were read — groups with members first, then dormant ones, "
                    + "each alphabetically. A group past that point does not appear here even if "
                    + "it is the one lagging; raise explorer.consumer-group-max-groups to widen it.");
            candidates = candidates.subList(0, maxGroups);
        }
        if (candidates.isEmpty()) {
            return new GroupSnapshot(inCluster, eligible, truncated, List.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), warnings, null);
        }

        List<String> groupIds = candidates.stream().map(GroupListing::groupId).toList();
        Map<String, String> typeOf = candidates.stream().collect(Collectors.toMap(
                GroupListing::groupId, g -> g.type().map(Enum::name).orElse("UNKNOWN"), (a, b) -> a));

        // Per group, not in bulk. `.all()` fails as soon as *one* group fails to describe, so a
        // single odd group — a coordinator that moved, a group deleted mid-read — cost the members
        // and assignments of all two hundred. Membership is what separates STALLED (nothing is
        // draining) from BEHIND (it is catching up), so losing it everywhere is not cosmetic.
        Map<String, ConsumerGroupDescription> descriptions = new LinkedHashMap<>();
        Map<String, String> describeErrors = new LinkedHashMap<>();
        try {
            awaitPerGroup(adminClient.describeConsumerGroups(groupIds).describedGroups(),
                    10_000, descriptions, describeErrors);
        } catch (Exception e) {
            // The call itself refused — a closed client, an unavailable coordinator. Degraded,
            // not fatal: the offsets carry the lag and may still be readable.
            groupIds.forEach(id -> describeErrors.put(id, rootMessage(e)));
        }
        if (!describeErrors.isEmpty()) {
            warnings.add("Group members could not be read for " + describeErrors.size() + " of the "
                    + groupIds.size() + " group(s) (" + firstReason(describeErrors)
                    + "); their lag is still reported, their assignments are not — so they are "
                    + "never graded \"stalled\", a verdict that rests on membership.");
        }

        Map<String, ListConsumerGroupOffsetsSpec> specs = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            ListConsumerGroupOffsetsSpec spec = new ListConsumerGroupOffsetsSpec();
            if (restrictTo != null) spec = spec.topicPartitions(restrictTo);
            specs.put(groupId, spec);
        }
        // Same treatment, and here it decides more: `.all()` failing meant the whole topic came
        // back as "unavailable", so one unreadable group hid every other group's lag.
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new LinkedHashMap<>();
        Map<String, String> offsetErrors = new LinkedHashMap<>();
        try {
            ListConsumerGroupOffsetsResult offsetsResult = adminClient.listConsumerGroupOffsets(specs);
            Map<String, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> offsetFutures = new LinkedHashMap<>();
            for (String groupId : groupIds) {
                offsetFutures.put(groupId, offsetsResult.partitionsToOffsetAndMetadata(groupId));
            }
            awaitPerGroup(offsetFutures, 15_000, committed, offsetErrors);
        } catch (Exception e) {
            return GroupSnapshot.failed("Could not read committed offsets: " + rootMessage(e));
        }
        if (offsetErrors.size() == groupIds.size()) {
            // Not one group answered: that is the old meaning of unavailable, and it must keep it —
            // an empty list would claim nobody reads the topic.
            return GroupSnapshot.failed("Could not read committed offsets: " + firstReason(offsetErrors));
        }

        return new GroupSnapshot(inCluster, eligible, truncated, groupIds, typeOf,
                descriptions, describeErrors, committed, offsetErrors, warnings, null);
    }

    /**
     * One topic's view of a group snapshot: the end offsets are read <em>now</em>, and only they.
     *
     * <p>Public so an audit can take the snapshot once and walk every topic through it. The end
     * offsets stay per topic and stay last, which is what keeps the lag arithmetic honest — see
     * {@link #getTopicConsumers(String, int)}.
     */
    public TopicConsumers getTopicConsumers(String topic, GroupSnapshot snapshot) {
        TopicPartitions resolved = resolvePartitions(topic);
        if (resolved.error() != null) return TopicConsumers.unavailable(topic, resolved.error());
        return assemble(topic, resolved.partitions(), snapshot);
    }

    /** A topic's partitions, or the reason they could not be read — never both. */
    private record TopicPartitions(List<TopicPartition> partitions, String error) {
    }

    private TopicPartitions resolvePartitions(String topic) {
        try {
            TopicDescription description = adminClient.describeTopics(List.of(topic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
            if (description == null) {
                return new TopicPartitions(List.of(), "Topic '" + topic + "' does not exist.");
            }
            return new TopicPartitions(description.partitions().stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList(), null);
        } catch (Exception e) {
            return new TopicPartitions(List.of(), "Could not describe the topic: " + rootMessage(e));
        }
    }

    private TopicConsumers assemble(String topic, List<TopicPartition> partitions, GroupSnapshot snapshot) {
        if (snapshot.failure() != null) {
            return TopicConsumers.unavailable(topic, snapshot.failure());
        }
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        List<String> groupIds = snapshot.groupIds();
        if (groupIds.isEmpty()) {
            return new TopicConsumers(topic, List.of(), 0, snapshot.eligible(),
                    snapshot.inCluster(), snapshot.truncated(), true, warnings);
        }
        Map<String, String> typeOf = snapshot.typeOf();
        Map<String, ConsumerGroupDescription> descriptions = snapshot.descriptions();
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = snapshot.committed();
        Map<String, String> offsetErrors = snapshot.offsetErrors();

        Map<TopicPartition, Long> endOffsets;
        try {
            Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
            partitions.forEach(tp -> request.put(tp, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    adminClient.listOffsets(request).all().get(10, TimeUnit.SECONDS);
            endOffsets = ends.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        } catch (Exception e) {
            return TopicConsumers.unavailable(topic,
                    "Could not read the topic's end offsets: " + rootMessage(e));
        }

        List<ConsumerGroupLag> groups = new ArrayList<>();
        boolean negativeLag = false;
        for (String groupId : groupIds) {
            String failure = offsetErrors.get(groupId);
            if (failure != null) {
                // Named, never silently dropped: a group whose offsets could not be read is not a
                // group that does not read this topic, and the audit grades the two differently.
                groups.add(ConsumerGroupLag.failed(groupId, typeOf.getOrDefault(groupId, "UNKNOWN"), failure));
                continue;
            }
            Map<TopicPartition, OffsetAndMetadata> offsets = committed.get(groupId);
            if (offsets == null || offsets.values().stream().allMatch(Objects::isNull)) {
                continue; // this group has no position on this topic — not a consumer of it
            }
            ConsumerGroupDescription description = descriptions.get(groupId);
            boolean membersKnown = description != null;
            Map<Integer, MemberDescription> holders = assignmentsOn(topic, description);

            List<PartitionLag> perPartition = new ArrayList<>();
            long totalLag = 0L;
            int withoutCommit = 0;
            boolean any = false;
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata offset = offsets.get(tp);
                long end = endOffsets.getOrDefault(tp, 0L);
                Long position = offset == null ? null : offset.offset();
                Long lag = position == null ? null : end - position;
                if (position == null) {
                    withoutCommit++;
                } else {
                    any = true;
                    totalLag += lag;
                    if (lag < 0) negativeLag = true;
                }
                MemberDescription holder = holders.get(tp.partition());
                perPartition.add(new PartitionLag(tp.partition(), position, end, lag,
                        holder == null ? null : holder.consumerId(),
                        holder == null ? null : holder.clientId(),
                        holder == null ? null : holder.host()));
            }
            if (!any) continue;

            groups.add(new ConsumerGroupLag(
                    groupId,
                    typeOf.getOrDefault(groupId, "UNKNOWN"),
                    membersKnown ? description.groupState().name() : "UNKNOWN",
                    membersKnown ? description.members().size() : 0,
                    (int) holders.values().stream().map(MemberDescription::consumerId).distinct().count(),
                    membersKnown,
                    totalLag,
                    withoutCommit,
                    perPartition,
                    null));
        }

        if (negativeLag) {
            warnings.add("A committed offset sits past the end of its partition. The end offsets "
                    + "are read after the committed ones, so this is not a race: it is what an "
                    + "offset reset to a future position, or a topic recreated under the same "
                    + "name, leaves behind.");
        }
        // Unreadable groups first, then the worst lag. A row whose lag could not be read carries a
        // zero, so sorting on the number alone buried it among the groups that are up to date —
        // the one place it must not be, since "not measured" is the row an operator has to act on.
        groups.sort(Comparator.comparing((ConsumerGroupLag g) -> g.error() == null)
                .thenComparing(Comparator.comparingLong(ConsumerGroupLag::totalLag).reversed())
                .thenComparing(ConsumerGroupLag::groupId));
        return new TopicConsumers(topic, groups, groupIds.size(), snapshot.eligible(),
                snapshot.inCluster(), snapshot.truncated(), true, warnings);
    }

    /**
     * The cluster's groups as read at one instant — see {@link #groupSnapshot(int, List)}.
     *
     * @param failure non-null when the read failed outright; every other field is then empty
     */
    public record GroupSnapshot(
            int inCluster,
            int eligible,
            boolean truncated,
            List<String> groupIds,
            Map<String, String> typeOf,
            Map<String, ConsumerGroupDescription> descriptions,
            Map<String, String> describeErrors,
            Map<String, Map<TopicPartition, OffsetAndMetadata>> committed,
            Map<String, String> offsetErrors,
            List<String> warnings,
            String failure
    ) {
        static GroupSnapshot failed(String reason) {
            return new GroupSnapshot(0, 0, false, List.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), List.of(reason), reason);
        }
    }

    /** Which member holds each partition of {@code topic}, empty when the group was not described. */


    /**
     * The explorer's own groups that are safe to delete: ours by name, and dormant.
     *
     * <p>Both conditions, and the second is what makes this safe to run while other instances are
     * up: a live session holds members, so the broker reports it STABLE and it is not a candidate.
     * A group that is not ours is never returned whatever its state — this exists to clean up
     * after this application, not to tidy someone's cluster on their behalf.
     *
     * <p>"Ours" here is {@code isOwnReaderGroup}, which is narrower than
     * {@code isExplorerGroup}: the latter also matches the {@code flink_table_*} id written into
     * generated DDL, and that DDL is published to be copied into the user's own Flink jobs. An
     * idle {@code flink_table_*} group may therefore be a stopped production job rather than our
     * leftover, and deleting it would break the very rule stated above.
     */
    public List<String> listDeletableExplorerGroups(int max) {
        try {
            Collection<GroupListing> all = adminClient
                    .listGroups(new ListGroupsOptions().timeoutMs(5000))
                    .all().get(10, TimeUnit.SECONDS);
            return all.stream()
                    // isOwnReaderGroup, not isExplorerGroup: the latter also matches the
                    // `flink_table_*` id this application writes into generated DDL, which is
                    // published to be copied — an idle one may be the user's own stopped Flink
                    // job, not our leftover. See ExplorerConsumerGroups.
                    .filter(g -> ExplorerConsumerGroups.isOwnReaderGroup(g.groupId()))
                    .filter(KafkaAdminService::isDormant)
                    .map(GroupListing::groupId)
                    .sorted()
                    .limit(Math.max(0, max))
                    .toList();
        } catch (Exception e) {
            log.warn("Could not list groups for cleanup: {}", rootMessage(e));
            return List.of();
        }
    }

    /**
     * Deletes the given groups, one future at a time, and returns those actually removed.
     *
     * <p>Per group for the same reason the lag read is: {@code .all()} would make one refusal —
     * a group that became active between the listing and the delete, most plausibly — look like a
     * total failure. What is reported is what happened.
     */
    public List<String> deleteConsumerGroups(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return List.of();
        List<String> deleted = new ArrayList<>();
        try {
            DeleteConsumerGroupsResult result = adminClient.deleteConsumerGroups(groupIds);
            Map<String, KafkaFuture<Void>> futures = new LinkedHashMap<>(result.deletedGroups());
            Map<String, Void> ok = new LinkedHashMap<>();
            Map<String, String> failed = new LinkedHashMap<>();
            awaitPerGroup(futures, 15_000, ok, failed);
            deleted.addAll(ok.keySet());
            failed.forEach((id, reason) ->
                    log.debug("Could not delete consumer group '{}': {}", id, reason));
        } catch (Exception e) {
            log.warn("Could not delete consumer groups: {}", rootMessage(e));
        }
        return deleted;
    }

    /**
     * Waits on one future per group, so one failure costs one row rather than the whole answer.
     *
     * <p>The admin client has already issued every request; these futures are in flight together.
     * Waiting on them in turn against a single deadline therefore costs at most {@code timeoutMs}
     * in total, and a slow group does not turn its neighbours into failures — they have almost
     * always completed by the time their turn comes.
     */
    private <T> void awaitPerGroup(Map<String, KafkaFuture<T>> futures, long timeoutMs,
                                   Map<String, T> into, Map<String, String> errors) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Map.Entry<String, KafkaFuture<T>> entry : futures.entrySet()) {
            long remaining = Math.max(0, deadline - System.currentTimeMillis());
            try {
                into.put(entry.getKey(), entry.getValue().get(remaining, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.put(entry.getKey(), "interrupted");
            } catch (Exception e) {
                errors.put(entry.getKey(), rootMessage(e));
            }
        }
    }

    /** One reason stands for the batch — the count beside it says how many shared it. */
    private static String firstReason(Map<String, String> errors) {
        return errors.values().stream().findFirst().orElse("unknown reason");
    }

    /**
     * Dormant groups go last when the cap has to cut.
     *
     * <p>The cut used to be alphabetical, and its own warning admitted the flaw: "a group past that
     * point would not appear here even if it were the one lagging". A group with members is far
     * more likely to be the consumer someone is asking about than one that has been EMPTY for a
     * week — and the state is already in the listing, so this costs no extra call.
     */
    private static boolean isDormant(GroupListing group) {
        String state = group.groupState().map(Enum::name).orElse("UNKNOWN");
        return "EMPTY".equals(state) || "DEAD".equals(state);
    }

    private static Map<Integer, MemberDescription> assignmentsOn(String topic, ConsumerGroupDescription description) {
        if (description == null) return Map.of();
        Map<Integer, MemberDescription> holders = new HashMap<>();
        for (MemberDescription member : description.members()) {
            for (TopicPartition tp : member.assignment().topicPartitions()) {
                if (tp.topic().equals(topic)) holders.put(tp.partition(), member);
            }
        }
        return holders;
    }

    /**
     * The innermost message of a cause chain. An admin future wraps the useful text inside an
     * {@code ExecutionException} whose own message is the wrapped class name — and a bare
     * {@code getMessage()} is null on some of them, which would put "null" in a warning.
     */
    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        String best = null;
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                best = cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return best != null ? best : e.getClass().getSimpleName();
    }

    /**
     * Point-in-time KRaft controller quorum snapshot (KIP-595): leader id/epoch, high
     * watermark and per-replica voter/observer state. Deliberately uncached and cheap
     * (a single admin call) — used by {@code getClusterDetails()} and polled by
     * {@code KraftQuorumMetrics} for the Prometheus gauges. Returns null when the broker
     * doesn't expose the metadata quorum (Zookeeper mode).
     */
    public Map<String, Object> getQuorumSnapshot() {
        try {
            QuorumInfo quorum = adminClient
                    .describeMetadataQuorum(new DescribeMetadataQuorumOptions().timeoutMs(5000))
                    .quorumInfo().get(5, TimeUnit.SECONDS);
            Map<String, Object> kraftQuorum = new LinkedHashMap<>();
            kraftQuorum.put("leaderId", quorum.leaderId());
            kraftQuorum.put("leaderEpoch", quorum.leaderEpoch());
            kraftQuorum.put("highWatermark", quorum.highWatermark());
            kraftQuorum.put("voters", toReplicaStates(quorum.voters(), quorum));
            kraftQuorum.put("observers", toReplicaStates(quorum.observers(), quorum));
            return kraftQuorum;
        } catch (Exception e) {
            log.debug("Failed to describe metadata quorum (Zookeeper-based cluster?)", e);
            return null;
        }
    }

    /**
     * Features whose finalized version lags what every broker supports (finalized max <
     * supported max). On KRaft the prime suspect is {@code metadata.version} staying behind
     * after a rolling upgrade until {@code kafka-features.sh upgrade} is run — new metadata
     * features stay disabled cluster-wide until then. Returns an empty list when everything
     * is up to date or the broker doesn't expose feature metadata (e.g. Zookeeper mode).
     */
    public List<Map<String, Object>> getLaggingFeatures() {
        List<Map<String, Object>> lagging = new ArrayList<>();
        try {
            FeatureMetadata featureMetadata = adminClient.describeFeatures().featureMetadata().get(5, TimeUnit.SECONDS);
            for (Map.Entry<String, SupportedVersionRange> entry : featureMetadata.supportedFeatures().entrySet()) {
                FinalizedVersionRange finalized = featureMetadata.finalizedFeatures().get(entry.getKey());
                short supportedMax = entry.getValue().maxVersion();
                Short finalizedMax = finalized != null ? finalized.maxVersionLevel() : null;
                if (finalizedMax == null || finalizedMax < supportedMax) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("feature", entry.getKey());
                    item.put("finalizedVersion", finalizedMax);
                    item.put("supportedMaxVersion", supportedMax);
                    lagging.add(item);
                }
            }
            lagging.sort(Comparator.comparing(f -> (String) f.get("feature")));
        } catch (Exception e) {
            log.debug("Failed to compute feature version lag (broker may not support describeFeatures)", e);
        }
        return lagging;
    }

    /** Flattens raft replica states for the cluster-details payload, with lag vs the quorum high watermark. */
    private static List<Map<String, Object>> toReplicaStates(List<QuorumInfo.ReplicaState> replicas, QuorumInfo quorum) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuorumInfo.ReplicaState replica : replicas) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("replicaId", replica.replicaId());
            state.put("isLeader", replica.replicaId() == quorum.leaderId());
            state.put("logEndOffset", replica.logEndOffset());
            state.put("lag", Math.max(0L, quorum.highWatermark() - replica.logEndOffset()));
            state.put("lastFetchTimestampMs",
                    replica.lastFetchTimestamp().isPresent() ? replica.lastFetchTimestamp().getAsLong() : null);
            state.put("lastCaughtUpTimestampMs",
                    replica.lastCaughtUpTimestamp().isPresent() ? replica.lastCaughtUpTimestamp().getAsLong() : null);
            out.add(state);
        }
        return out;
    }

    /** Cached (30s TTL) for the same reason as {@link #getTopicsSize}: consumer + seek + poll per call. */
    @Cacheable(value = "topicLastMessages", key = "#topicNames")
    public Map<String, Long> getTopicsLastMessageTimestamps(List<String> topicNames) {
        Map<String, Long> timestamps = new HashMap<>();
        if (topicNames.isEmpty()) return timestamps;

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "timestamps");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> allPartitions = new ArrayList<>();
            Map<String, List<TopicPartition>> topicToPartitions = new HashMap<>();

            Map<String, TopicDescription> descriptions = adminClient.describeTopics(topicNames)
                    .allTopicNames().get(10, TimeUnit.SECONDS);

            for (String name : topicNames) {
                TopicDescription desc = descriptions.get(name);
                if (desc != null) {
                    List<TopicPartition> tps = desc.partitions().stream()
                            .map(p -> new TopicPartition(name, p.partition()))
                            .toList();
                    allPartitions.addAll(tps);
                    topicToPartitions.put(name, tps);
                }
            }

            if (allPartitions.isEmpty()) return timestamps;

            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(allPartitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(allPartitions);

            // Only seek to last record on non-empty partitions
            List<TopicPartition> nonEmpty = allPartitions.stream()
                    .filter(tp -> {
                        Long begin = beginningOffsets.get(tp);
                        Long end = endOffsets.get(tp);
                        return begin != null && end != null && end > begin;
                    })
                    .toList();

            if (nonEmpty.isEmpty()) return timestamps;

            consumer.assign(nonEmpty);
            for (TopicPartition tp : nonEmpty) {
                consumer.seek(tp, endOffsets.get(tp) - 1);
            }

            Map<TopicPartition, Long> partitionTimestamps = new HashMap<>();
            Set<TopicPartition> pending = new HashSet<>(nonEmpty);
            int retries = 3;
            while (retries-- > 0 && !pending.isEmpty()) {
                var polled = consumer.poll(java.time.Duration.ofMillis(500));
                for (var record : polled) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    partitionTimestamps.put(tp, record.timestamp());
                    pending.remove(tp);
                }
            }

            // Aggregate per topic: keep the most recent partition timestamp
            for (String name : topicNames) {
                List<TopicPartition> tps = topicToPartitions.get(name);
                if (tps == null) continue;
                OptionalLong maxTs = tps.stream()
                        .filter(partitionTimestamps::containsKey)
                        .mapToLong(partitionTimestamps::get)
                        .max();
                if (maxTs.isPresent()) {
                    timestamps.put(name, maxTs.getAsLong());
                }
            }
        } catch (Exception e) {
            log.error("Failed to get topic last-message timestamps", e);
        }
        return timestamps;
    }

    public Optional<KafkaMessage> getLatestMessage(String topicName) {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "latest-message");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName))
                .allTopicNames().get(5, TimeUnit.SECONDS);
            TopicDescription desc = descriptions.get(topicName);
            if (desc == null) return Optional.empty();

            List<TopicPartition> partitions = desc.partitions().stream()
                .map(p -> new TopicPartition(topicName, p.partition()))
                .toList();
            if (partitions.isEmpty()) return Optional.empty();

            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<TopicPartition> nonEmpty = partitions.stream()
                .filter(tp -> {
                    Long begin = beginningOffsets.get(tp);
                    Long end = endOffsets.get(tp);
                    return begin != null && end != null && end > begin;
                })
                .toList();
            if (nonEmpty.isEmpty()) return Optional.empty();

            consumer.assign(nonEmpty);
            for (TopicPartition tp : nonEmpty) {
                consumer.seek(tp, endOffsets.get(tp) - 1);
            }

            Map<TopicPartition, org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>> latestByPartition = new HashMap<>();
            Set<TopicPartition> pending = new HashSet<>(nonEmpty);
            int retries = 4;
            while (retries-- > 0 && !pending.isEmpty()) {
                org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> polled =
                    consumer.poll(java.time.Duration.ofMillis(500));
                for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record : polled) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    latestByPartition.put(tp, record);
                    pending.remove(tp);
                }
            }

            return latestByPartition.values().stream()
                .max(Comparator
                    .comparingLong(org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>::timestamp)
                    .thenComparingLong(org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>::offset))
                .map(record -> new KafkaMessage(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.timestamp(),
                    record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null,
                    deserializeValue(record.topic(), record.value())
                ));
        } catch (Exception e) {
            log.error("Failed to get latest message for topic {}", topicName, e);
            return Optional.empty();
        }
    }

    public boolean ping() {
        return pingDetail().reachable();
    }

    /**
     * The result of the reachability probe, <em>with the reason</em> when it fails.
     *
     * <p>{@link #ping()} answers a bare boolean, so every caller could report "offline" and none
     * could say why — a broker that is down, a bootstrap address pointing nowhere and an
     * authentication failure all came out as the same blank screen. The message is what turns that
     * into something an operator can act on, so the probe keeps it and {@code ping()} becomes the
     * shorthand for callers that genuinely only need the flag.
     *
     * @param reachable whether the broker answered within the probe's budget
     * @param error the flattened failure message, or {@code null} when reachable
     */
    public record PingResult(boolean reachable, String error) {}

    public PingResult pingDetail() {
        try {
            adminClient.listTopics().names().get(2, TimeUnit.SECONDS);
            return new PingResult(true, null);
        } catch (Exception e) {
            return new PingResult(false, SqlErrorClassifier.explain(e));
        }
    }

    public List<String> getSampleMessages(String topicName, int maxMessages) {
        return getRecentRecords(topicName, maxMessages).stream()
                .map(org.apache.kafka.clients.consumer.ConsumerRecord::value)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecentRecords(String topicName, int maxMessages) {
        return getRecordsWithPredicate(topicName, maxMessages, null);
    }

    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getEarliestRecords(String topicName, int maxMessages) {
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        ExplorerConsumerGroups.configure(props, "records");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get(5, TimeUnit.SECONDS);
            TopicDescription desc = descriptions.get(topicName);
            if (desc == null) return records;
            List<TopicPartition> partitions = desc.partitions().stream()
                    .map(p -> new TopicPartition(topicName, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            records.addAll(drain(consumer, partitions, maxMessages));
        } catch (Exception e) {
            log.error("Error fetching earliest records for topic {}", topicName, e);
        }
        return records;
    }

    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecordsSince(String topicName, int minutes, int maxMessages) {
        long timestampLimit = System.currentTimeMillis() - ((long) minutes * 60 * 1000);
        return getRecordsWithPredicate(topicName, maxMessages, timestampLimit);
    }

    /**
     * Core logic for fetching records from Kafka with optional time-based filtering.
     *
     * Strategy:
     * 1. If timestampLimit is provided, we use offsetsForTimes to find the starting offsets.
     * 2. Otherwise, we calculate the starting offset by subtracting maxMessages from the end offset.
     * 3. We use manual partition assignment (assign) instead of group management (subscribe)
     *    to have full control over seeking.
     */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecordsWithPredicate(String topicName, int maxMessages, Long timestampLimit) {
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        // Use a unique group ID to avoid triggering unnecessary rebalances and to ignore existing offsets.
        ExplorerConsumerGroups.configure(props, "flow-records");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get(5, TimeUnit.SECONDS);
            TopicDescription desc = descriptions.get(topicName);
            if (desc == null) return records;

            List<TopicPartition> partitions = desc.partitions().stream()
                    .map(p -> new TopicPartition(topicName, p.partition()))
                    .toList();

            consumer.assign(partitions);

            if (timestampLimit != null) {
                Map<TopicPartition, Long> timestampsToSearch = partitions.stream()
                        .collect(Collectors.toMap(tp -> tp, tp -> timestampLimit));
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp> offsets = consumer.offsetsForTimes(timestampsToSearch);
                for (TopicPartition tp : partitions) {
                    org.apache.kafka.clients.consumer.OffsetAndTimestamp oat = offsets.get(tp);
                    if (oat != null) {
                        consumer.seek(tp, oat.offset());
                    } else {
                        consumer.seekToEnd(Collections.singletonList(tp));
                    }
                }
            } else {
                Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
                for (TopicPartition tp : partitions) {
                    long endOffset = endOffsets.get(tp);
                    // Clamp to the beginning offset: on topics where retention has deleted old
                    // segments, seeking below it is an out-of-range position and the consumer
                    // resets to auto.offset.reset (default "latest"), silently returning nothing.
                    long beginningOffset = beginningOffsets.getOrDefault(tp, 0L);
                    long startOffset = Math.max(beginningOffset, endOffset - (maxMessages / partitions.size() + 1));
                    consumer.seek(tp, startOffset);
                }
            }

            records.addAll(drain(consumer, partitions, maxMessages));
        } catch (Exception e) {
            log.error("Error fetching records for topic {}", topicName, e);
        }
        return records;
    }

    /** Poll timeout of the record-drain loop. */
    private static final java.time.Duration DRAIN_POLL_TIMEOUT = java.time.Duration.ofMillis(500);
    /**
     * Consecutive empty polls tolerated before giving up on a partition set that still has
     * uncaught-up offsets. Only a safety net — the loop normally stops on the end offsets.
     */
    private static final int DRAIN_MAX_EMPTY_POLLS = 3;
    /**
     * Wall-clock budget for one drain. A constant rather than an {@code ExplorerConfig} property:
     * this service is constructed with only {@code KafkaConfig} in a dozen tests, and the value
     * only exists to stop a slow or unhealthy broker from pinning the calling thread — callers
     * already bound how much they ask for.
     */
    private static final long DRAIN_BUDGET_MS = 20_000;

    /**
     * Reads up to {@code maxMessages} from the already-assigned and already-seeked partitions.
     *
     * <p>Termination is driven by the <strong>end offsets</strong>, not by an empty poll. Both
     * callers used to stop at the first {@code poll()} that returned nothing, which is a lie about
     * the topic: the first poll of a fresh consumer very often comes back empty while metadata is
     * still being resolved or the fetch is in flight. The audit's duplicate detection then judged a
     * topic on a handful of records — or none at all — and reported a confident zero.
     *
     * <p>A wall-clock budget and a cap on consecutive empty polls keep a slow or unhealthy broker
     * from pinning the calling thread. Callers see a short result and, in the audit's case, report
     * the count they actually scanned.
     */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> drain(
            KafkaConsumer<byte[], byte[]> consumer, List<TopicPartition> partitions, int maxMessages) {
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DRAIN_BUDGET_MS);
        int emptyPolls = 0;

        while (records.size() < maxMessages) {
            if (!hasUnreadOffsets(consumer, partitions, endOffsets)) break; // caught up: really done
            if (System.nanoTime() >= deadline) {
                log.debug("Record scan budget spent after {} record(s)", records.size());
                break;
            }
            org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> polled = consumer.poll(DRAIN_POLL_TIMEOUT);
            if (polled.isEmpty()) {
                if (++emptyPolls >= DRAIN_MAX_EMPTY_POLLS) break;
                continue;
            }
            emptyPolls = 0;
            for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record : polled) {
                String value = deserializeValue(record.topic(), record.value());
                String key = record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null;
                records.add(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                    record.topic(), record.partition(), record.offset(), record.timestamp(), record.timestampType(),
                    -1, -1, key, value, record.headers(), record.leaderEpoch()));
                if (records.size() >= maxMessages) break;
            }
        }
        return records;
    }

    /** True while at least one assigned partition still has records before its end offset. */
    private static boolean hasUnreadOffsets(KafkaConsumer<byte[], byte[]> consumer,
                                            List<TopicPartition> partitions,
                                            Map<TopicPartition, Long> endOffsets) {
        for (TopicPartition tp : partitions) {
            Long end = endOffsets.get(tp);
            if (end != null && consumer.position(tp) < end) return true;
        }
        return false;
    }
}
