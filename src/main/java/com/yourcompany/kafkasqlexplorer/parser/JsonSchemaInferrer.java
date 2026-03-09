package com.yourcompany.kafkasqlexplorer.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class JsonSchemaInferrer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, String> infer(String json) {
        Map<String, String> schema = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            root.fields().forEachRemaining(entry -> {
                schema.put(entry.getKey(), getFlinkType(entry.getValue()));
            });
        } catch (Exception e) {
            // Handle parsing error
        }
        return schema;
    }

    private String getFlinkType(JsonNode node) {
        if (node.isDouble() || node.isFloat()) return "DOUBLE";
        if (node.isInt() || node.isLong()) return "BIGINT";
        if (node.isBoolean()) return "BOOLEAN";
        return "STRING";
    }
}
