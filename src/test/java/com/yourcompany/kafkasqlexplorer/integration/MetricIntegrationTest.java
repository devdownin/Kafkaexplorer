package com.yourcompany.kafkasqlexplorer.integration;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.MessageProducerService;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class MetricIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", kafka::getBootstrapServers);
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

    @BeforeEach
    void setUp() {
        // Clean up or ensure tables exist
        try {
            flinkSqlService.executeSql(QueryRequest.ddl(
                "CREATE TABLE IF NOT EXISTS test_topic (" +
                "  id STRING," +
                "  amount DOUBLE," +
                "  ts TIMESTAMP(3)," +
                "  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND" +
                ") WITH (" +
                "  'connector' = 'kafka'," +
                "  'topic' = 'test_topic'," +
                "  'properties.bootstrap.servers' = '" + kafka.getBootstrapServers() + "'," +
                "  'properties.group.id' = 'test-group'," +
                "  'scan.startup.mode' = 'earliest-offset'," +
                "  'format' = 'json'" +
                ")", 10000L));
        } catch (Exception e) {
            // Might already exist
        }
    }

    @Test
    void testGaugeMetricAccuracy() throws ExecutionException, InterruptedException {
        messageProducerService.produce("test_topic", "k1", "{\"id\":\"1\", \"amount\": 10.5, \"ts\": \"2026-03-24T10:00:00Z\"}");
        messageProducerService.produce("test_topic", "k2", "{\"id\":\"2\", \"amount\": 20.0, \"ts\": \"2026-03-24T10:00:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_gauge")
            .type("GAUGE")
            .sql("SELECT SUM(amount) AS metric_value FROM test_topic")
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
        messageProducerService.produce("test_topic", "k3", "{\"id\":\"3\", \"amount\": 1.0, \"ts\": \"2026-03-24T10:00:02Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_counter")
            .type("COUNTER")
            .sql("SELECT COUNT(*) AS metric_value FROM test_topic")
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

        messageProducerService.produce("test_topic", "k4", "{\"id\":\"4\", \"amount\": 1.0, \"ts\": \"2026-03-24T10:00:03Z\"}");
        metricService.refreshMetrics();

        assertEquals(firstValue + 1, metricService.getById(saved.id()).get().lastValue());
    }

    @Test
    void testHistogramMetricAccuracy() throws ExecutionException, InterruptedException {
        messageProducerService.produce("test_topic", "h1", "{\"id\":\"h1\", \"amount\": 10.0, \"ts\": \"2026-03-24T10:01:00Z\"}");
        messageProducerService.produce("test_topic", "h2", "{\"id\":\"h2\", \"amount\": 50.0, \"ts\": \"2026-03-24T10:01:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_histogram")
            .type("HISTOGRAM")
            .sql("SELECT amount AS metric_value FROM test_topic WHERE id LIKE 'h%'")
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
        messageProducerService.produce("test_topic", "s1", "{\"id\":\"s1\", \"amount\": 100.0, \"ts\": \"2026-03-24T10:02:00Z\"}");
        messageProducerService.produce("test_topic", "s2", "{\"id\":\"s2\", \"amount\": 200.0, \"ts\": \"2026-03-24T10:02:01Z\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_summary")
            .type("SUMMARY")
            .sql("SELECT amount AS metric_value FROM test_topic WHERE id LIKE 's%'")
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
        flinkSqlService.executeSql(QueryRequest.ddl(
            "CREATE TABLE IF NOT EXISTS test_topic_2 (" +
            "  id STRING" +
            ") WITH (" +
            "  'connector' = 'kafka'," +
            "  'topic' = 'test_topic_2'," +
            "  'properties.bootstrap.servers' = '" + kafka.getBootstrapServers() + "'," +
            "  'scan.startup.mode' = 'earliest-offset'," +
            "  'format' = 'json'" +
            ")", 10000L));

        messageProducerService.produce("test_topic", "a", "{\"id\":\"a\"}");
        messageProducerService.produce("test_topic", "b", "{\"id\":\"b\"}");
        messageProducerService.produce("test_topic_2", "c", "{\"id\":\"c\"}");

        MetricConfig config = MetricConfig.builder()
            .name("test_delta")
            .type("GAUGE")
            .templateType("TOPIC_COUNT_DELTA")
            .templateParams(Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM test_topic",
                "rightSql", "SELECT COUNT(*) AS metric_value FROM test_topic_2",
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
}
