package com.yourcompany.kafkasqlexplorer.parser;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class XmlSchemaInferrerTest {

    @Test
    void testInferSimpleXml() {
        XmlSchemaInferrer inferrer = new XmlSchemaInferrer();
        String xml = "<order><id>123</id><status>NEW</status></order>";
        Map<String, String> schema = inferrer.infer(xml);

        assertEquals("STRING", schema.get("id"));
        assertEquals("STRING", schema.get("status"));
    }

    @Test
    void testInferWithNestedElements() {
        XmlSchemaInferrer inferrer = new XmlSchemaInferrer();
        String xml = "<order><customer><name>Alice</name></customer><amount>100</amount></order>";
        Map<String, String> schema = inferrer.infer(xml);

        assertTrue(schema.containsKey("customer"));
        assertTrue(schema.containsKey("amount"));
    }

    @Test
    void testInvalidXml() {
        XmlSchemaInferrer inferrer = new XmlSchemaInferrer();
        String xml = "<order><id>123</status></order>";
        Map<String, String> schema = inferrer.infer(xml);
        assertTrue(schema.isEmpty());
    }
}
