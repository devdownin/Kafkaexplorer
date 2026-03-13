package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.functions.ScalarFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
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
    private transient XPathFactory xPathFactory;
    private transient Map<String, XPathExpression> expressionCache;

    /**
     * Initializes the XML parser with strict security settings to prevent
     * XML External Entity (XXE) injection attacks.
     */
    private void init() {
        if (this.factory == null) {
            this.factory = DocumentBuilderFactory.newInstance();
            try {
                // Security: Disable DTDs and external entities
                this.factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                this.factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception e) {
                log.warn("Could not configure XML factory with secure features", e);
            }
            this.factory.setXIncludeAware(false);
            this.factory.setExpandEntityReferences(false);
        }
        if (this.xPathFactory == null) {
            this.xPathFactory = XPathFactory.newInstance();
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
                    return xPathFactory.newXPath().compile(k);
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
