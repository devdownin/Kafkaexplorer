// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.parser.AvroSchemaInferrer;
import com.compagnonsdudev.kafkasqlexplorer.parser.JsonSchemaInferrer;
import com.compagnonsdudev.kafkasqlexplorer.parser.XmlSchemaInferrer;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Service
public class SchemaInferenceService {

    private static final Logger log = LoggerFactory.getLogger(SchemaInferenceService.class);
    private final ExplorerConfig explorerConfig;
    private final JsonSchemaInferrer jsonInferrer;
    private final XmlSchemaInferrer xmlInferrer;
    private final AvroSchemaInferrer avroInferrer;
    private final KafkaAdminService kafkaAdminService;

    public SchemaInferenceService(ExplorerConfig explorerConfig, JsonSchemaInferrer jsonInferrer, XmlSchemaInferrer xmlInferrer, AvroSchemaInferrer avroInferrer, KafkaAdminService kafkaAdminService) {
        this.explorerConfig = explorerConfig;
        this.jsonInferrer = jsonInferrer;
        this.xmlInferrer = xmlInferrer;
        this.avroInferrer = avroInferrer;
        this.kafkaAdminService = kafkaAdminService;
    }

    public Map<String, String> inferSchema(String topicName, MessageFormat format) {
        return inferSchema(topicName, format, null);
    }

    /**
     * Same as {@link #inferSchema(String, MessageFormat)} but reuses an already-fetched sample.
     * Callers that need several passes over the same topic (the cluster audit runs format
     * detection, schema inference and poison detection back to back) would otherwise open one
     * KafkaConsumer per pass to read the exact same ten messages.
     *
     * @param samples pre-fetched message values, or {@code null} to sample the topic
     */
    public Map<String, String> inferSchema(String topicName, MessageFormat format, List<String> samples) {
        if (format == MessageFormat.AVRO) {
            return avroInferrer.infer(topicName);
        }

        if (samples == null) samples = getSampleMessages(topicName);
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
        return detectFormat(topicName, null);
    }

    /**
     * Same as {@link #detectFormat(String)} but reuses an already-fetched sample.
     *
     * @param samples pre-fetched message values, or {@code null} to sample the topic
     */
    public MessageFormat detectFormat(String topicName, List<String> samples) {
        // Special check for Avro: if it has a schema in the registry, it's probably Avro
        Map<String, String> avroSchema = avroInferrer.infer(topicName);
        if (!avroSchema.isEmpty()) return MessageFormat.AVRO;

        if (samples == null) samples = getSampleMessages(topicName);
        if (samples.isEmpty()) return MessageFormat.AUTO;

        for (String sample : samples) {
            String trimmed = sample.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return MessageFormat.JSON;
            if (trimmed.startsWith("<")) return MessageFormat.XML;
        }
        return MessageFormat.AUTO;
    }

    /**
     * Reads one sample of the topic, sized by {@code explorer.inference-sample-size}. Public so a
     * caller running several inference passes can fetch once and feed the sample-taking overloads.
     */
    public List<String> getSampleMessages(String topicName) {
        // Delegate to KafkaAdminService which reads from ALL partitions, not just partition 0.
        // This prevents empty schema inference when messages are distributed across partitions.
        return kafkaAdminService.getSampleMessages(topicName, explorerConfig.getInferenceSampleSize());
    }
}
