package com.yourcompany.kafkasqlexplorer.parser;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonSchemaInferrerTest {

    @Test
    public void testInferJson() {
        JsonSchemaInferrer inferrer = new JsonSchemaInferrer();
        String json = "{\"id\": 1, \"name\": \"test\", \"price\": 10.5, \"active\": true}";

        Map<String, String> schema = inferrer.infer(json);

        assertEquals("BIGINT", schema.get("id"));
        assertEquals("STRING", schema.get("name"));
        assertEquals("DOUBLE", schema.get("price"));
        assertEquals("BOOLEAN", schema.get("active"));
    }
}
