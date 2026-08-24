// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.parser;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class AvroSchemaInferrer {

    private static final Logger log = LoggerFactory.getLogger(AvroSchemaInferrer.class);
    private final SchemaRegistryClient schemaRegistryClient;

    public AvroSchemaInferrer(KafkaConfig kafkaConfig) {
        if (kafkaConfig.getSchemaRegistryUrl() != null) {
            this.schemaRegistryClient = new CachedSchemaRegistryClient(kafkaConfig.getSchemaRegistryUrl(), 100);
        } else {
            this.schemaRegistryClient = null;
        }
    }

    public Map<String, String> infer(String topicName) {
        if (schemaRegistryClient == null) {
            log.warn("Schema Registry URL not configured, cannot infer Avro schema for topic {}",
                LogSafe.name(topicName));
            return Collections.emptyMap();
        }

        try {
            // Confluent Schema Registry convention: <topic>-value
            SchemaMetadata metadata = schemaRegistryClient.getLatestSchemaMetadata(topicName + "-value");
            Schema schema = new Schema.Parser().parse(metadata.getSchema());
            
            Map<String, String> flinkSchema = new HashMap<>();
            if (schema.getType() == Schema.Type.RECORD) {
                for (Schema.Field field : schema.getFields()) {
                    flinkSchema.put(field.name(), getFlinkType(field.schema()));
                }
            }
            return flinkSchema;
        } catch (Exception e) {
            log.debug("Failed to fetch Avro schema from registry for topic {}: {}",
                LogSafe.name(topicName), LogSafe.text(e.getMessage()));
            return Collections.emptyMap();
        }
    }

    private String getFlinkType(Schema schema) {
        Schema.Type type = schema.getType();
        
        // Handle Union types (often used for nullability)
        if (type == Schema.Type.UNION) {
            for (Schema s : schema.getTypes()) {
                if (s.getType() != Schema.Type.NULL) {
                    return getFlinkType(s);
                }
            }
            return "STRING";
        }

        return switch (type) {
            case INT -> "INT";
            case LONG -> "BIGINT";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case BYTES, FIXED -> "BINARY";
            case STRING, ENUM -> "STRING";
            default -> "STRING";
        };
    }
}
