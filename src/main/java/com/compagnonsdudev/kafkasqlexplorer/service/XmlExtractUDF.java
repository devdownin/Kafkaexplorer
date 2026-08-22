// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.flink.table.functions.ScalarFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.compagnonsdudev.kafkasqlexplorer.parser.SecureXml;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Flink User Defined Function (UDF) to extract values from XML strings using XPath.
 * This is particularly useful for querying Kafka topics with XML payloads.
 *
 * Performance Note: We use the 'transient' keyword for heavy factories to avoid
 * serialization issues during Flink job distribution.
 */
public class XmlExtractUDF extends ScalarFunction {

    private static final Logger log = LoggerFactory.getLogger(XmlExtractUDF.class);
    private transient DocumentBuilderFactory factory;
    private transient Map<String, XPathExpression> expressionCache;

    /**
     * Initializes the XML parser with strict security settings to prevent
     * XML External Entity (XXE) injection attacks.
     */
    private void init() {
        if (this.factory == null) {
            this.factory = SecureXml.documentBuilderFactory();
        }
        if (this.expressionCache == null) {
            this.expressionCache = new ConcurrentHashMap<>();
        }
    }

    public String eval(String xml, String xpathExpression) {
        init();
        if (xml == null || xpathExpression == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPathExpression expr = expressionCache.computeIfAbsent(xpathExpression, k -> {
                try {
                    return SecureXml.xpath().compile(k);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            NodeList nodeList = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

            if (nodeList.getLength() > 0) {
                return nodeList.item(0).getTextContent();
            }
        } catch (Exception e) {
            log.debug("XPath evaluation failed for expression: {}", xpathExpression, e);
            return "Error: " + e.getMessage();
        }
        return null;
    }
}
