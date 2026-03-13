package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.parser.JsonSchemaInferrer;
import com.yourcompany.kafkasqlexplorer.parser.XmlSchemaInferrer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class SchemaInferenceService {

    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final JsonSchemaInferrer jsonInferrer;
    private final XmlSchemaInferrer xmlInferrer;

    public SchemaInferenceService(KafkaConfig kafkaConfig, ExplorerConfig explorerConfig, JsonSchemaInferrer jsonInferrer, XmlSchemaInferrer xmlInferrer) {
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
        this.jsonInferrer = jsonInferrer;
        this.xmlInferrer = xmlInferrer;
    }

    public Map<String, String> inferSchema(String topicName, MessageFormat format) {
        List<String> samples = getSampleMessages(topicName);
        if (samples.isEmpty()) return Collections.emptyMap();

        Map<String, String> mergedSchema = new LinkedHashMap<>();

        for (String sample : samples) {
            Map<String, String> sampleSchema = Collections.emptyMap();
            if (format == MessageFormat.AUTO) {
                if (sample.trim().startsWith("{") || sample.trim().startsWith("[")) {
                    sampleSchema = jsonInferrer.infer(sample);
                } else if (sample.trim().startsWith("<")) {
                    sampleSchema = xmlInferrer.infer(sample);
                }
            } else if (format == MessageFormat.JSON) {
                sampleSchema = jsonInferrer.infer(sample);
            } else if (format == MessageFormat.XML) {
                sampleSchema = xmlInferrer.infer(sample);
            }

            mergeSchemas(mergedSchema, sampleSchema);
        }

        return mergedSchema;
    }

    private void mergeSchemas(Map<String, String> target, Map<String, String> source) {
        source.forEach((key, type) -> {
            if (!target.containsKey(key)) {
                target.put(key, type);
            } else {
                String existingType = target.get(key);
                target.put(key, resolveType(existingType, type));
            }
        });
    }

    private String resolveType(String t1, String t2) {
        if (t1.equals(t2)) return t1;
        if (t1.equals("STRING") || t2.equals("STRING")) return "STRING";
        if (t1.equals("DOUBLE") || t2.equals("DOUBLE")) return "DOUBLE";
        if (t1.equals("BIGINT") || t2.equals("BIGINT")) return "BIGINT";
        return "STRING";
    }

    public MessageFormat detectFormat(String topicName) {
        List<String> samples = getSampleMessages(topicName);
        if (samples.isEmpty()) return MessageFormat.AUTO;

        for (String sample : samples) {
            String trimmed = sample.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return MessageFormat.JSON;
            if (trimmed.startsWith("<")) return MessageFormat.XML;
        }
        return MessageFormat.AUTO;
    }

    private List<String> getSampleMessages(String topicName) {
        List<String> samples = new ArrayList<>();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "schema-inference-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition tp = new TopicPartition(topicName, 0);
            consumer.assign(Collections.singletonList(tp));
            consumer.seekToBeginning(Collections.singletonList(tp));

            int targetSize = explorerConfig.getInferenceSampleSize();
            long timeoutMs = explorerConfig.getInferencePollTimeoutMs();

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(timeoutMs));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null) {
                    samples.add(record.value());
                }
                if (samples.size() >= targetSize) break;
            }
        } catch (Exception e) {
            // Handle error
        }
        return samples;
    }
}
