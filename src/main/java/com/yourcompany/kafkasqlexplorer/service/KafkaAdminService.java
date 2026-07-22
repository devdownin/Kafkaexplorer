// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
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
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.SupportedVersionRange;
import org.springframework.stereotype.Service;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
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
    private SchemaRegistryClient schemaRegistryClient;
    private KafkaAvroDeserializer avroDeserializer;

    public KafkaAdminService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    @PostConstruct
    public void init() {
        if (this.adminClient != null) {
            this.adminClient.close();
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
            adminClient.close();
        }
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

    private String deserializeValue(String topic, byte[] value) {
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-bulk-metadata-" + UUID.randomUUID());

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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-metadata-" + UUID.randomUUID());

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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-timestamps-" + UUID.randomUUID());

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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-latest-message-" + UUID.randomUUID());

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
        try {
            adminClient.listTopics().names().get(2, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "explorer-earliest-" + UUID.randomUUID());
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
            int count = 0;
            boolean moreRecords = true;
            while (count < maxMessages && moreRecords) {
                org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> polled = consumer.poll(java.time.Duration.ofMillis(500));
                if (polled.isEmpty()) moreRecords = false;
                for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record : polled) {
                    String value = deserializeValue(record.topic(), record.value());
                    String key = record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null;
                    records.add(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        record.topic(), record.partition(), record.offset(), record.timestamp(), record.timestampType(),
                        -1, -1, key, value, record.headers(), record.leaderEpoch()));
                    count++;
                    if (count >= maxMessages) break;
                }
            }
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "flow-records-" + UUID.randomUUID());
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

            int count = 0;
            boolean moreRecords = true;
            while (count < maxMessages && moreRecords) {
                org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> polled = consumer.poll(java.time.Duration.ofMillis(500));
                if (polled.isEmpty()) moreRecords = false;
                for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record : polled) {
                    String value = deserializeValue(record.topic(), record.value());
                    String key = record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null;
                    records.add(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        record.topic(), record.partition(), record.offset(), record.timestamp(), record.timestampType(),
                        -1, -1, key, value, record.headers(), record.leaderEpoch()));
                    count++;
                    if (count >= maxMessages) break;
                }
            }
        } catch (Exception e) {
            log.error("Error fetching records for topic {}", topicName, e);
        }
        return records;
    }
}
