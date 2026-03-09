package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DdlGeneratorServiceTest {

    @Test
    public void testGenerateJsonDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("localhost:9092");
        DdlGeneratorService service = new DdlGeneratorService(config);

        Map<String, String> schema = new HashMap<>();
        schema.put("id", "BIGINT");
        schema.put("name", "STRING");

        String ddl = service.generateDdl("test_topic", schema, MessageFormat.JSON);

        assertTrue(ddl.contains("CREATE TABLE test_topic"));
        assertTrue(ddl.contains("id BIGINT"));
        assertTrue(ddl.contains("'value.format' = 'json'"));
        assertTrue(ddl.contains("localhost:9092"));
        assertTrue(ddl.contains("proc_time AS PROCTIME()"));
        assertTrue(ddl.contains("'properties.group.id' = 'flink_table_test_topic'"));
    }

    @Test
    public void testGenerateXmlDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("localhost:9092");
        DdlGeneratorService service = new DdlGeneratorService(config);

        Map<String, String> schema = new HashMap<>();
        schema.put("raw_payload", "STRING");

        String ddl = service.generateDdl("xml_topic", schema, MessageFormat.XML);

        assertTrue(ddl.contains("CREATE TABLE xml_topic"));
        assertTrue(ddl.contains("'value.format' = 'raw'"));
        assertTrue(ddl.contains("raw_value STRING"));
    }
}
