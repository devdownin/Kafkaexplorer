package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DdlGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DdlGeneratorService.class);
    private final KafkaConfig kafkaConfig;
    private final NamingConventionService namingConventionService;

    public DdlGeneratorService(KafkaConfig kafkaConfig, NamingConventionService namingConventionService) {
        this.kafkaConfig = kafkaConfig;
        this.namingConventionService = namingConventionService;
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format) {
        return generateDdl(topicName, schema, format, "earliest-offset");
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format, String startupMode) {
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
        sb.append("    proc_time AS PROCTIME(),\n");
        sb.append("    event_time TIMESTAMP(3) METADATA FROM 'timestamp',\n");
        sb.append("    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n");
        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");

        // Try to identify a key field
        String keyField = namingConventionService.findKeyField(schema);
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

        sb.append("    'scan.startup.mode' = '").append(startupMode).append("'\n");
        sb.append(");");

        return sb.toString();
    }
}
