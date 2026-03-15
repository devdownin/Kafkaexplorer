package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MetricService {
    private static final Logger log = LoggerFactory.getLogger(MetricService.class);
    private final Map<String, MetricConfig> metrics = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicReference<Double>>> multiGaugeValues = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>> historyMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 50;

    private final FlinkSqlService flinkSqlService;
    private final MeterRegistry meterRegistry;
    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricService(FlinkSqlService flinkSqlService, MeterRegistry meterRegistry,
                         KafkaConfig kafkaConfig, ExplorerConfig explorerConfig) {
        this.flinkSqlService = flinkSqlService;
        this.meterRegistry = meterRegistry;
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
    }

    @PostConstruct
    public void init() {
        restoreFromKafka();
        if (metrics.isEmpty()) {
            addMetric("business_events_total", "COUNTER",
                "SELECT COUNT(*) as metric_value FROM demo_orders_in", "Total number of business events processed");
            addMetric("business_events_by_type", "GAUGE",
                "SELECT COUNT(*) as metric_value, 'order' as type FROM demo_orders_in GROUP BY 'order'", "Events grouped by type");
        }
    }

    private void addMetric(String name, String type, String sql, String description) {
        String id = UUID.randomUUID().toString();
        save(new MetricConfig(id, name, type, sql, description, null, null, null, null, null));
    }

    public List<MetricConfig> getAllMetrics() {
        return new ArrayList<>(metrics.values());
    }

    public Optional<MetricConfig> getById(String id) {
        return Optional.ofNullable(metrics.get(id));
    }

    public void save(MetricConfig metric) {
        String id = metric.id();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }

        MetricConfig newMetric = new MetricConfig(
            id, metric.name(), metric.type(), metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(),
            metric.lastValue(), metric.lastUpdateTime(), metric.errorMessage(),
            metric.history() != null ? metric.history() : List.of()
        );

        metrics.put(id, newMetric);
        persistToKafka(newMetric);
    }

    public void delete(String id) {
        metrics.remove(id);
        multiGaugeValues.remove(id);
        historyMap.remove(id);
        meterRegistry.find("explorer_business_metric").tag("metric_id", id).meters().forEach(meterRegistry::remove);
        persistToKafka(new MetricConfig(id, null, null, null, null, null, null, null, null, "DELETED"));
    }

    @Scheduled(fixedRateString = "${explorer.metrics-refresh-rate:30000}")
    public void refreshMetrics() {
        metrics.forEach((id, config) -> {
            try {
                QueryResult result = flinkSqlService.executeSql(new QueryRequest(config.sql(), "latest-offset", 50, 5000L, null));
                if (result.error() != null) {
                    updateMetricState(id, null, result.error());
                } else if (!result.rows().isEmpty()) {
                    processRows(id, config, result);
                } else {
                    updateMetricState(id, null, "No rows returned");
                }
            } catch (Exception e) {
                updateMetricState(id, null, e.getMessage());
            }
        });
    }

    private void processRows(String metricId, MetricConfig config, QueryResult result) {
        Map<String, AtomicReference<Double>> gaugeMap = multiGaugeValues.computeIfAbsent(metricId, k -> new ConcurrentHashMap<>());
        Set<String> seenKeys = new HashSet<>();
        Double primaryValue = null;

        for (Map<String, Object> row : result.rows()) {
            Double value = null;
            List<Tag> tags = new ArrayList<>();
            tags.add(Tag.of("metric_id", metricId));
            tags.add(Tag.of("metric_name", config.name()));
            tags.add(Tag.of("metric_type", config.type()));

            StringBuilder keyBuilder = new StringBuilder();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("metric_value".equalsIgnoreCase(entry.getKey())) {
                    if (entry.getValue() instanceof Number num) value = num.doubleValue();
                } else {
                    String val = String.valueOf(entry.getValue());
                    tags.add(Tag.of(entry.getKey().toLowerCase(), val));
                    keyBuilder.append(entry.getKey()).append("=").append(val).append("|");
                }
            }

            if (value != null) {
                String labelKey = keyBuilder.toString();
                seenKeys.add(labelKey);
                gaugeMap.computeIfAbsent(labelKey, k -> {
                    AtomicReference<Double> h = new AtomicReference<>(0.0);
                    Gauge.builder("explorer_business_metric", h, AtomicReference::get).tags(tags).register(meterRegistry);
                    return h;
                }).set(value);
                if (primaryValue == null) primaryValue = value;
            }
        }

        if (primaryValue != null) {
            updateHistory(metricId, primaryValue);
            updateMetricState(metricId, primaryValue, null);
        }
    }

    private void updateHistory(String id, Double value) {
        LinkedList<Double> history = historyMap.computeIfAbsent(id, k -> new LinkedList<>());
        history.addLast(value);
        if (history.size() > MAX_HISTORY) history.removeFirst();
    }

    private void updateMetricState(String id, Double value, String error) {
        MetricConfig current = metrics.get(id);
        if (current != null) {
            metrics.put(id, new MetricConfig(
                current.id(), current.name(), current.type(), current.sql(), current.description(),
                current.warningThreshold(), current.criticalThreshold(),
                value != null ? value : current.lastValue(), System.currentTimeMillis(), error,
                new ArrayList<>(historyMap.getOrDefault(id, new LinkedList<>()))
            ));
        }
    }

    private void persistToKafka(MetricConfig metric) {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            String value = objectMapper.writeValueAsString(metric);
            producer.send(new ProducerRecord<>(explorerConfig.getMetricsConfigTopic(), metric.id(), value)).get();
        } catch (Exception e) {
            log.warn("Failed to persist metric config: {}", e.getMessage());
        }
    }

    private void restoreFromKafka() {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "explorer-metrics-restorer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(explorerConfig.getMetricsConfigTopic()));
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 2000) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) break;
                for (ConsumerRecord<String, String> record : records) {
                    MetricConfig config = objectMapper.readValue(record.value(), MetricConfig.class);
                    if ("DELETED".equals(config.errorMessage())) metrics.remove(record.key());
                    else metrics.put(record.key(), config);
                }
            }
        } catch (Exception e) {
            log.debug("Restore failed: {}", e.getMessage());
        }
    }
}
