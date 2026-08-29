// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivity;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.common.errors.TopicExistsException;
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
    private volatile AdminClient adminClient;

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

    /**
     * Consulted by {@link #cachedPartitions} only, and optional on purpose: this service is
     * constructed with {@code KafkaConfig} alone in a dozen tests, so a required dependency here
     * would be a constructor change in all of them for a memo. Absent, the read is simply not
     * memoized — same answer, one more round trip.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CacheManager cacheManager;

    /**
     * Optional for the same reason as {@link #cacheManager}: a dozen tests construct this service
     * with {@code KafkaConfig} alone. Absent, the consumer pool is sized 0 — which is also the
     * shipped default, so the tests exercise the same path an untouched deployment does.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig explorerConfig;

    /**
     * Lends consumers to the three per-topic record readers. Rebuilt by {@link #init()}, which is
     * also the cluster-repoint hook — a pooled client carries the bootstrap address it was built
     * with, so it must not outlive the configuration that produced it.
     */
    private volatile KafkaConsumerPool consumerPool = new KafkaConsumerPool(0);

    public KafkaAdminService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    /**
     * Builds the admin client, and is also what {@code POST /api/config} calls to repoint the
     * application at another cluster — so it runs with the rest of the application live around it.
     *
     * <p>It used to close the previous client and <em>then</em> create the replacement, leaving
     * every other thread holding a closed {@code AdminClient} for the duration: a dashboard poll or
     * a readiness probe landing in that window failed with "The AdminClient is closed", which reads
     * as an unreachable cluster rather than as a settings save in progress. The field was not
     * {@code volatile} either, so nothing published the new client to those threads. Build first,
     * publish, close the old one last — the swap is then a single visible write, and the worst a
     * concurrent caller sees is one call answered by the previous cluster, which is what
     * {@code POST /api/config} already promises about work in flight.
     */
    @PostConstruct
    public void init() {
        AdminClient previous = this.adminClient;
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        this.adminClient = AdminClient.create(props);

        // Before anything else can borrow one: an idle consumer built for the previous cluster
        // would answer a question about this one with a connection to that one.
        KafkaConsumerPool previousPool = this.consumerPool;
        this.consumerPool = new KafkaConsumerPool(
                explorerConfig == null ? 0 : explorerConfig.getConsumerPoolSize());
        if (previousPool != null) previousPool.close();
        if (previous != null) {
            // Bounded for the same reason as close() below: this runs when the cluster is
            // repointed, which is very often *because* the previous one stopped answering.
            previous.close(Duration.ofSeconds(5));
        }

        if (kafkaConfig.getSchemaRegistryUrl() != null) {
            this.schemaRegistryClient = new CachedSchemaRegistryClient(kafkaConfig.getSchemaRegistryUrl(), 100);
            Map<String, Object> avroProps = new HashMap<>();
            avroProps.put("schema.registry.url", kafkaConfig.getSchemaRegistryUrl());
            this.avroDeserializer = new KafkaAvroDeserializer(schemaRegistryClient, avroProps);
        }
    }

    @PreDestroy
    public void close() {
        consumerPool.close();
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

    /**
     * A {@code GenericDatumWriter} per Avro schema, rather than one per record.
     *
     * <p>The writer resolves the schema when it is built, and the JSON encoder allocates its own
     * parser and output stack — three objects and a schema walk per record, on a path the audit
     * drives ten thousand times for a single topic's duplicate scan. A writer is stateless once
     * built and safe to share; the encoder is not, so it stays per call. Bounded because the key
     * is the schema and a topic whose producers evolve it would otherwise grow this without end.
     */
    private final Map<org.apache.avro.Schema, org.apache.avro.io.DatumWriter<GenericRecord>> avroWriters =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<org.apache.avro.Schema, org.apache.avro.io.DatumWriter<GenericRecord>> eldest) {
                    return size() > 64;
                }
            });

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
                            org.apache.avro.Schema schema = genericRecord.getSchema();
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            org.apache.avro.io.Encoder encoder =
                                org.apache.avro.io.EncoderFactory.get().jsonEncoder(schema, out);
                            avroWriters.computeIfAbsent(schema,
                                    org.apache.avro.generic.GenericDatumWriter::new)
                                .write(genericRecord, encoder);
                            encoder.flush();
                            return out.toString(StandardCharsets.UTF_8);
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
     * Cached (30s TTL): each call costs a describeTopics and two listOffsets over every partition
     * of every topic, and the dashboard polls it for the whole cluster. It no longer costs a
     * KafkaConsumer as well — this reads offsets and not one record, so the client it opened was
     * pure overhead; see {@link #readRecordCounts}.
     *
     * <p><b>A topic this could not measure comes back as {@code 0}</b>, which is the right shape
     * for the dashboard's column — a missing key there is a null pointer in the UI — and the wrong
     * one for any caller that has to act on the difference between "this topic is empty" and "we
     * could not read this topic". {@link #getTopicRecordCounts(List)} is the same measurement
     * without that flattening, and this method is now a thin honest-to-lenient adapter over it, so
     * there is one offsets read in the tree rather than two that can drift.
     */
    @Cacheable(value = "topicSizes", key = "#topicNames")
    public Map<String, Long> getTopicsSize(List<String> topicNames) {
        Map<String, Long> sizes = new HashMap<>();
        if (topicNames.isEmpty()) return sizes;

        // Initialize with 0 to prevent null pointers or missing keys in UI
        topicNames.forEach(name -> sizes.put(name, 0L));
        sizes.putAll(readRecordCounts(topicNames));
        return sizes;
    }

    /**
     * How many records each topic holds — <b>omitting the ones it could not measure</b>.
     *
     * <p>The count is {@code endOffset - beginningOffset} summed over the partitions, so it is
     * what is still readable rather than what was ever produced: retention and compaction have
     * already been applied, which is exactly the question "is there anything here for a query to
     * read" asks. A topic the broker describes no partition for, or one whose offsets did not
     * come back, carries <b>no entry</b> rather than a zero — {@code 0} is a measurement ("this
     * topic is empty") and handing it back for a read that failed is the flattening this codebase
     * keeps removing: a caller acting on it reports a topic as empty on the strength of a broker
     * blip.
     *
     * <p>Cached on the same 30 s TTL as its lenient sibling, under its own name so the two
     * contracts cannot be served from one another's entry.
     */
    @Cacheable(value = "topicRecordCounts", key = "#topicNames")
    public Map<String, Long> getTopicRecordCounts(List<String> topicNames) {
        if (topicNames == null || topicNames.isEmpty()) return Map.of();
        return readRecordCounts(topicNames);
    }

    private Map<String, Long> readRecordCounts(List<String> topicNames) {
        Map<String, Long> sizes = new HashMap<>();

        Map<String, List<TopicPartition>> topicToPartitions;
        try {
            topicToPartitions = cachedPartitions(topicNames, 10_000);
        } catch (Exception e) {
            log.error("Failed to describe topics for record counts", e);
            return sizes;
        }
        List<TopicPartition> allPartitions = topicToPartitions.values().stream()
                .flatMap(List::stream).toList();
        if (allPartitions.isEmpty()) return sizes;

        try {
            // Both bounds through the admin client rather than a throw-away KafkaConsumer. It is
            // the same ListOffsets request either way, and the consumer bought nothing here: it
            // reads no record, so the client it needed was pure overhead on the dashboard's
            // hottest call — a connection, a handshake and a metadata fetch every 30 s. The two
            // requests are also issued before either is awaited, so they overlap instead of
            // queueing, and each partition is awaited on its own future: one that does not answer
            // costs its own contribution where the consumer's map-returning form threw the batch.
            ListOffsetsResult earliest = adminClient.listOffsets(specs(allPartitions, OffsetSpec.earliest()));
            ListOffsetsResult latest = adminClient.listOffsets(specs(allPartitions, OffsetSpec.latest()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

            for (Map.Entry<String, List<TopicPartition>> entry : topicToPartitions.entrySet()) {
                long size = 0L;
                int measured = 0;
                for (TopicPartition tp : entry.getValue()) {
                    try {
                        long start = awaitOffset(earliest, tp, deadline).offset();
                        long end = awaitOffset(latest, tp, deadline).offset();
                        size += end - start;
                        measured++;
                    } catch (Exception e) {
                        log.debug("Offsets for {}-{} could not be read: {}", LogSafe.name(tp.topic()),
                                tp.partition(), LogSafe.text(SqlErrorClassifier.explain(e)));
                    }
                }
                // A partition that did not answer costs its own contribution, so the count is
                // a floor — which is harmless, since every caller here reads it as "is there
                // anything to measure". A topic where *none* answered is a different matter:
                // the sum would be 0, and 0 is the one value that must never be invented, so
                // the topic carries no entry at all rather than a fabricated emptiness.
                if (measured > 0) sizes.put(entry.getKey(), size);
            }
        } catch (Exception e) {
            log.error("Failed to read record counts for: {}", topicNames, e);
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

    // ── Lag in time ───────────────────────────────────────────────────────────

    /** Partitions one time-lag read will measure. Beyond this the answer costs more than it says. */
    private static final int TIME_LAG_MAX_PARTITIONS = 64;
    /** Wall-clock budget for the record reads of one time-lag measurement. */
    private static final long TIME_LAG_BUDGET_MS = 8_000;
    private static final Duration TIME_LAG_POLL = Duration.ofMillis(400);

    /**
     * How far behind <em>in time</em> a group is on a topic — the age of the oldest record still
     * waiting on each partition.
     *
     * <p>A record lag is a count, and a count cannot be acted on without a rate: ten thousand
     * messages is ten seconds on one topic and a fortnight on another. The number an operator
     * actually reasons about ("the payments consumer is four minutes behind") needs the timestamp
     * of the record sitting at the committed offset, which no admin call returns — hence the
     * consumer read here, and hence its budget.
     *
     * <p>One consumer, one seek per lagging partition, one drain: the record at each committed
     * offset is the first one that partition yields, so the whole measurement is a single poll
     * loop rather than a read per partition. Partitions committed at the end of the log are not
     * read at all — they are caught up, which is a measurement, not an absence of one.
     *
     * <p>The end offsets are read <b>after</b> the committed ones, the same ordering the record
     * lag uses and for the same reason: a consumer committing between the two calls can then only
     * make the lag look larger, never smaller, so a reading of zero is one that was earned.
     *
     * @param groupId the group to measure; must not be blank — a delay is always somebody's
     */
    public TopicTimeLag getConsumerTimeLag(String topic, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return TopicTimeLag.unavailable(topic, groupId, "A consumer group is required to measure a delay.");
        }
        TopicPartitions resolved = resolvePartitions(topic);
        if (resolved.error() != null) return TopicTimeLag.unavailable(topic, groupId, resolved.error());

        List<TopicPartition> partitions = resolved.partitions();
        List<String> warnings = new ArrayList<>();
        if (partitions.size() > TIME_LAG_MAX_PARTITIONS) {
            warnings.add("Topic has " + partitions.size() + " partitions; the "
                + TIME_LAG_MAX_PARTITIONS + " lowest-numbered ones were measured.");
            partitions = partitions.subList(0, TIME_LAG_MAX_PARTITIONS);
        }

        Map<TopicPartition, OffsetAndMetadata> committed;
        try {
            committed = adminClient
                .listConsumerGroupOffsets(Map.of(groupId,
                    new ListConsumerGroupOffsetsSpec().topicPartitions(partitions)))
                .partitionsToOffsetAndMetadata(groupId)
                .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return TopicTimeLag.unavailable(topic, groupId,
                "Could not read the committed offsets of '" + groupId + "': " + rootMessage(e));
        }
        if (committed == null || committed.values().stream().allMatch(Objects::isNull)) {
            return TopicTimeLag.unavailable(topic, groupId,
                "Group '" + groupId + "' has no committed offset on this topic.");
        }

        Map<TopicPartition, Long> endOffsets;
        try {
            Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
            partitions.forEach(tp -> request.put(tp, OffsetSpec.latest()));
            endOffsets = adminClient.listOffsets(request).all().get(10, TimeUnit.SECONDS)
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        } catch (Exception e) {
            return TopicTimeLag.unavailable(topic, groupId,
                "Could not read the topic's end offsets: " + rootMessage(e));
        }

        // Partitions that still hold something to read are the only ones worth a seek.
        Map<TopicPartition, Long> toRead = new LinkedHashMap<>();
        List<PartitionTimeLag> resultsSoFar = new ArrayList<>();
        int withoutCommit = 0;
        for (TopicPartition tp : partitions) {
            OffsetAndMetadata offset = committed.get(tp);
            long end = endOffsets.getOrDefault(tp, 0L);
            if (offset == null) {
                withoutCommit++;
                resultsSoFar.add(PartitionTimeLag.unknown(tp.partition(), null, end, null,
                    "The group has never committed on this partition — its backlog is counted by nothing here."));
                continue;
            }
            long position = offset.offset();
            if (position >= end) {
                resultsSoFar.add(PartitionTimeLag.caughtUp(tp.partition(), position, end));
            } else {
                toRead.put(tp, position);
            }
        }

        Map<Integer, Long> timestamps = toRead.isEmpty()
            ? Map.of()
            : readTimestampsAt(toRead, warnings);

        long now = System.currentTimeMillis();
        List<PartitionTimeLag> results = new ArrayList<>(resultsSoFar);
        for (Map.Entry<TopicPartition, Long> entry : toRead.entrySet()) {
            TopicPartition tp = entry.getKey();
            long position = entry.getValue();
            long end = endOffsets.getOrDefault(tp, 0L);
            Long timestamp = timestamps.get(tp.partition());
            if (timestamp == null) {
                // Compacted away, deleted by retention, or the budget ran out. Reporting 0 here
                // would say "caught up" about the partition we know is behind.
                results.add(PartitionTimeLag.unknown(tp.partition(), position, end, end - position,
                    "The record at the committed offset could not be read — compacted, aged out, or the read budget was spent."));
            } else {
                results.add(new PartitionTimeLag(tp.partition(), position, end, end - position,
                    Math.max(0L, now - timestamp), timestamp, null));
            }
        }
        results.sort(Comparator.comparingInt(PartitionTimeLag::partition));

        List<Long> measured = results.stream()
            .map(PartitionTimeLag::lagMs)
            .filter(Objects::nonNull)
            .toList();
        int unknown = (int) results.stream()
            .filter(p -> p.lagMs() == null && p.committedOffset() != null)
            .count();
        int caughtUp = (int) results.stream()
            .filter(p -> p.lagMs() != null && p.lagMs() == 0L)
            .count();

        if (measured.isEmpty()) {
            return new TopicTimeLag(topic, groupId, results, null, null, 0, 0, withoutCommit, unknown,
                false, "No partition's delay could be measured.", warnings);
        }
        long max = measured.stream().mapToLong(Long::longValue).max().orElse(0L);
        long avg = Math.round(measured.stream().mapToLong(Long::longValue).average().orElse(0.0));
        return new TopicTimeLag(topic, groupId, results, max, avg, measured.size(), caughtUp,
            withoutCommit, unknown, true, null, warnings);
    }

    /**
     * The timestamp of the record sitting at each given offset, in one poll loop.
     *
     * <p>Every partition is seeked to its committed offset and the drain keeps the <em>first</em>
     * record each one yields — which is exactly the record that consumer has not consumed yet.
     * The loop ends when every partition has answered or the budget is spent; a partition that
     * never answers is left out of the map, so the caller reports it as unmeasured rather than
     * inventing a zero.
     */
    private Map<Integer, Long> readTimestampsAt(Map<TopicPartition, Long> offsets, List<String> warnings) {
        Map<Integer, Long> timestamps = new HashMap<>();
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        // Never commits, never joins the measured group: a measurement that moved the very offsets
        // it reads would be its own worst source of error.
        ExplorerConsumerGroups.configure(props, "time-lag");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (Consumer<byte[], byte[]> consumer = createTimeLagConsumer(props)) {
            List<TopicPartition> partitions = new ArrayList<>(offsets.keySet());
            consumer.assign(partitions);
            offsets.forEach(consumer::seek);

            long deadline = System.currentTimeMillis() + TIME_LAG_BUDGET_MS;
            while (timestamps.size() < partitions.size() && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(TIME_LAG_POLL);
                if (records.isEmpty()) continue;
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    // First record wins: it is the one at the committed offset.
                    timestamps.putIfAbsent(record.partition(), record.timestamp());
                }
            }
            if (timestamps.size() < partitions.size()) {
                warnings.add("The read budget was spent before " + (partitions.size() - timestamps.size())
                    + " partition(s) answered; their delay is reported as unknown, not as zero.");
            }
        } catch (Exception e) {
            log.debug("Time-lag record read failed: {}", e.toString());
            warnings.add("The records at the committed offsets could not be read: " + rootMessage(e));
        }
        return timestamps;
    }

    /**
     * Test seam — {@code KafkaAdminServiceTimeLagTest} drives a {@link
     * org.apache.kafka.clients.consumer.MockConsumer} through it. Same pattern as
     * {@code TopicSearchService.createConsumer()}.
     */
    protected Consumer<byte[], byte[]> createTimeLagConsumer(Properties props) {
        return new KafkaConsumer<>(props);
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
                    // Leftovers of older builds first, then by id so a pass is reproducible. The
                    // cut used to be alphabetical over ids that are UUIDs, which is a random cut
                    // dressed up as an order; what a capped pass should remove first is the
                    // backlog no build running today can recreate.
                    .sorted(Comparator
                        .comparing((String id) -> !ExplorerConsumerGroups.isLegacyGroup(id))
                        .thenComparing(Comparator.naturalOrder()))
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

    // ── The application's own topics ──────────────────────────────────────────

    /**
     * Creates {@code name} if it is absent, with {@code configs}, and says whether it did.
     *
     * <p>Partitions and replication are deliberately left to the broker's own defaults
     * ({@code Optional.empty()}): what this application has an opinion about is the retention
     * policy of its own stores, not how a given cluster sizes a topic — a hard-coded replication
     * factor of 1 would be wrong on every real cluster, and 3 would fail on every single-node one.
     *
     * <p>A concurrent creation (two explorers pointed at one cluster, both starting) comes back as
     * {@link TopicExistsException} and is reported as "already there", not as a failure: the
     * outcome asked for is the outcome obtained.
     *
     * @return true when this call created the topic
     */
    public boolean createTopicIfAbsent(String name, Map<String, String> configs)
            throws ExecutionException, InterruptedException, TimeoutException {
        NewTopic topic = new NewTopic(name, Optional.empty(), Optional.empty()).configs(configs);
        try {
            adminClient.createTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) return false;
            throw e;
        }
    }

    /** The topic's configuration as the broker reports it, empty when it could not be read. */
    public Map<String, String> getTopicConfigs(String name) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        try {
            Config config = adminClient.describeConfigs(List.of(resource))
                    .all().get(10, TimeUnit.SECONDS).get(resource);
            if (config == null) return Map.of();
            Map<String, String> values = new LinkedHashMap<>();
            for (ConfigEntry entry : config.entries()) {
                if (entry.value() != null) values.put(entry.name(), entry.value());
            }
            return values;
        } catch (Exception e) {
            log.warn("Could not read the configuration of topic '{}': {}", LogSafe.name(name), rootMessage(e));
            return Map.of();
        }
    }

    /**
     * Sets the given entries on a topic, leaving every other entry alone.
     *
     * <p>{@code incrementalAlterConfigs} rather than the deprecated whole-config form, which
     * replaces the resource's configuration outright — an operator's {@code min.insync.replicas}
     * is not ours to reset while fixing a cleanup policy.
     */
    public void alterTopicConfigs(String name, Map<String, String> configs)
            throws ExecutionException, InterruptedException, TimeoutException {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        List<AlterConfigOp> ops = configs.entrySet().stream()
                .map(e -> new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();
        adminClient.incrementalAlterConfigs(Map.of(resource, ops)).all().get(15, TimeUnit.SECONDS);
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

    /**
     * When each topic last received a record — <b>read from offset metadata, not from records</b>.
     *
     * <p>This is the dashboard's "Last Message" column, and it used to be the most expensive read
     * in the application by a wide margin. It opened a consumer, assigned <em>every non-empty
     * partition of every topic on the cluster</em>, seeked each to {@code end - 1} and polled until
     * a record came back from each — to keep one {@code long} per partition and throw the payloads
     * away. A fetch does not return one record: it returns the batch the record sits in, up to
     * {@code max.partition.fetch.bytes} (1 MiB by default) <em>per partition</em>. So a
     * three-partition cluster of three hundred topics pulled the tail of nine hundred partitions
     * across the network, every 30 s, to render a column of relative dates.
     *
     * <p>Kafka answers the question directly. {@code ListOffsets} with {@link
     * OffsetSpec#maxTimestamp()} (KIP-734) returns the largest record timestamp a partition holds,
     * from the broker's own index — no record is read, no consumer is created, and the whole
     * cluster is one request. It is also the better measurement: the previous code read the record
     * at the <em>end</em> of the log, whose timestamp is the newest only when producers stamp in
     * order, while this is the maximum by definition.
     *
     * <p>The spec needs a broker at Kafka 3.0 or later, and this application supports 2.1+, so an
     * older one is not an error — {@link #lastTimestampsByPolling} does what this method used to.
     * Anything else that fails costs the topics it names and nothing more: each partition is
     * awaited on its own future rather than through {@code .all()}, and a topic with no answer
     * carries no entry rather than a fabricated instant, the rule every read here follows.
     */
    @Cacheable(value = "topicLastMessages", key = "#topicNames")
    public Map<String, Long> getTopicsLastMessageTimestamps(List<String> topicNames) {
        Map<String, Long> timestamps = new HashMap<>();
        if (topicNames == null || topicNames.isEmpty()) return timestamps;

        Map<String, List<TopicPartition>> topicToPartitions;
        try {
            topicToPartitions = cachedPartitions(topicNames, 10_000);
        } catch (Exception e) {
            log.error("Failed to describe topics for last-message timestamps", e);
            return timestamps;
        }
        List<TopicPartition> all = topicToPartitions.values().stream().flatMap(List::stream).toList();
        if (all.isEmpty()) return timestamps;

        ListOffsetsResult result;
        try {
            result = adminClient.listOffsets(specs(all, OffsetSpec.maxTimestamp()));
        } catch (Exception e) {
            log.error("Failed to get topic last-message timestamps", e);
            return timestamps;
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Map.Entry<String, List<TopicPartition>> entry : topicToPartitions.entrySet()) {
            long newest = Long.MIN_VALUE;
            for (TopicPartition tp : entry.getValue()) {
                long ts;
                try {
                    ts = awaitOffset(result, tp, deadline).timestamp();
                } catch (Exception e) {
                    if (isUnsupportedVersion(e)) {
                        log.info("This broker does not implement ListOffsets(maxTimestamp) (Kafka 3.0+); "
                            + "reading the last record of each partition instead.");
                        return lastTimestampsByPolling(topicToPartitions);
                    }
                    // One partition that did not answer costs its own contribution. The topic's
                    // instant is then the newest of the ones that did, which can only be older
                    // than the truth — never a fabricated one.
                    log.debug("maxTimestamp for {}-{} could not be read: {}", LogSafe.name(tp.topic()),
                            tp.partition(), LogSafe.text(SqlErrorClassifier.explain(e)));
                    continue;
                }
                // An empty partition answers -1 for both the offset and the timestamp, and so
                // does one whose records predate message timestamps altogether. Neither is an
                // instant, and 1970 rendered as "56 years ago" is a worse answer than none.
                if (ts > 0) newest = Math.max(newest, ts);
            }
            if (newest > Long.MIN_VALUE) timestamps.put(entry.getKey(), newest);
        }
        return timestamps;
    }

    /**
     * What {@link #getTopicsLastMessageTimestamps} did before the offset index could be asked, kept
     * for brokers older than Kafka 3.0: seek every non-empty partition to its last record and poll
     * until each has answered. Costs a consumer and the tail of every partition on the cluster.
     */
    private Map<String, Long> lastTimestampsByPolling(Map<String, List<TopicPartition>> topicToPartitions) {
        Map<String, Long> timestamps = new HashMap<>();
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "timestamps");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> allPartitions = topicToPartitions.values().stream()
                    .flatMap(List::stream).toList();
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
            for (Map.Entry<String, List<TopicPartition>> entry : topicToPartitions.entrySet()) {
                OptionalLong maxTs = entry.getValue().stream()
                        .filter(partitionTimestamps::containsKey)
                        .mapToLong(partitionTimestamps::get)
                        .max();
                if (maxTs.isPresent()) {
                    timestamps.put(entry.getKey(), maxTs.getAsLong());
                }
            }
        } catch (Exception e) {
            log.error("Failed to get topic last-message timestamps", e);
        }
        return timestamps;
    }

    /**
     * True when a failure is the broker saying it does not implement the request, at any depth of
     * the cause chain — the admin client wraps it in an {@link ExecutionException}, and a
     * {@code KafkaFuture} awaited per partition wraps it again.
     *
     * <p>It is the one failure that must not be reported as "this could not be read": the question
     * is answerable here, just not by the cheap route, so it selects a fallback rather than an
     * error message.
     */
    private static boolean isUnsupportedVersion(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof org.apache.kafka.common.errors.UnsupportedVersionException) return true;
        }
        return false;
    }

    /**
     * Topic name to its partitions, from one {@code describeTopics}.
     *
     * <p>Per-topic futures rather than {@code allTopicNames()}: one topic the cluster does not know
     * — deleted between the {@code listTopics} that named it and this call, which on a live cluster
     * is an ordinary race — must cost that topic's row and not everybody else's. Its three callers
     * are the bulk reads behind the dashboard: the record counts and the last-message timestamps,
     * where {@code allTopicNames()} turned a single stale name into an empty table, and the
     * activity curve, which had been fixed for that on its own and whose loop this is.
     */
    private Map<String, List<TopicPartition>> describePartitions(List<String> topicNames, long timeoutMs) {
        return describePartitions(topicNames, timeoutMs, null);
    }

    /**
     * The same map, memoized on the same 30 s TTL as everything else this service caches.
     *
     * <p>The dashboard's two bulk reads are separately {@code @Cacheable}, so on a cold entry they
     * both run — and both began by describing every topic of the cluster, which is one metadata
     * request more than the question needs. They share the answer here instead.
     *
     * <p>Deliberately not {@code @Cacheable} on this method: it is called from inside this bean,
     * where self-invocation bypasses the Spring proxy and the annotation would do nothing at all —
     * the trap {@code AuditService} carries a warning about for {@code @Async}. And deliberately
     * not shared with {@link #getTopicActivity}, which collects a <em>reason</em> per topic it
     * could not describe: a cache hit has no reasons to give, and that response states its own
     * scope from them.
     */
    private Map<String, List<TopicPartition>> cachedPartitions(List<String> topicNames, long timeoutMs) {
        Cache cache = cacheManager == null ? null : cacheManager.getCache("topicPartitions");
        if (cache == null) return describePartitions(topicNames, timeoutMs);

        List<String> key = List.copyOf(topicNames);
        @SuppressWarnings("unchecked")
        Map<String, List<TopicPartition>> hit = cache.get(key, Map.class);
        if (hit != null) return hit;

        Map<String, List<TopicPartition>> described = describePartitions(topicNames, timeoutMs);
        // A read that described nothing is not cached: the broker being unreachable for one call
        // must not freeze an empty cluster for the next thirty seconds, which is the rule the
        // consumer and activity reads already follow with their `unless` clauses.
        if (!described.isEmpty()) cache.put(key, described);
        return described;
    }

    /**
     * Same, naming what it could not describe.
     *
     * @param warnings collector for the topics that failed, or {@code null} to log them instead —
     *     a caller that reports its own scope needs the names, one that does not must still not
     *     swallow them silently
     */
    private Map<String, List<TopicPartition>> describePartitions(List<String> topicNames, long timeoutMs,
                                                                List<String> warnings) {
        Map<String, List<TopicPartition>> byTopic = new LinkedHashMap<>();
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(topicNames));
        Map<String, KafkaFuture<TopicDescription>> described =
                adminClient.describeTopics(distinct).topicNameValues();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (String name : distinct) {
            KafkaFuture<TopicDescription> future = described.get(name);
            if (future == null) continue;
            try {
                TopicDescription description = future.get(remainingMs(deadline), TimeUnit.MILLISECONDS);
                byTopic.put(name, description.partitions().stream()
                        .map(p -> new TopicPartition(name, p.partition()))
                        .toList());
            } catch (Exception e) {
                String reason = SqlErrorClassifier.explain(e);
                if (warnings != null) {
                    warnings.add("Topic '" + name + "' could not be described: " + reason);
                } else {
                    log.debug("Topic {} could not be described: {}", LogSafe.name(name), LogSafe.text(reason));
                }
            }
        }
        return byTopic;
    }

    /** Fewer than four points is not a curve; more than sixty is a sparkline nobody can read. */
    public static final int ACTIVITY_MIN_BUCKETS = 4;
    public static final int ACTIVITY_MAX_BUCKETS = 60;
    /** A window under a minute buckets into instants; over a month, into buckets nothing fills. */
    public static final long ACTIVITY_MIN_WINDOW_MS = 60_000L;
    public static final long ACTIVITY_MAX_WINDOW_MS = 30L * 24 * 60 * 60 * 1000L;
    /**
     * Wall clock for one activity read. A constant rather than a property: every call it makes is
     * a metadata round trip issued in parallel, so this bounds latency, not work — the work is
     * bounded by {@code maxLookups}, which is where an operator has something to decide.
     */
    private static final long ACTIVITY_TIMEOUT_MS = 15_000L;

    /**
     * How much each of these topics produced, bucket by bucket, over the last {@code windowMs}.
     *
     * <p>This is the dashboard's sparkline column, and it is deliberately built out of
     * {@code listOffsets} alone: one request per bucket boundary, each covering every partition of
     * every topic asked for, all issued before any is awaited. Nothing opens a consumer, nothing
     * reads a record, and nothing joins a group — a curve per row on a page of twenty-five topics
     * costs the boundaries' round trips and no more, which is the only reason it can sit in a
     * table that refreshes on a timer.
     *
     * <p>What a bucket counts is stated in {@link TopicActivity}: offsets produced, not records
     * present. The distinction matters on a compacted topic, where the two answers legitimately
     * differ, and it is why this method exists beside {@link #getTopicsSize} rather than deriving
     * a curve from it.
     *
     * <p>The window is <b>aligned</b> on the bucket width and ends at the last <b>completed</b>
     * bucket. Two consecutive polls inside one bucket therefore describe exactly the same
     * boundaries — which keeps the curve from wobbling as the clock moves, makes this cacheable at
     * all, and keeps the last point from being a half-filled bucket that reads as a collapse in
     * traffic.
     *
     * <p>Cached (30 s TTL) like every other metadata read here, and <b>a failed read is not
     * cached</b>: the dashboard's refresh is the gesture that exists to retry, and replaying a
     * cached failure for half a minute would answer that gesture with the failure it was trying to
     * clear.
     *
     * @param topicNames topics to measure, in the caller's own order — the lookup budget, when it
     *                   bites, keeps the head of that list, so a page shows the rows it displays
     * @param windowMs   how far back to look, clamped to [{@value #ACTIVITY_MIN_WINDOW_MS},
     *                   {@value #ACTIVITY_MAX_WINDOW_MS}]
     * @param buckets    points in the series, clamped to [{@value #ACTIVITY_MIN_BUCKETS},
     *                   {@value #ACTIVITY_MAX_BUCKETS}]
     * @param maxLookups ceiling on partitions × boundaries for the whole call; topics past it are
     *                   left out and named in the response's warnings, never silently dropped
     */
    @Cacheable(value = "topicActivity",
            key = "#topicNames + '@' + #windowMs + '/' + #buckets + '/' + #maxLookups",
            unless = "!#result.available()")
    public TopicActivityResponse getTopicActivity(List<String> topicNames, long windowMs, int buckets,
                                                  int maxLookups) {
        return getTopicActivity(topicNames, windowMs, buckets, maxLookups, System.currentTimeMillis());
    }

    /**
     * The same read against a stated instant.
     *
     * <p>The window is derived from the clock, so nothing could assert which bucket a record falls
     * into without saying when "now" is — and a test that computes the alignment a microsecond
     * before the method does is a test that fails whenever the two land either side of a boundary.
     * The instant is a parameter here and read from the clock above; nothing else differs.
     */
    TopicActivityResponse getTopicActivity(List<String> topicNames, long windowMs, int buckets,
                                           int maxLookups, long nowMs) {
        int bucketCount = Math.clamp(buckets, ACTIVITY_MIN_BUCKETS, ACTIVITY_MAX_BUCKETS);
        long window = Math.clamp(windowMs, ACTIVITY_MIN_WINDOW_MS, ACTIVITY_MAX_WINDOW_MS);
        long bucketMs = Math.max(1000L, window / bucketCount);
        long end = (nowMs / bucketMs) * bucketMs;
        long start = end - bucketMs * bucketCount;

        if (topicNames == null || topicNames.isEmpty()) {
            return new TopicActivityResponse(Map.of(), start, end, bucketMs, bucketCount, true, List.of());
        }

        List<String> warnings = new ArrayList<>();
        Map<String, List<TopicPartition>> topicPartitions;
        try {
            // Per-topic futures rather than allTopicNames(): one topic the cluster does not know
            // must cost that topic's row, not the whole column. Shared with the two bulk reads the
            // dashboard makes beside this one — see describePartitions.
            topicPartitions = describePartitions(topicNames, 10_000, warnings);
        } catch (Exception e) {
            return TopicActivityResponse.unavailable(start, end, bucketMs, bucketCount,
                    "Topic metadata could not be read: " + SqlErrorClassifier.explain(e));
        }

        // The budget is spent from the head of the caller's list, and what it cuts is named. A
        // curve missing from a row reads as a topic that produced nothing, which is exactly the
        // kind of silence a bounded read must not leave behind.
        int perPartition = bucketCount + 1;
        int spent = 0;
        Map<String, List<TopicPartition>> inScope = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, List<TopicPartition>> entry : topicPartitions.entrySet()) {
            int cost = entry.getValue().size() * perPartition;
            if (!inScope.isEmpty() && spent + cost > maxLookups) {
                skipped.add(entry.getKey());
                continue;
            }
            inScope.put(entry.getKey(), entry.getValue());
            spent += cost;
        }
        if (!skipped.isEmpty()) {
            warnings.add(skipped.size() + " topic(s) were left out of this read: it would have taken more than "
                    + maxLookups + " offset lookups (explorer.activity-max-lookups). Not measured: "
                    + String.join(", ", skipped.subList(0, Math.min(5, skipped.size())))
                    + (skipped.size() > 5 ? ", …" : ""));
        }

        List<TopicPartition> all = inScope.values().stream().flatMap(List::stream).toList();
        if (all.isEmpty()) {
            return new TopicActivityResponse(Map.of(), start, end, bucketMs, bucketCount,
                    warnings.isEmpty(), warnings);
        }

        Map<String, TopicActivity> series = new LinkedHashMap<>();
        try {
            // Every request is issued before any is awaited: N+3 round trips overlap instead of
            // queueing, so the wall clock is one round trip's latency rather than sixty.
            ListOffsetsResult earliest = adminClient.listOffsets(specs(all, OffsetSpec.earliest()));
            ListOffsetsResult latest = adminClient.listOffsets(specs(all, OffsetSpec.latest()));
            List<ListOffsetsResult> boundaries = new ArrayList<>(perPartition);
            for (int i = 0; i <= bucketCount; i++) {
                boundaries.add(adminClient.listOffsets(specs(all, OffsetSpec.forTimestamp(start + i * bucketMs))));
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACTIVITY_TIMEOUT_MS);
            for (Map.Entry<String, List<TopicPartition>> entry : inScope.entrySet()) {
                series.put(entry.getKey(), buildActivity(entry.getKey(), entry.getValue(), earliest, latest,
                        boundaries, start, end, bucketMs, bucketCount, deadline));
            }
        } catch (Exception e) {
            return TopicActivityResponse.unavailable(start, end, bucketMs, bucketCount,
                    "Offsets could not be read: " + SqlErrorClassifier.explain(e));
        }

        boolean available = series.values().stream().anyMatch(TopicActivity::available);
        return new TopicActivityResponse(series, start, end, bucketMs, bucketCount, available, warnings);
    }

    private static Map<TopicPartition, OffsetSpec> specs(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        return request;
    }

    private static long remainingMs(long deadlineNanos) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private static ListOffsetsResult.ListOffsetsResultInfo awaitOffset(
            ListOffsetsResult result, TopicPartition tp, long deadlineNanos) throws Exception {
        return result.partitionResult(tp).get(remainingMs(deadlineNanos), TimeUnit.MILLISECONDS);
    }

    /**
     * One topic's series, summed over its partitions.
     *
     * <p>Each partition is awaited on its own future rather than through {@code .all()}: one
     * partition that cannot be read costs its own contribution — reported through
     * {@code partitionsMeasured}, which makes the series a floor — where {@code .all()} would cost
     * the topic its whole curve.
     */
    private TopicActivity buildActivity(String topic, List<TopicPartition> partitions,
                                        ListOffsetsResult earliest, ListOffsetsResult latest,
                                        List<ListOffsetsResult> boundaries,
                                        long start, long end, long bucketMs, int bucketCount,
                                        long deadlineNanos) {
        long[] counts = new long[bucketCount];
        int measured = 0;
        Long coveredFrom = null;
        String firstFailure = null;

        for (TopicPartition tp : partitions) {
            try {
                long endOffset = awaitOffset(latest, tp, deadlineNanos).offset();
                long beginOffset = awaitOffset(earliest, tp, deadlineNanos).offset();
                if (endOffset < 0 || beginOffset < 0) {
                    throw new IllegalStateException("the broker reported no offset for this partition");
                }

                long[] positions = new long[bucketCount + 1];
                long firstResolvedOffset = -1L;
                long firstResolvedTimestamp = -1L;
                for (int i = 0; i <= bucketCount; i++) {
                    ListOffsetsResult.ListOffsetsResultInfo info = awaitOffset(boundaries.get(i), tp, deadlineNanos);
                    long offset = info.offset();
                    if (offset < 0) {
                        // No record at or after this boundary: everything this partition holds was
                        // produced before it, so the position is the end of the log.
                        offset = endOffset;
                    } else if (firstResolvedOffset < 0) {
                        firstResolvedOffset = offset;
                        firstResolvedTimestamp = info.timestamp();
                    }
                    positions[i] = Math.clamp(offset, beginOffset, endOffset);
                }

                for (int i = 0; i < bucketCount; i++) {
                    counts[i] += Math.max(0L, positions[i + 1] - positions[i]);
                }
                measured++;

                // The oldest surviving record starts after the window does, on a log that has been
                // trimmed: what was produced before it is gone, and the buckets covering that
                // stretch would otherwise read as a quiet night rather than as deleted history.
                if (beginOffset > 0 && firstResolvedOffset == beginOffset
                        && firstResolvedTimestamp > start && firstResolvedTimestamp < end) {
                    coveredFrom = coveredFrom == null
                            ? firstResolvedTimestamp : Math.max(coveredFrom, firstResolvedTimestamp);
                }
            } catch (Exception e) {
                if (firstFailure == null) firstFailure = SqlErrorClassifier.explain(e);
                log.debug("Activity: partition {} could not be read: {}", tp, e.toString());
            }
        }

        if (measured == 0) {
            return TopicActivity.unavailable(topic, start, end, bucketMs,
                    "Offsets could not be read for any of the " + partitions.size() + " partition(s): "
                            + (firstFailure == null ? "no reason reported" : firstFailure));
        }

        List<String> notes = new ArrayList<>();
        if (measured < partitions.size()) {
            notes.add((partitions.size() - measured) + " of " + partitions.size()
                    + " partitions could not be read, so these counts are a floor: " + firstFailure);
        }
        if (coveredFrom != null) {
            notes.add("Records produced before " + java.time.Instant.ofEpochMilli(coveredFrom)
                    + " have been deleted by retention, so the earlier buckets are floors rather than quiet periods.");
        }

        List<Long> series = new ArrayList<>(bucketCount);
        long total = 0;
        for (long count : counts) {
            series.add(count);
            total += count;
        }
        return new TopicActivity(topic, start, end, bucketMs, series, total, coveredFrom,
                measured, partitions.size(), true, notes.isEmpty() ? null : String.join(" ", notes));
    }

    /**
     * A topic's partitions, taken from the consumer that is about to read them.
     *
     * <p>The three record fetchers each opened with {@code adminClient.describeTopics(topic)} and a
     * five-second await, purely to turn a name into a partition list — while the consumer beside
     * them fetched metadata for that same topic anyway, the moment it was assigned. That is one
     * round trip and one client's worth of waiting, on every sample, per topic, and the audit
     * takes one sample per topic of the cluster. {@code partitionsFor} answers from the metadata
     * the read needs regardless.
     *
     * <p>It is safe to ask it about a topic that does not exist only because every internal
     * consumer is configured with {@code allow.auto.create.topics=false} — see {@link
     * ExplorerConsumerGroups#configure}. Without that, this question would answer itself by
     * creating the topic.
     *
     * @return the partitions, or an empty list when the cluster has no such topic
     */
    private static List<TopicPartition> partitionsOf(Consumer<byte[], byte[]> consumer, String topicName) {
        // Bounded explicitly: the no-argument overload waits default.api.timeout.ms, which is a
        // minute, where the describeTopics await it replaces gave up after five seconds. A
        // metadata read that hangs a request thread for a minute is not an improvement on a round
        // trip. (A topic the cluster does not know answers null straight away, without retrying.)
        List<org.apache.kafka.common.PartitionInfo> infos =
                consumer.partitionsFor(topicName, METADATA_TIMEOUT);
        if (infos == null) return List.of();
        return infos.stream()
                .map(info -> new TopicPartition(topicName, info.partition()))
                .sorted(Comparator.comparingInt(TopicPartition::partition))
                .toList();
    }

    /** The offsets a partition currently spans: its first surviving record, and one past its last. */
    public record OffsetRange(long beginning, long end) {}

    /**
     * Both bounds of one partition, asked for together.
     *
     * <p>{@code Consumer.beginningOffsets} and {@code endOffsets} each block, so a caller needing
     * both waits two round trips in series. These are futures: the two requests are issued before
     * either is awaited, so the wait is one round trip.
     *
     * @return {@code null} when there is no admin client, or when the broker did not answer —
     *     which is a different thing from an empty partition, and the caller has a slower way to
     *     ask
     */
    public OffsetRange offsetRange(TopicPartition tp) {
        AdminClient admin = this.adminClient;
        if (admin == null) return null;
        try {
            List<TopicPartition> one = List.of(tp);
            ListOffsetsResult earliest = admin.listOffsets(specs(one, OffsetSpec.earliest()));
            ListOffsetsResult latest = admin.listOffsets(specs(one, OffsetSpec.latest()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            return new OffsetRange(awaitOffset(earliest, tp, deadline).offset(),
                                   awaitOffset(latest, tp, deadline).offset());
        } catch (Exception e) {
            log.debug("Offset range for {}-{} could not be read: {}", LogSafe.name(tp.topic()),
                    tp.partition(), LogSafe.text(SqlErrorClassifier.explain(e)));
            return null;
        }
    }

    /** Where the newest record of a topic sits, as the broker's offset index reports it. */
    private record NewestRecord(TopicPartition partition, long offset, long timestamp) {}

    /**
     * The answer to "which partition holds the newest record?", which has <b>three</b> values.
     *
     * <p>"We could not ask" is one of them, and folding it into "there is none" is the flattening
     * this codebase keeps removing: the question is still answerable, just not by the cheap route,
     * so it has to select the fallback rather than empty the result.
     *
     * @param answered whether the broker implements {@code ListOffsets(maxTimestamp)} at all
     * @param record where the newest record sits, or {@code null} when every partition is empty —
     *     which is a measurement, not a failure
     */
    private record NewestLookup(boolean answered, NewestRecord record) {
        static final NewestLookup UNANSWERED = new NewestLookup(false, null);
        static NewestLookup of(NewestRecord record) { return new NewestLookup(true, record); }
    }

    /** The partition and offset of the topic's newest record, from {@code ListOffsets(maxTimestamp)}. */
    private NewestLookup newestRecordOf(List<TopicPartition> partitions) {
        ListOffsetsResult result;
        try {
            result = adminClient.listOffsets(specs(partitions, OffsetSpec.maxTimestamp()));
        } catch (Exception e) {
            log.debug("maxTimestamp offsets could not be requested: {}",
                    LogSafe.text(SqlErrorClassifier.explain(e)));
            return NewestLookup.UNANSWERED;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        NewestRecord best = null;
        for (TopicPartition tp : partitions) {
            ListOffsetsResult.ListOffsetsResultInfo info;
            try {
                info = awaitOffset(result, tp, deadline);
            } catch (Exception e) {
                // The broker refusing the spec is answerable by the other route, so it selects the
                // fallback; one partition that did not answer costs only its own candidacy.
                if (isUnsupportedVersion(e)) return NewestLookup.UNANSWERED;
                log.debug("maxTimestamp for {}-{} could not be read: {}", LogSafe.name(tp.topic()),
                        tp.partition(), LogSafe.text(SqlErrorClassifier.explain(e)));
                continue;
            }
            // -1 on both is an empty partition, or one whose records carry no timestamp.
            if (info.timestamp() <= 0 || info.offset() < 0) continue;
            if (best == null || info.timestamp() > best.timestamp()
                    || (info.timestamp() == best.timestamp() && info.offset() > best.offset())) {
                best = new NewestRecord(tp, info.offset(), info.timestamp());
            }
        }
        return NewestLookup.of(best);
    }

    /**
     * The newest record of a topic — <b>one partition read, not every partition</b>.
     *
     * <p>It used to seek the last record of every partition, poll until each had answered, and
     * keep the one with the highest timestamp: N partition fetches, N-1 of them discarded, and on
     * a topic of large payloads N batches pulled across the network for one record. The broker can
     * say which partition holds the newest record before anything is read — {@code
     * ListOffsets(maxTimestamp)} returns that timestamp <em>and</em> its offset — so the read is a
     * single assigned partition at a known offset.
     *
     * <p>The selection rule is the one it replaces (highest timestamp, offset breaking a tie);
     * only the cost changed. Where the broker predates that spec (Kafka 3.0) the offsets are read
     * the old way — {@code end - 1} per partition — and every candidate is polled, exactly as
     * before.
     */
    public Optional<KafkaMessage> getLatestMessage(String topicName) {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        ExplorerConsumerGroups.configure(props, "latest-message");

        KafkaConsumerPool.Lease lease = consumerPool.lease(props);
        try {
            Consumer<byte[], byte[]> consumer = lease.consumer();
            List<TopicPartition> partitions = partitionsOf(consumer, topicName);
            if (partitions.isEmpty()) return Optional.empty();

            List<TopicPartition> candidates;
            NewestLookup newest = newestRecordOf(partitions);
            if (newest.answered()) {
                // The broker answered: exactly one partition holds the newest record, and at which
                // offset is already known. Nothing else has to be fetched.
                if (newest.record() == null) {
                    return Optional.empty();   // every partition is empty — a measurement, not a failure
                }
                candidates = List.of(newest.record().partition());
                consumer.assign(candidates);
                consumer.seek(newest.record().partition(), newest.record().offset());
            } else {
                // The broker does not implement maxTimestamp: fall back to the last record of
                // every partition, which is what this method used to do unconditionally.
                Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
                Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
                candidates = partitions.stream()
                    .filter(tp -> {
                        Long begin = beginning.get(tp);
                        Long last = end.get(tp);
                        return begin != null && last != null && last > begin;
                    })
                    .toList();
                if (candidates.isEmpty()) return Optional.empty();
                consumer.assign(candidates);
                candidates.forEach(tp -> consumer.seek(tp, end.get(tp) - 1));
            }

            Map<TopicPartition, ConsumerRecord<byte[], byte[]>> latestByPartition = new HashMap<>();
            Set<TopicPartition> pending = new HashSet<>(candidates);
            int retries = 4;
            while (retries-- > 0 && !pending.isEmpty()) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(java.time.Duration.ofMillis(500));
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    latestByPartition.putIfAbsent(tp, record);
                    pending.remove(tp);
                }
            }

            return latestByPartition.values().stream()
                .max(Comparator
                    .comparingLong(ConsumerRecord<byte[], byte[]>::timestamp)
                    .thenComparingLong(ConsumerRecord<byte[], byte[]>::offset))
                .map(record -> new KafkaMessage(
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.timestamp(),
                    record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null,
                    deserializeValue(record.topic(), record.value())
                ));
        } catch (Exception e) {
            lease.discard();
            log.error("Failed to get latest message for topic {}", LogSafe.name(topicName), e);
            return Optional.empty();
        } finally {
            lease.close();
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

    /** How long the reachability probe waits before calling the broker unreachable. */
    static final long PING_TIMEOUT_MS = 2_000;

    public PingResult pingDetail() {
        try {
            adminClient.listTopics().names().get(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new PingResult(true, null);
        } catch (TimeoutException e) {
            // A future's TimeoutException carries no message, so SqlErrorClassifier.explain() can
            // only fall back to the class name — and "TimeoutException" told an operator neither
            // what timed out nor how long we waited, on the one message the connection pill and
            // the startup summary both quote verbatim. The budget is known here, so it is said.
            // Without the address: every caller already shows it beside this message.
            return new PingResult(false, "No answer within " + PING_TIMEOUT_MS + " ms");
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
        KafkaConsumerPool.Lease lease = consumerPool.lease(props);
        try {
            Consumer<byte[], byte[]> consumer = lease.consumer();
            List<TopicPartition> partitions = partitionsOf(consumer, topicName);
            if (partitions.isEmpty()) return records;
            consumer.assign(partitions);
            // Seeked explicitly rather than through seekToBeginning, because drain() needs to be
            // told where the read starts — see the cursor it keeps. Same offsets either way.
            Map<TopicPartition, Long> startOffsets = consumer.beginningOffsets(partitions);
            startOffsets.forEach(consumer::seek);
            records.addAll(drain(consumer, startOffsets, consumer.endOffsets(partitions), maxMessages));
        } catch (Exception e) {
            // The borrower does not vouch for a client whose read threw: it is closed, not pooled.
            lease.discard();
            log.error("Error fetching earliest records for topic {}", LogSafe.name(topicName), e);
        } finally {
            lease.close();
        }
        return records;
    }

    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecordsSince(String topicName, int minutes, int maxMessages) {
        return getRecordsSinceTimestamp(topicName,
            System.currentTimeMillis() - ((long) minutes * 60 * 1000), maxMessages);
    }

    /**
     * Records at or after an <b>instant</b>, rather than at or after a duration ago.
     *
     * <p>The distinction is the whole point for a caller reading two topics that must describe the
     * same window: a duration is resolved against the clock at the moment it is read, so two sides
     * read one after the other cover two windows offset by however long the first read took. An
     * instant is computed once and applied to both, so the pairs they yield are pairs rather than
     * an accident of the two topics' throughputs. {@code MetricService}'s transit latency is that
     * caller; {@link #getRecordsSince(String, int, int)} keeps the duration form for the rest.
     */
    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecordsSinceTimestamp(
            String topicName, long timestampMs, int maxMessages) {
        return getRecordsWithPredicate(topicName, maxMessages, timestampMs);
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

        KafkaConsumerPool.Lease lease = consumerPool.lease(props);
        try {
            Consumer<byte[], byte[]> consumer = lease.consumer();
            List<TopicPartition> partitions = partitionsOf(consumer, topicName);
            if (partitions.isEmpty()) return records;

            consumer.assign(partitions);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> startOffsets = new HashMap<>();

            if (timestampLimit != null) {
                Map<TopicPartition, Long> timestampsToSearch = partitions.stream()
                        .collect(Collectors.toMap(tp -> tp, tp -> timestampLimit));
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp> offsets = consumer.offsetsForTimes(timestampsToSearch);
                for (TopicPartition tp : partitions) {
                    org.apache.kafka.clients.consumer.OffsetAndTimestamp oat = offsets.get(tp);
                    // No offset at or after the instant asked for means every record in this
                    // partition predates it: there is nothing to read, which the end offset states
                    // as a number drain() can compare rather than as a position it would read back.
                    Long startOffset = oat != null ? oat.offset() : endOffsets.get(tp);
                    if (startOffset == null) {
                        // No end offset either, so there is no number to say "nothing to read"
                        // with — and a default of 0 would say the opposite, seeking to the
                        // beginning and returning the whole partition, every record of it older
                        // than the instant asked for. seekToEnd states it without a number; the
                        // partition then carries no cursor entry, so drain() leaves it out of its
                        // bookkeeping rather than treating it as unread.
                        consumer.seekToEnd(Collections.singletonList(tp));
                        continue;
                    }
                    consumer.seek(tp, startOffset);
                    startOffsets.put(tp, startOffset);
                }
            } else {
                Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
                for (TopicPartition tp : partitions) {
                    Long endOffset = endOffsets.get(tp);
                    if (endOffset == null) {
                        // "The last N records" is measured backwards from the end, so without an
                        // end offset there is nothing to measure from. The default of 0 that used
                        // to stand here made `max(beginning, 0 - n)` collapse to the beginning —
                        // so a partition whose end could not be read answered a question about
                        // the *newest* records with its oldest ones, which is a wrong answer
                        // rather than a missing one. It contributes nothing instead, the same
                        // rule as the timestamp branch above.
                        consumer.seekToEnd(Collections.singletonList(tp));
                        continue;
                    }
                    // Clamp to the beginning offset: on topics where retention has deleted old
                    // segments, seeking below it is an out-of-range position and the consumer
                    // resets to auto.offset.reset (default "latest"), silently returning nothing.
                    long beginningOffset = beginningOffsets.getOrDefault(tp, 0L);
                    long startOffset = Math.max(beginningOffset, endOffset - (maxMessages / partitions.size() + 1));
                    consumer.seek(tp, startOffset);
                    startOffsets.put(tp, startOffset);
                }
            }

            // The end offsets are the ones already read above: drain() compares its cursor
            // against them, and asking the broker a second time for numbers this method is
            // holding is a listOffsets round trip per sample, on the audit's hot path.
            records.addAll(drain(consumer, startOffsets, endOffsets, maxMessages));
        } catch (Exception e) {
            lease.discard();
            log.error("Error fetching records for topic {}", LogSafe.name(topicName), e);
        } finally {
            lease.close();
        }
        return records;
    }

    /** How long a record fetcher waits for a topic's metadata — what the describeTopics await used to be. */
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(5);

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
     *
     * <p><b>And the cursor is this method's own, never {@code consumer.position(tp)}.</b> That one
     * is the client's <em>fetch</em> position: the consumer prefetches in the background, across
     * every assigned partition, and advances it as responses are buffered rather than as records
     * are returned. Measured on {@code KafkaSnapshotReader}, which had the same comparison, one
     * poll delivered two records while {@code position()} reported the log end for all eighteen
     * partitions — so the read stopped believing itself caught up with most of the records still
     * undelivered. Single-topic reads mask it, which is why it survived here; a topic with enough
     * partitions does not, and both callers of this method are how the audit samples a topic.
     *
     * @param startOffsets where each assigned partition was seeked to — the caller chose it, so
     *                     nobody has to ask the client where the read begins
     */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> drain(
            Consumer<byte[], byte[]> consumer, Map<TopicPartition, Long> startOffsets, int maxMessages) {
        return drain(consumer, startOffsets, consumer.endOffsets(List.copyOf(startOffsets.keySet())), maxMessages);
    }

    /**
     * Same, against end offsets the caller already holds.
     *
     * <p>Both callers read them to decide where to seek, and this method then asked the broker for
     * the same numbers again — a second {@code listOffsets} round trip per sample, and the audit
     * takes a sample per topic. Reading them once also means the loop compares its cursor against
     * the same instant it seeked from, rather than against an end that moved in between.
     */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> drain(
            Consumer<byte[], byte[]> consumer, Map<TopicPartition, Long> startOffsets,
            Map<TopicPartition, Long> endOffsets, int maxMessages) {
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
        List<TopicPartition> partitions = List.copyOf(startOffsets.keySet());
        Map<TopicPartition, Long> nextOffsets = new HashMap<>(startOffsets);
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DRAIN_BUDGET_MS);
        int emptyPolls = 0;

        while (records.size() < maxMessages) {
            if (!hasUnreadOffsets(partitions, endOffsets, nextOffsets)) break; // caught up: really done
            if (System.nanoTime() >= deadline) {
                log.debug("Record scan budget spent after {} record(s)", records.size());
                break;
            }
            // A partition this read has finished with is paused rather than left assigned. The
            // fetcher keeps a request in flight for every assigned partition, so a drained one
            // goes on being fetched — and, having nothing past its end offset, holds each poll for
            // fetch.max.wait.ms while the partitions that still have records are already back.
            // Nothing is ever resumed: the consumer is closed when the read ends, and a partition
            // that has reached its end offset does not un-reach it.
            pauseDrained(consumer, partitions, endOffsets, nextOffsets);
            org.apache.kafka.clients.consumer.ConsumerRecords<byte[], byte[]> polled = consumer.poll(DRAIN_POLL_TIMEOUT);
            if (polled.isEmpty()) {
                if (++emptyPolls >= DRAIN_MAX_EMPTY_POLLS) break;
                continue;
            }
            emptyPolls = 0;
            for (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record : polled) {
                nextOffsets.merge(new TopicPartition(record.topic(), record.partition()),
                    record.offset() + 1, Math::max);
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

    /** Pauses every assigned partition whose cursor has reached the end offset this read seeked against. */
    private static void pauseDrained(Consumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                     Map<TopicPartition, Long> endOffsets, Map<TopicPartition, Long> nextOffsets) {
        Set<TopicPartition> paused = consumer.paused();
        List<TopicPartition> toPause = new ArrayList<>();
        for (TopicPartition tp : partitions) {
            if (paused.contains(tp)) continue;
            Long end = endOffsets.get(tp);
            Long next = nextOffsets.get(tp);
            if (end != null && next != null && next >= end) toPause.add(tp);
        }
        if (!toPause.isEmpty()) consumer.pause(toPause);
    }

    /**
     * True while at least one assigned partition still holds records this read has not been handed.
     *
     * <p>Delegates: the comparison is stated once, in {@link TopicReadCursor}, which the two
     * startup restores also read it from. Three copies of "is this read finished?" is three
     * chances for the next one to be written with {@code consumer.position()} again.
     */
    private static boolean hasUnreadOffsets(List<TopicPartition> partitions,
                                            Map<TopicPartition, Long> endOffsets,
                                            Map<TopicPartition, Long> nextOffsets) {
        return TopicReadCursor.hasUnread(partitions, endOffsets, nextOffsets);
    }
}
