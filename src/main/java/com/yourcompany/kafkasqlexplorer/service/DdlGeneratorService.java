// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");

        List<String> columns = new ArrayList<>();
        if (format == MessageFormat.XML) {
            // No Flink native XML format: read message as raw string, parse via XmlExtract UDF.
            columns.add("    `raw_value` STRING");
        } else {
            // JSON, AVRO and AUTO:
            // Guard: an empty schema produces an invalid DDL (empty column list).
            // Fall back to a single raw_value STRING so the table can be registered.
            List<String> cols = new ArrayList<>(schema.keySet());
            if (cols.isEmpty()) {
                columns.add("    `raw_value` STRING");
            } else {
                for (String colName : cols) {
                    columns.add("    `" + colName + "` " + schema.get(colName));
                }
            }
        }

        // Add standard technical columns (Metadata and Computed)
        // Guard against duplicate column names if they are already in the schema
        if (!schema.containsKey("event_time")) {
            columns.add("    `event_time` TIMESTAMP(3) METADATA FROM 'timestamp'");
        }
        if (!schema.containsKey("proc_time")) {
            columns.add("    `proc_time` AS PROCTIME()");
        }

        // Write all columns
        for (int i = 0; i < columns.size(); i++) {
            sb.append(columns.get(i));
            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");
        sb.append("    'properties.group.id' = 'flink_table_").append(tableName).append("',\n");
        sb.append("    'connector' = 'kafka',\n");

        // Add Kafka connection properties
        kafkaConfig.getKafkaProperties().forEach((key, value) -> {
            sb.append("    'properties.").append(key).append("' = '").append(value).append("',\n");
        });

        if (format == MessageFormat.XML) {
            sb.append("    'value.format' = 'raw',\n");
        } else if (format == MessageFormat.AVRO) {
            sb.append("    'value.format' = 'avro-confluent',\n");
            sb.append("    'avro-confluent.url' = '").append(kafkaConfig.getSchemaRegistryUrl()).append("',\n");
        } else {
            sb.append("    'value.format' = 'json',\n");
            sb.append("    'json.ignore-parse-errors' = 'true',\n");
        }

        sb.append("    'scan.startup.mode' = '").append(startupMode).append("'\n");
        sb.append(");");

        return sb.toString();
    }
}
