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
        sb.append("CREATE TABLE IF NOT EXISTS `").append(tableName.replace("`", "``")).append("` (\n");

        if (format == MessageFormat.XML) {
            // No Flink native XML format: read message as raw string, parse via XmlExtract UDF.
            sb.append("    raw_value STRING,\n");
        } else if (format == MessageFormat.AVRO || format == MessageFormat.JSON || format == MessageFormat.AUTO) {
            List<String> cols = new ArrayList<>(schema.keySet());
            for (int i = 0; i < cols.size(); i++) {
                String colName = cols.get(i);
                String colType = schema.get(colName);
                // Sanitize column name and type (type should be a valid Flink SQL type)
                sb.append("    `").append(colName.replace("`", "``")).append("` ").append(colType).append(",\n");
            }
        }

        sb.append("    event_time TIMESTAMP(3) METADATA FROM 'timestamp' VIRTUAL,\n");
        sb.append("    proc_time AS PROCTIME()\n");
        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName.replace("'", "''")).append("',\n");
        sb.append("    'properties.group.id' = 'flink_table_").append(tableName.replace("'", "''")).append("',\n");
        sb.append("    'connector' = 'kafka',\n");

        // Add Kafka connection properties
        kafkaConfig.getKafkaProperties().forEach((key, value) -> {
            sb.append("    'properties.").append(key.replace("'", "''")).append("' = '").append(value.replace("'", "''")).append("',\n");
        });

        if (format == MessageFormat.XML) {
            sb.append("    'format' = 'raw',\n");
        } else if (format == MessageFormat.AVRO) {
            sb.append("    'format' = 'avro-confluent',\n");
            sb.append("    'avro-confluent.url' = '").append(kafkaConfig.getSchemaRegistryUrl()).append("',\n");
        } else {
            sb.append("    'format' = 'json',\n");
            sb.append("    'json.ignore-parse-errors' = 'true',\n");
        }

        sb.append("    'scan.startup.mode' = '").append(startupMode).append("'\n");
        sb.append(");");

        return sb.toString();
    }
}
