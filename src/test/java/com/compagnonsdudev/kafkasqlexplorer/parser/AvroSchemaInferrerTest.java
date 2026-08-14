// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.parser;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class AvroSchemaInferrerTest {

    @Mock
    private SchemaRegistryClient schemaRegistryClient;

    @Mock
    private KafkaConfig kafkaConfig;

    private AvroSchemaInferrer inferrer;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(kafkaConfig.getSchemaRegistryUrl()).thenReturn("http://localhost:8081");
        inferrer = new AvroSchemaInferrer(kafkaConfig);
        ReflectionTestUtils.setField(inferrer, "schemaRegistryClient", schemaRegistryClient);
    }

    @Test
    public void testInferAvro() throws Exception {
        String avroSchemaJson = "{" +
                "\"type\": \"record\"," +
                "\"name\": \"User\"," +
                "\"fields\": [" +
                "  {\"name\": \"id\", \"type\": \"int\"}," +
                "  {\"name\": \"name\", \"type\": \"string\"}," +
                "  {\"name\": \"active\", \"type\": \"boolean\"}" +
                "]" +
                "}";

        SchemaMetadata metadata = new SchemaMetadata(1, 1, avroSchemaJson);
        when(schemaRegistryClient.getLatestSchemaMetadata("test-topic-value")).thenReturn(metadata);

        Map<String, String> schema = inferrer.infer("test-topic");

        assertEquals("INT", schema.get("id"));
        assertEquals("STRING", schema.get("name"));
        assertEquals("BOOLEAN", schema.get("active"));
    }

    @Test
    public void testInferAvroWithUnions() throws Exception {
        String avroSchemaJson = "{" +
                "\"type\": \"record\"," +
                "\"name\": \"Product\"," +
                "\"fields\": [" +
                "  {\"name\": \"price\", \"type\": [\"null\", \"double\"], \"default\": null}" +
                "]" +
                "}";

        SchemaMetadata metadata = new SchemaMetadata(1, 1, avroSchemaJson);
        when(schemaRegistryClient.getLatestSchemaMetadata("products-value")).thenReturn(metadata);

        Map<String, String> schema = inferrer.infer("products");

        assertEquals("DOUBLE", schema.get("price"));
    }
}
