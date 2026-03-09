package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DdlGeneratorService {

    private final KafkaConfig kafkaConfig;

    public DdlGeneratorService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(topicName).append(" (\n");

        schema.forEach((col, type) -> sb.append("    ").append(col).append(" ").append(type).append(",\n"));

        sb.append("    event_time TIMESTAMP(3) METADATA FROM 'timestamp',\n");
        sb.append("    kafka_offset BIGINT METADATA FROM 'offset' VIRTUAL,\n");
        sb.append("    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n");
        sb.append(") WITH (\n");
        sb.append("    'connector' = 'kafka',\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");
        sb.append("    'properties.bootstrap.servers' = '").append(kafkaConfig.getBootstrapServers()).append("',\n");
        sb.append("    'scan.startup.mode' = 'earliest-offset',\n");

        if (format == MessageFormat.JSON) {
            sb.append("    'format' = 'json',\n");
            sb.append("    'json.ignore-parse-errors' = 'true'\n");
        } else {
            sb.append("    'format' = 'raw',\n");
            sb.append("    'value.format' = 'raw'\n");
        }

        sb.append(")");
        return sb.toString();
    }
}
