package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

public class DdlDebug2 {
    @Test
    public void debug() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("localhost:9092");
        NamingConventionService namingConventionService = new NamingConventionService();
        DdlGeneratorService service = new DdlGeneratorService(config, namingConventionService);

        Map<String, String> schema = new HashMap<>();
        schema.put("id", "BIGINT");
        schema.put("name", "STRING");

        String ddl = service.generateDdl("test_topic", schema, MessageFormat.JSON);
        System.out.println("DEBUG JSON DDL:\n" + ddl);

        schema = new HashMap<>();
        schema.put("raw_payload", "STRING");
        ddl = service.generateDdl("xml_topic", schema, MessageFormat.XML);
        System.out.println("DEBUG XML DDL:\n" + ddl);
    }
}
