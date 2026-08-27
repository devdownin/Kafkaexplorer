// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.parser.AvroSchemaInferrer;
import com.compagnonsdudev.kafkasqlexplorer.parser.JsonSchemaInferrer;
import com.compagnonsdudev.kafkasqlexplorer.parser.XmlSchemaInferrer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks against a real Kafka 4.3 broker (KRaft, native image) that the
 * mocked unit suite cannot cover: the kafka-clients 4.x admin surface used by
 * {@link KafkaAdminService} (metadata quorum, group listing, feature lag), record
 * sampling through the ConsumerRecord copy path, and a KIP-848 consumer actually
 * joining with the new rebalance protocol.
 *
 * Skipped automatically when no Docker daemon is available (e.g. restricted
 * sandboxes); runs in CI and on developer machines.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaClusterIntegrationTest {

    /**
     * Two knobs beyond the image, and neither is decoration.
     *
     * <p>{@code withStartupAttempts(3)} exists because a launch that fails is not a test that
     * failed: it happened twice in twelve hours on hosted runners — once here, once on a push to
     * {@code main} — and it takes the whole {@code mvn verify} down with it, this class's own
     * assertions never having run. It was 2, and 2 was not enough: a later pull request lost its
     * {@code build} job to the same "Container startup failed for image apache/kafka-native:4.3.1"
     * with 797 tests green beside it, and a manual re-run of the job was all it took to pass.
     * Three attempts is not a claim that three is the right number — it is one more than the
     * count observed to be insufficient, alongside the CI step that now pulls the image before
     * the suite runs, so a registry that cannot be reached is named as such instead of arriving
     * as a failed test. That is survivable on a pull request, where a re-run costs a
     * few minutes; it is not on {@code release.yml}, which gates a tag on the same {@code verify}
     * and offers no way to retry without cutting the version again. One retry turns the commonest
     * infrastructure hiccup into a delay instead of a failed release.
     *
     * <p>The startup timeout is raised from the 60 s default because it is measured against a
     * cold pull of the image on a runner that may also be pulling for the {@code docker} job:
     * the wait strategy is what decides whether a slow pull reads as a broken broker. Both are
     * bounded — this must never become an unbounded wait, which is how a wedged container turns
     * into a six-hour job.
     *
     * <p>Deliberately <em>not</em> a retry around the assertions: a broker that started and then
     * misbehaved is a finding, and re-running that would hide exactly what this class exists to
     * catch.
     */
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"))
        .withStartupAttempts(3)
        .withStartupTimeout(Duration.ofMinutes(3));

    private static final String TOPIC = "it.orders.json";
    /** Three partitions, four records each, then everything below offset 2 deleted. */
    private static final String TRIMMED_TOPIC = "it.trimmed.multipart";
    private static final int TRIMMED_PARTITIONS = 3;
    private static final int TRIMMED_PER_PARTITION = 4;
    private static final int TRIMMED_DELETED_BELOW = 2;

    private static KafkaAdminService adminService;
    private static KafkaConfig kafkaConfig;

    @BeforeAll
    static void setUp() throws Exception {
        kafkaConfig = new KafkaConfig();
        kafkaConfig.setBootstrapServers(KAFKA.getBootstrapServers());
        kafkaConfig.setSchemaRegistryUrl(null); // no registry in this stack
        adminService = new KafkaAdminService(kafkaConfig);
        adminService.init();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(adminProps)) {
            admin.createTopics(List.of(
                new NewTopic(TRIMMED_TOPIC, TRIMMED_PARTITIONS, (short) 1))).all().get();

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                for (int i = 0; i < 3; i++) {
                    producer.send(new ProducerRecord<>(TOPIC, "key-" + i, "{\"id\": " + i + ", \"status\": \"NEW\"}"));
                }
                for (int partition = 0; partition < TRIMMED_PARTITIONS; partition++) {
                    for (int i = 0; i < TRIMMED_PER_PARTITION; i++) {
                        producer.send(new ProducerRecord<>(TRIMMED_TOPIC, partition,
                            "key-" + partition + "-" + i,
                            "{\"id\": \"" + partition + "-" + i + "\"}"));
                    }
                }
                producer.flush();
            }

            // What retention does, done on purpose: the oldest records are deleted and the log
            // start offset moves past 0. Nothing else in the suite produces this state, and it is
            // the state in which seeking to 0 is out of range.
            Map<TopicPartition, RecordsToDelete> toDelete = new java.util.HashMap<>();
            for (int partition = 0; partition < TRIMMED_PARTITIONS; partition++) {
                toDelete.put(new TopicPartition(TRIMMED_TOPIC, partition),
                    RecordsToDelete.beforeOffset(TRIMMED_DELETED_BELOW));
            }
            admin.deleteRecords(toDelete).all().get();
        }
    }

    private static KafkaSnapshotReader snapshotReader() {
        ProcessMiningConfig processMiningConfig = new ProcessMiningConfig();
        return new KafkaSnapshotReader(kafkaConfig, adminService, processMiningConfig,
            new PayloadDigestService(processMiningConfig));
    }

    /**
     * A query engine wired to this broker, built on demand.
     *
     * <p>Not a field: it starts a local Flink cluster, and only the two metric-path cases below
     * need one — the rest of this class talks to the broker directly.
     */
    private static FlinkSqlService flinkService() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env,
            EnvironmentSettings.newInstance().inStreamingMode().build());

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        config.setFlinkJobStorePath(Files.createTempFile("it-flink-jobs-", ".json").toString());
        config.setFlinkTableStorePath(Files.createTempFile("it-flink-tables-", ".json").toString());

        SchemaInferenceService schemaInference = new SchemaInferenceService(
            config, new JsonSchemaInferrer(), new XmlSchemaInferrer(),
            new AvroSchemaInferrer(kafkaConfig), adminService);
        DdlGeneratorService ddlGenerator =
            new DdlGeneratorService(kafkaConfig, new NamingConventionService());

        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        return new FlinkSqlService(tableEnv, coordinator, config,
            new SqlQueryValidator(config, tableEnv, coordinator),
            adminService, schemaInference, ddlGenerator,
            new FlinkJobStore(config), new FlinkTableStore(config));
    }

    /** The value a count-delta metric publishes: the last numeric row, never the first. */
    private static Double lastMetricValue(QueryResult result) {
        Double last = null;
        for (Map<String, Object> row : result.rows()) {
            if (row.get("metric_value") instanceof Number n) last = n.doubleValue();
        }
        return last;
    }

    @AfterAll
    static void tearDown() {
        if (adminService != null) {
            adminService.close();
        }
    }

    @Test
    void listsTopicsAndCountsMessages() throws Exception {
        List<String> topics = adminService.listTopics();
        assertTrue(topics.contains(TOPIC), "produced topic should be listed");

        Map<String, Long> sizes = adminService.getTopicsSize(List.of(TOPIC));
        assertEquals(3L, sizes.get(TOPIC), "all produced messages should be counted");
    }

    @Test
    void samplesEarliestRecordsWithValues() {
        List<ConsumerRecord<String, String>> records = adminService.getEarliestRecords(TOPIC, 10);
        assertEquals(3, records.size());
        assertTrue(records.get(0).value().contains("\"status\""), "JSON payload should round-trip");
        assertNotNull(records.get(0).key());
    }

    /**
     * A snapshot read of a topic retention has trimmed returns the records that are still there.
     *
     * <p>The seek used to clamp at {@code 0}, which on a trimmed log is below the first surviving
     * record: the position is out of range, the consumer applies {@code auto.offset.reset} —
     * {@code latest}, in this mode — and jumps to the end. Nothing is delivered and a topic full of
     * records reads as an empty one, which is indistinguishable from a quiet cluster by anything
     * downstream. No mocked consumer can catch this: {@code MockConsumer} emulates neither the
     * out-of-range condition nor the reset, so the fault only exists against a real broker.
     */
    @Test
    void readsATopicWhoseOldestRecordsRetentionHasDeleted() {
        List<KafkaMessage> messages = snapshotReader()
            .read(List.of(TRIMMED_TOPIC), SnapshotConfig.latestN(200));

        int surviving = TRIMMED_PARTITIONS * (TRIMMED_PER_PARTITION - TRIMMED_DELETED_BELOW);
        assertEquals(surviving, messages.size(),
            "seeking below the log start resets to the end and delivers nothing");
        assertTrue(messages.stream().allMatch(m -> TRIMMED_TOPIC.equals(m.topic())));
    }

    /**
     * A snapshot read of several topics returns all of them, not whichever answered first.
     *
     * <p>Two faults produced the same symptom against a real broker and neither is reproducible
     * against a mock: a loop that stopped at the first empty poll — which a fresh consumer very
     * often returns while metadata resolves — and one that compared {@code consumer.position()},
     * the client's prefetch position, which runs ahead of what has been delivered. Measured on the
     * demo cluster, one poll delivered two records of one topic while position() reported the log
     * end for all eighteen partitions.
     */
    @Test
    void readsEveryTopicOfAMultiTopicSnapshot() {
        List<KafkaMessage> messages = snapshotReader()
            .read(List.of(TOPIC, TRIMMED_TOPIC), SnapshotConfig.latestN(200));

        assertEquals(Set.of(TOPIC, TRIMMED_TOPIC),
            messages.stream().map(KafkaMessage::topic).collect(java.util.stream.Collectors.toSet()),
            "a topic whose records arrive after another's must still be sampled");
        assertEquals(3 + TRIMMED_PARTITIONS * (TRIMMED_PER_PARTITION - TRIMMED_DELETED_BELOW),
            messages.size(), "every surviving record of both topics");
    }

    /**
     * And the audit's own sampling path, which shares the defect and the fix.
     * {@code getEarliestRecords} reads through {@code drain()}, whose cursor used to be
     * {@code position()} as well.
     */
    @Test
    void samplesEveryPartitionOfATrimmedTopic() {
        List<ConsumerRecord<String, String>> records =
            adminService.getEarliestRecords(TRIMMED_TOPIC, 100);

        assertEquals(TRIMMED_PARTITIONS * (TRIMMED_PER_PARTITION - TRIMMED_DELETED_BELOW),
            records.size(), "the audit samples a trimmed, multi-partition topic in full");
    }

    @Test
    void clusterDetailsExposeKraftQuorumGroupsAndFeatures() {
        Map<String, Object> details = adminService.getClusterDetails();
        assertNull(details.get("error"), "cluster details should not degrade to an error");
        assertNotNull(details.get("clusterId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> quorum = (Map<String, Object>) details.get("kraftQuorum");
        assertNotNull(quorum, "KRaft broker must expose the metadata quorum");
        assertTrue((int) quorum.get("leaderId") >= 0);
        assertFalse(((List<?>) quorum.get("voters")).isEmpty(), "single combined node is a voter");

        assertNotNull(details.get("groups"), "Kafka 4 broker must support ListGroups");

        // A freshly formatted 4.3 broker finalizes metadata.version at the release level:
        // it must never be reported as lagging (that signal is reserved for real
        // half-finished rolling upgrades).
        List<Map<String, Object>> lagging = adminService.getLaggingFeatures();
        assertTrue(lagging.stream().noneMatch(f -> "metadata.version".equals(f.get("feature"))),
                "fresh cluster should not report a lagging metadata.version, got: " + lagging);
    }

    @Test
    void kip848ConsumerJoinsAndIsListedWithConsumerType() throws Exception {
        String groupId = "it-kip848-group";
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer"); // KIP-848
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));

            int received = 0;
            long deadline = System.currentTimeMillis() + 30_000;
            while (received < 3 && System.currentTimeMillis() < deadline) {
                received += consumer.poll(Duration.ofMillis(500)).count();
            }
            assertEquals(3, received, "KIP-848 consumer should receive every produced message");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups =
                    (List<Map<String, Object>>) adminService.getClusterDetails().get("groups");
            Map<String, Object> group = groups.stream()
                    .filter(g -> groupId.equals(g.get("groupId")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("group " + groupId + " not listed in " + groups));
            assertEquals("CONSUMER", group.get("type"), "KIP-848 group must be listed with the CONSUMER type");
        }
    }

    /**
     * Whether this connector can bound a scan at all — the question D1 was fixed on a reading of
     * the documentation, and that only a broker can answer.
     *
     * <p>It answered no. {@code flink-connector-kafka:5.0.0-2.2} refuses
     * {@code scan.bounded.mode} outright, and the shape of that refusal is the part worth pinning:
     * {@code FlinkSqlService} classifies it as an <em>engine</em> failure, so it falls back to the
     * direct reader and returns rows with <b>no error</b>. A caller cannot tell the option was
     * rejected — which is why {@code MetricService} no longer sends it, and why the degrade-once
     * latch that was written to make sending it safe could never have fired.
     *
     * <p>The day a connector bump supports the option, this test fails and says so. That is the
     * point of asserting the current answer rather than working around it.
     */
    @Test
    void thisConnectorRefusesToBoundAScanAndSaysSoOnlyToTheLog() throws Exception {
        FlinkSqlService flink = flinkService();
        String table = DdlGeneratorService.toTableName(TOPIC);
        String count = "SELECT COUNT(*) AS metric_value FROM " + table;

        QueryResult bounded = flink.executeSql(QueryRequest.sql(
            count + " /*+ OPTIONS('scan.startup.mode'='earliest-offset','scan.bounded.mode'='latest-offset') */",
            10_000, 20_000L, "earliest-offset"));

        assertNull(bounded.error(), "the refusal is swallowed by the fallback, not surfaced");
        assertEquals("KAFKA_DIRECT", bounded.engine(),
            "the planner refused the option and the query fell back — if this ever reads FLINK, "
                + "the connector has gained scan.bounded.mode and MetricService can send it again");

        // The fallback still answers the question correctly, which is the only reason the defect
        // was survivable: the direct reader counts the records rather than reading a changelog.
        assertEquals(3.0, lastMetricValue(bounded));

        // Without the option the planner cannot finish either — an unbounded streaming COUNT(*)
        // spends its whole budget — so on this stack the two are indistinguishable from outside,
        // and the templates ask the direct reader by name rather than hoping.
        QueryResult unbounded = flink.executeSql(QueryRequest.sql(count, 10_000, 4_000L, "earliest-offset"));
        assertEquals("KAFKA_DIRECT", unbounded.engine());
    }

    /**
     * The count a metric actually publishes, through the reader the template now asks for by name.
     *
     * <p>A single-table read goes to the direct reader rather than the planner (D2/D3), so this is
     * the path a {@code TOPIC_COUNT_DELTA} side really takes. Against a mock it proves nothing
     * about the records; here the number has to come out of the broker.
     */
    @Test
    void theDirectReaderCountsTheRecordsThatAreReallyThere() throws Exception {
        FlinkSqlService flink = flinkService();
        String table = DdlGeneratorService.toTableName(TOPIC);

        QueryResult result = flink.executeSql(QueryRequest.directSql(
            "SELECT COUNT(*) AS metric_value FROM " + table, 10_000, 20_000L, "earliest-offset"));

        assertNull(result.error(), String.valueOf(result.error()));
        assertEquals("KAFKA_DIRECT", result.engine(), "directRead must not consult the planner");
        assertEquals(1, result.rows().size(), "an aggregate on this reader is one row, not a changelog");
        assertEquals(3.0, lastMetricValue(result));
    }
}
