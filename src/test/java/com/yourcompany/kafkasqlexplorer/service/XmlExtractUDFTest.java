package com.yourcompany.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmlExtractUDFTest {

    @Test
    void testEvalSimplePath() {
        XmlExtractUDF udf = new XmlExtractUDF();
        String xml = "<root><child>value</child></root>";
        assertEquals("value", udf.eval(xml, "/root/child"));
    }

    @Test
    void testEvalAttribute() {
        XmlExtractUDF udf = new XmlExtractUDF();
        String xml = "<root><child id=\"123\">value</child></root>";
        assertEquals("123", udf.eval(xml, "/root/child/@id"));
    }

    @Test
    void testEvalNestedPath() {
        XmlExtractUDF udf = new XmlExtractUDF();
        String xml = "<order><customer><name>Alice</name></customer></order>";
        assertEquals("Alice", udf.eval(xml, "/order/customer/name"));
    }

    @Test
    void testEvalNotFound() {
        XmlExtractUDF udf = new XmlExtractUDF();
        String xml = "<root><child>value</child></root>";
        assertNull(udf.eval(xml, "/root/other"));
    }

    @Test
    void testEvalNullInputs() {
        XmlExtractUDF udf = new XmlExtractUDF();
        assertNull(udf.eval(null, "/root"));
        assertNull(udf.eval("<root/>", null));
    }

    @Test
    void testXxeProtection() {
        XmlExtractUDF udf = new XmlExtractUDF();
        // Trying to use an external entity
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>" +
                "<foo>&xxe;</foo>";

        String result = udf.eval(xml, "/foo");
        // Due to disallow-doctype-decl being true, it should throw an error or at least NOT resolve the entity
        assertTrue(result.contains("Error") || result.isEmpty() || !result.contains("root:x:0:0"));
    }
}
