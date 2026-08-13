package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DdlGeneratorServiceTest {

    @Test
    public void testGenerateJsonDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("localhost:9092");
        NamingConventionService namingConventionService = new NamingConventionService();
        DdlGeneratorService service = new DdlGeneratorService(config, namingConventionService);

        Map<String, String> schema = new HashMap<>();
        schema.put("id", "BIGINT");
        schema.put("name", "STRING");

        String ddl = service.generateDdl("test_topic", schema, MessageFormat.JSON);

        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS test_topic"));
        assertTrue(ddl.contains("`id` BIGINT"));
        assertTrue(ddl.contains("`event_time` TIMESTAMP(3) METADATA FROM 'timestamp'"));
        assertTrue(ddl.contains("'value.format' = 'json'"));
        assertTrue(ddl.contains("localhost:9092"));
        assertTrue(ddl.contains("`proc_time` AS PROCTIME()"));
        assertTrue(ddl.contains("'properties.group.id' = 'flink_table_test_topic'"));
        // Explicite, jamais hérité : un lecteur de cette application ne commite pas, et un offset
        // commité sous un groupe sans membre est ce que l'audit note CRITIQUE.
        assertTrue(ddl.contains("'properties.enable.auto.commit' = 'false'"));
    }

    @Test
    public void testGenerateXmlDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("localhost:9092");
        NamingConventionService namingConventionService = new NamingConventionService();
        DdlGeneratorService service = new DdlGeneratorService(config, namingConventionService);

        String ddl = service.generateDdl("xml_topic", Map.of(), MessageFormat.XML);

        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS xml_topic"));
        assertTrue(ddl.contains("'value.format' = 'raw'"));
        assertTrue(ddl.contains("`raw_value` STRING"));
        assertTrue(ddl.contains("`event_time` TIMESTAMP(3) METADATA FROM 'timestamp'"));
        assertTrue(ddl.contains("`proc_time` AS PROCTIME()"));
    }

    @Test
    public void testGenerateSslDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("kafka-ssl:9093");
        config.setMode("SSL");
        config.setTruststorePath("/tmp/truststore.jks");
        config.setTruststorePassword("password");

        NamingConventionService namingConventionService = new NamingConventionService();
        DdlGeneratorService service = new DdlGeneratorService(config, namingConventionService);
        Map<String, String> schema = new HashMap<>();
        schema.put("id", "BIGINT");

        String ddl = service.generateDdl("ssl_topic", schema, MessageFormat.JSON);

        assertTrue(ddl.contains("'properties.security.protocol' = 'SSL'"));
        assertTrue(ddl.contains("'properties.ssl.truststore.location' = '/tmp/truststore.jks'"));
        assertTrue(ddl.contains("'properties.ssl.truststore.password' = 'password'"));
        assertTrue(ddl.contains("'properties.bootstrap.servers' = 'kafka-ssl:9093'"));
    }

    @Test
    public void testGenerateConfluentCloudDdl() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("pkc-xyz.us-east-1.aws.confluent.cloud:9092");
        config.setMode("CONFLUENT_CLOUD");
        config.setConfluentKey("MY_KEY");
        config.setConfluentSecret("MY_SECRET");

        NamingConventionService namingConventionService = new NamingConventionService();
        DdlGeneratorService service = new DdlGeneratorService(config, namingConventionService);
        Map<String, String> schema = new HashMap<>();
        schema.put("id", "BIGINT");

        String ddl = service.generateDdl("cc_topic", schema, MessageFormat.JSON);

        assertTrue(ddl.contains("'properties.security.protocol' = 'SASL_SSL'"));
        assertTrue(ddl.contains("'properties.sasl.mechanism' = 'PLAIN'"));
        assertTrue(ddl.contains("'properties.sasl.jaas.config' = 'org.apache.kafka.common.security.plain.PlainLoginModule required username=\"MY_KEY\" password=\"MY_SECRET\";'"));
        assertTrue(ddl.contains("'properties.bootstrap.servers' = 'pkc-xyz.us-east-1.aws.confluent.cloud:9092'"));
    }

    @Test
    public void testMaskSensitivePropertiesRedactsCredentials() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("pkc-xyz.us-east-1.aws.confluent.cloud:9092");
        config.setMode("CONFLUENT_CLOUD");
        config.setConfluentKey("MY_KEY");
        config.setConfluentSecret("MY_SECRET");

        DdlGeneratorService service = new DdlGeneratorService(config, new NamingConventionService());
        String masked = DdlGeneratorService.maskSensitiveProperties(
            service.generateDdl("cc_topic", Map.of("id", "BIGINT"), MessageFormat.JSON));

        assertTrue(!masked.contains("MY_SECRET"));
        assertTrue(!masked.contains("MY_KEY"));
        assertTrue(masked.contains("'properties.sasl.jaas.config' = '******'"));
        // Non-sensitive properties must survive masking
        assertTrue(masked.contains("'properties.bootstrap.servers' = 'pkc-xyz.us-east-1.aws.confluent.cloud:9092'"));
        assertTrue(masked.contains("'properties.security.protocol' = 'SASL_SSL'"));
    }

    @Test
    public void testMaskSensitivePropertiesRedactsSslPasswords() {
        KafkaConfig config = new KafkaConfig();
        config.setBootstrapServers("kafka-ssl:9093");
        config.setMode("SSL");
        config.setTruststorePath("/tmp/truststore.jks");
        config.setTruststorePassword("trust-pass");
        config.setKeystorePassword("key-pass");

        DdlGeneratorService service = new DdlGeneratorService(config, new NamingConventionService());
        String masked = DdlGeneratorService.maskSensitiveProperties(
            service.generateDdl("ssl_topic", Map.of("id", "BIGINT"), MessageFormat.JSON));

        assertTrue(!masked.contains("trust-pass"));
        assertTrue(!masked.contains("key-pass"));
        assertTrue(masked.contains("'properties.ssl.truststore.password' = '******'"));
        // The truststore location is not a credential and must stay visible
        assertTrue(masked.contains("'properties.ssl.truststore.location' = '/tmp/truststore.jks'"));
    }

    @Test
    public void testRestoreMaskedPropertiesRecoversSecretFromStored() {
        String stored = "CREATE TABLE t (id BIGINT) WITH (\n" +
            "  'properties.bootstrap.servers' = 'broker:9092',\n" +
            "  'properties.ssl.truststore.password' = 'trust-pass'\n);";
        // The UI edits a non-secret line but sends the password back masked.
        String edited = "CREATE TABLE t (id BIGINT, name STRING) WITH (\n" +
            "  'properties.bootstrap.servers' = 'broker:9092',\n" +
            "  'properties.ssl.truststore.password' = '******'\n);";

        String restored = DdlGeneratorService.restoreMaskedProperties(edited, stored);

        assertTrue(restored.contains("'properties.ssl.truststore.password' = 'trust-pass'"),
            "the real secret must be restored from the stored DDL");
        assertFalse(restored.contains("******"), "no mask token should remain");
        assertTrue(restored.contains("name STRING"), "non-secret edits must be preserved");
    }

    @Test
    public void testRestoreMaskedPropertiesLeavesRetypedSecretUntouched() {
        String stored = "WITH ('properties.ssl.truststore.password' = 'old-pass')";
        // User genuinely retyped a new secret — it must be kept, not overwritten by the stored one.
        String edited = "WITH ('properties.ssl.truststore.password' = 'new-pass')";

        assertEquals(edited, DdlGeneratorService.restoreMaskedProperties(edited, stored));
    }

    @Test
    public void testRestoreMaskedPropertiesNoOpWithoutMask() {
        String edited = "WITH ('properties.bootstrap.servers' = 'broker:9092')";
        assertEquals(edited, DdlGeneratorService.restoreMaskedProperties(edited, "anything"));
        assertEquals(edited, DdlGeneratorService.restoreMaskedProperties(edited, null));
    }
}
