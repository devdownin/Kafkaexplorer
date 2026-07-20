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
import java.util.regex.Pattern;

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
     * Matches DDL properties whose key carries a credential (SSL passwords, Confluent
     * secrets, SASL JAAS config with inline username/password).
     */
    private static final Pattern SENSITIVE_PROP_PATTERN = Pattern.compile(
        "(?i)('[^']*(?:password|secret|sasl\\.jaas\\.config)[^']*'\\s*=\\s*)'[^']*'");

    /**
     * Redacts credential values from a DDL string before it is returned to the UI
     * (topic detail, DDL preview, lineage SHOW CREATE TABLE). The full unmasked DDL is
     * still used internally for table registration — the Flink connector needs the real
     * credentials — but must never reach the browser.
     */
    public static String maskSensitiveProperties(String ddl) {
        if (ddl == null) return null;
        return SENSITIVE_PROP_PATTERN.matcher(ddl).replaceAll("$1'******'");
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

        if (format == MessageFormat.XML) {
            // For XML (value.format='raw'), raw_value MUST be a physical column.
            // Declaring it as METADATA FROM 'value' is incompatible with value.format='raw'
            // and causes "Unable to create a source" at SELECT time.
            sb.append("    `raw_value` STRING,\n");
        } else {
            // JSON, AUTO, AVRO
            List<String> cols = new ArrayList<>(schema.keySet());
            for (String colName : cols) {
                sb.append("    `").append(colName).append("` ").append(schema.get(colName)).append(",\n");
            }
        }

        // Mandatory technical columns for all topics
        sb.append("    `event_time` TIMESTAMP(3) METADATA FROM 'timestamp',\n");
        sb.append("    `proc_time` AS PROCTIME()\n");
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
