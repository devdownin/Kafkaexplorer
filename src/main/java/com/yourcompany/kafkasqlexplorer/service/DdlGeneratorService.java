package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DdlGeneratorService {

    private final KafkaConfig kafkaConfig;

    public DdlGeneratorService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(topicName).append(" (\n");

        if (format == MessageFormat.XML) {
            sb.append("    raw_value STRING,\n");
        } else {
            schema.forEach((col, type) -> sb.append("    ").append(col).append(" ").append(type).append(",\n"));
            // Always add raw_value for JSON to support JSON_VALUE queries via Assistant
            sb.append("    raw_value STRING METADATA FROM 'value',\n");
        }

        // Add special columns as per user example
        sb.append("    proc_time AS PROCTIME()\n");
        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");

        // Try to identify a key field (heuristic: "id" or first column)
        String keyField = schema.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("id"))
                .findFirst()
                .orElse(schema.keySet().stream().findFirst().orElse(null));
        if (keyField != null && format != MessageFormat.XML) {
            sb.append("    'key.fields' = '").append(keyField).append("',\n");
        }

        sb.append("    'properties.group.id' = 'flink_table_").append(topicName).append("',\n");
        sb.append("    'connector' = 'kafka',\n");

        // Add Kafka connection properties
        kafkaConfig.getKafkaProperties().forEach((key, value) -> {
            // Flink Kafka connector uses 'properties.' prefix for Kafka client configs
            // bootstrap.servers is handled specially as 'properties.bootstrap.servers' usually,
            // but also 'scan.startup.mode' etc are connector specific.
            // For general kafka properties, the prefix is 'properties.'
            sb.append("    'properties.").append(key).append("' = '").append(value).append("',\n");
        });

        if (format == MessageFormat.JSON) {
            sb.append("    'value.format' = 'json',\n");
            sb.append("    'json.ignore-parse-errors' = 'true',\n");
        } else {
            sb.append("    'value.format' = 'raw',\n");
        }

        sb.append("    'properties.auto.offset.reset' = 'earliest'\n");
        sb.append(");");

        return sb.toString();
    }
}
