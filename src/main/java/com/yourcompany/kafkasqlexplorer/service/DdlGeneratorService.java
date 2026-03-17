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

    /**
     * Converts a Kafka topic name to a valid Flink SQL table name.
     * Dots and hyphens are invalid unquoted identifiers in Flink SQL, so they are replaced with underscores.
     * The original topic name is preserved in the 'topic' connector property.
     */
    public static String toTableName(String topicName) {
        return topicName.replace('.', '_').replace('-', '_');
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format, String startupMode) {
        // Validation of startupMode to prevent SQL injection
        if (startupMode == null || (!startupMode.equals("earliest-offset") && !startupMode.equals("latest-offset"))) {
            startupMode = "earliest-offset";
        }

        String tableName = toTableName(topicName);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");

        if (format == MessageFormat.XML) {
            // No Flink native XML format: read message as raw string, parse via XmlExtract UDF
            sb.append("    raw_value STRING,\n");
        } else {
            // JSON and AUTO (unknown/empty topic): use JSON format with ignore-parse-errors.
            // AUTO defaults to JSON because it is the dominant format in Kafka ecosystems;
            // json.ignore-parse-errors ensures non-JSON topics return null fields rather than failing.
            schema.forEach((col, type) -> sb.append("    ").append(col).append(" ").append(type).append(",\n"));
            sb.append("    raw_value STRING METADATA FROM 'value',\n");
        }

        // Add special columns as per user example
        sb.append("    proc_time AS PROCTIME(),\n");
        sb.append("    event_time TIMESTAMP(3) METADATA FROM 'timestamp',\n");
        sb.append("    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n");
        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");

        // Try to identify a key field (only meaningful for JSON/AUTO where schema columns exist)
        String keyField = namingConventionService.findKeyField(schema);
        if (keyField != null && format != MessageFormat.XML) {
            sb.append("    'key.fields' = '").append(keyField).append("',\n");
            sb.append("    'key.format' = 'raw',\n");
        }

        sb.append("    'properties.group.id' = 'flink_table_").append(tableName).append("',\n");
        sb.append("    'connector' = 'kafka',\n");

        // Add Kafka connection properties
        kafkaConfig.getKafkaProperties().forEach((key, value) -> {
            // Flink Kafka connector uses 'properties.' prefix for Kafka client configs
            // bootstrap.servers is handled specially as 'properties.bootstrap.servers' usually,
            // but also 'scan.startup.mode' etc are connector specific.
            // For general kafka properties, the prefix is 'properties.'
            sb.append("    'properties.").append(key).append("' = '").append(value).append("',\n");
        });

        if (format == MessageFormat.XML) {
            sb.append("    'value.format' = 'raw',\n");
        } else {
            // JSON and AUTO both use json format; ignore-parse-errors handles non-JSON content gracefully
            sb.append("    'value.format' = 'json',\n");
            sb.append("    'json.ignore-parse-errors' = 'true',\n");
        }

        sb.append("    'scan.startup.mode' = '").append(startupMode).append("'\n");
        sb.append(");");

        return sb.toString();
    }
}
