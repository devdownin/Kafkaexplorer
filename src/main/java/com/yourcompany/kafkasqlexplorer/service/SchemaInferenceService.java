package com.yourcompany.kafkasqlexplorer.service;

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
    private final JsonSchemaInferrer jsonInferrer;
    private final XmlSchemaInferrer xmlInferrer;

    public SchemaInferenceService(KafkaConfig kafkaConfig, JsonSchemaInferrer jsonInferrer, XmlSchemaInferrer xmlInferrer) {
        this.kafkaConfig = kafkaConfig;
        this.jsonInferrer = jsonInferrer;
        this.xmlInferrer = xmlInferrer;
    }

    public Map<String, String> inferSchema(String topicName, MessageFormat format) {
        String sample = getSampleMessage(topicName);
        if (sample == null) return Collections.emptyMap();

        if (format == MessageFormat.AUTO) {
            if (sample.trim().startsWith("{") || sample.trim().startsWith("[")) {
                return jsonInferrer.infer(sample);
            } else if (sample.trim().startsWith("<")) {
                return xmlInferrer.infer(sample);
            }
        } else if (format == MessageFormat.JSON) {
            return jsonInferrer.infer(sample);
        } else if (format == MessageFormat.XML) {
            return xmlInferrer.infer(sample);
        }

        return Collections.emptyMap();
    }

    public MessageFormat detectFormat(String topicName) {
        String sample = getSampleMessage(topicName);
        if (sample == null) return MessageFormat.AUTO;
        if (sample.trim().startsWith("{") || sample.trim().startsWith("[")) return MessageFormat.JSON;
        if (sample.trim().startsWith("<")) return MessageFormat.XML;
        return MessageFormat.AUTO;
    }

    private String getSampleMessage(String topicName) {
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

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null) return record.value();
            }
        } catch (Exception e) {
            // Handle error
        }
        return null;
    }
}
