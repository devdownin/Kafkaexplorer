package com.yourcompany.kafkasqlexplorer.integration;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.MessageProducerService;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class MetricIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MetricIntegrationTest.class);
    private static KafkaContainer kafka;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        String external = System.getProperty("kafka.bootstrap-servers");
        if (external == null) {
            external = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        }

        if (external != null) {
            final String server = external;
            registry.add("kafka.bootstrap-servers", () -> server);
        } else {
            kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
            kafka.start();
            registry.add("kafka.bootstrap-servers", kafka::getBootstrapServers);
        }
        registry.add("explorer.metrics-config-topic", () -> "test.metrics.config");
    }

    @Autowired
    private MetricService metricService;

    @Autowired
    private FlinkSqlService flinkSqlService;

    @Autowired
    private MessageProducerService messageProducerService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaConfig kafkaConfig;

    private void ensureTable(String name) {
        try {
            flinkSqlService.executeSql(QueryRequest.ddl(
                "CREATE TABLE IF NOT EXISTS " + name + " (" +
                "  id STRING," +
                "  amount DOUBLE," +
                "  ts TIMESTAMP(3)," +
                "  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND" +
                ") WITH (" +
                "  'connector' = 'kafka'," +
                "  'topic' = '" + name + "'," +
                "  'properties.bootstrap.servers' = '" + kafkaConfig.getBootstrapServers() + "'," +
                "  'properties.group.id' = 'test-group-" + name + "'," +
                "  'scan.startup.mode' = 'earliest-offset'," +
                "  'format' = 'json'" +
                ")", 10000L));
        } catch (Exception e) {
            log.warn("Failed to ensure table {}: {}", name, e.getMessage());
        }
    }

    @Test
    void testGaugeMetricAccuracy() throws ExecutionException, InterruptedException {
        String topic = "topic_gauge";
        ensureTable(topic);

        messageProducerService.produce(topic, "k1", "{\"id\":\"1\", \"amount\": 10.5, \"ts\": \"2026-03-24T10:00:00Z\"}");
        messageProducerService.produce(topic, "k2", "{\"id\":\"2\", \"amount\": 20.0, \"ts\": \"2026-03-24T10:00:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_gauge")
            .type("GAUGE")
            .sql("SELECT SUM(amount) AS metric_value FROM " + topic)
            .description("Sum of amounts")
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getAllMetrics().stream()
            .filter(m -> "test_gauge".equals(m.name()))
            .findFirst().orElseThrow();

        assertEquals(30.5, saved.lastValue());

        Gauge gauge = meterRegistry.find("explorer_metric_gauge")
            .tag("metric_id", saved.id())
            .gauge();
        assertNotNull(gauge);
        assertEquals(30.5, gauge.value());
    }

    @Test
    void testCounterMetricAccuracy() throws ExecutionException, InterruptedException {
        String topic = "topic_counter";
        ensureTable(topic);

        messageProducerService.produce(topic, "k3", "{\"id\":\"3\", \"amount\": 1.0, \"ts\": \"2026-03-24T10:00:02Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_counter")
            .type("COUNTER")
            .sql("SELECT COUNT(*) AS metric_value FROM " + topic)
            .description("Total count")
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getAllMetrics().stream()
            .filter(m -> "test_counter".equals(m.name()))
            .findFirst().orElseThrow();

        double firstValue = saved.lastValue();

        Counter counter = meterRegistry.find("explorer_metric_counter")
            .tag("metric_id", saved.id())
            .counter();
        assertNotNull(counter);

        messageProducerService.produce(topic, "k4", "{\"id\":\"4\", \"amount\": 1.0, \"ts\": \"2026-03-24T10:00:03Z\"}");
        metricService.refreshMetrics();

        assertEquals(firstValue + 1, metricService.getById(saved.id()).get().lastValue());
    }

    @Test
    void testHistogramMetricAccuracy() throws ExecutionException, InterruptedException {
        String topic = "topic_histogram";
        ensureTable(topic);

        messageProducerService.produce(topic, "h1", "{\"id\":\"h1\", \"amount\": 10.0, \"ts\": \"2026-03-24T10:01:00Z\"}");
        messageProducerService.produce(topic, "h2", "{\"id\":\"h2\", \"amount\": 50.0, \"ts\": \"2026-03-24T10:01:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_histogram")
            .type("HISTOGRAM")
            .sql("SELECT amount AS metric_value FROM " + topic)
            .description("Amount distribution")
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getAllMetrics().stream()
            .filter(m -> "test_histogram".equals(m.name()))
            .findFirst().orElseThrow();

        DistributionSummary summary = meterRegistry.find("explorer_metric_histogram")
            .tag("metric_id", saved.id())
            .summary();
        assertNotNull(summary);
        assertEquals(2, summary.count());
        assertEquals(60.0, summary.totalAmount());
    }

    @Test
    void testSummaryMetricAccuracy() throws ExecutionException, InterruptedException {
        String topic = "topic_summary";
        ensureTable(topic);

        messageProducerService.produce(topic, "s1", "{\"id\":\"s1\", \"amount\": 100.0, \"ts\": \"2026-03-24T10:02:00Z\"}");
        messageProducerService.produce(topic, "s2", "{\"id\":\"s2\", \"amount\": 200.0, \"ts\": \"2026-03-24T10:02:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_summary")
            .type("SUMMARY")
            .sql("SELECT amount AS metric_value FROM " + topic)
            .description("Amount summary")
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getAllMetrics().stream()
            .filter(m -> "test_summary".equals(m.name()))
            .findFirst().orElseThrow();

        DistributionSummary summary = meterRegistry.find("explorer_metric_summary")
            .tag("metric_id", saved.id())
            .summary();
        assertNotNull(summary);
        assertEquals(2, summary.count());
        assertEquals(300.0, summary.totalAmount());
    }

    @Test
    void testTopicCountDeltaTemplate() throws ExecutionException, InterruptedException {
        String topicLeft = "topic_delta_left";
        String topicRight = "topic_delta_right";
        ensureTable(topicLeft);

        try {
            flinkSqlService.executeSql(QueryRequest.ddl(
                "CREATE TABLE IF NOT EXISTS " + topicRight + " (" +
                "  id STRING" +
                ") WITH (" +
                "  'connector' = 'kafka'," +
                "  'topic' = '" + topicRight + "'," +
                "  'properties.bootstrap.servers' = '" + kafkaConfig.getBootstrapServers() + "'," +
                "  'scan.startup.mode' = 'earliest-offset'," +
                "  'format' = 'json'" +
                ")", 10000L));
        } catch (Exception e) {}

        messageProducerService.produce(topicLeft, "a", "{\"id\":\"a\"}");
        messageProducerService.produce(topicLeft, "b", "{\"id\":\"b\"}");
        messageProducerService.produce(topicRight, "c", "{\"id\":\"c\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_delta")
            .type("GAUGE")
            .templateType("TOPIC_COUNT_DELTA")
            .templateParams(Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM " + topicLeft,
                "rightSql", "SELECT COUNT(*) AS metric_value FROM " + topicRight,
                "operation", "LEFT_MINUS_RIGHT"
            ))
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getAllMetrics().stream()
            .filter(m -> "test_delta".equals(m.name()))
            .findFirst().orElseThrow();

        Double left = (Double) saved.lastSummary().get("leftValue");
        Double right = (Double) saved.lastSummary().get("rightValue");
        assertEquals(left - right, saved.lastValue());
    }

    @Test
    void testTransitLatencyTemplate() throws ExecutionException, InterruptedException {
        String sourceTopic = "latency_source";
        String targetTopic = "latency_target";

        ensureTable(sourceTopic);
        ensureTable(targetTopic);

        // Produce correlated messages
        messageProducerService.produce(sourceTopic, "ord-1", "{\"id\":\"ord-1\", \"ts\": \"2026-03-24T12:00:00Z\"}");
        messageProducerService.produce(targetTopic, "ord-1", "{\"id\":\"ord-1\", \"ts\": \"2026-03-24T12:00:05Z\"}"); // +5s

        messageProducerService.produce(sourceTopic, "ord-2", "{\"id\":\"ord-2\", \"ts\": \"2026-03-24T12:00:10Z\"}");
        messageProducerService.produce(targetTopic, "ord-2", "{\"id\":\"ord-2\", \"ts\": \"2026-03-24T12:00:20Z\"}"); // +10s

        MetricConfig config = MetricConfig.builder()
            .name("test_latency")
            .type("GAUGE")
            .templateType("TOPIC_TRANSIT_LATENCY")
            .templateParams(Map.of(
                "sourceSql", "SELECT id AS match_key, ts AS event_time FROM " + sourceTopic,
                "targetSql", "SELECT id AS match_key, ts AS event_time FROM " + targetTopic
            ))
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getById(config.id()).orElseThrow();

        // (5000 + 10000) / 2 = 7500ms
        assertEquals(7500.0, saved.lastValue());
        assertEquals(2, saved.lastSummary().get("matchedCount"));
    }

    @Test
    void testMetricLabelsExtraction() throws ExecutionException, InterruptedException {
        String topic = "topic_with_labels";
        ensureTable(topic);

        // Produce message with some business fields
        messageProducerService.produce(topic, "m1", "{\"id\":\"m1\", \"region\":\"EU\", \"status\":\"ACTIVE\", \"amount\":100}");

        MetricConfig config = MetricConfig.builder()
            .name("labeled_metric")
            .type("GAUGE")
            .sql("SELECT amount AS metric_value FROM " + topic)
            .labelTopic(topic)
            .labelFields(List.of("region", "status"))
            .build();
        metricService.save(config);

        metricService.refreshMetrics();

        MetricConfig saved = metricService.getById(config.id()).orElseThrow();
        assertEquals(100.0, saved.lastValue());

        // Verify Micrometer labels (tags)
        Gauge gauge = meterRegistry.find("explorer_metric_gauge")
            .tag("metric_id", saved.id())
            .tag("region", "EU")
            .tag("status", "ACTIVE")
            .gauge();

        assertNotNull(gauge);
        assertEquals(100.0, gauge.value());
    }
}
