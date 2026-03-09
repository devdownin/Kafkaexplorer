package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.functions.ScalarFunction;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class XmlExtractUDF extends ScalarFunction {

    private final DocumentBuilderFactory factory;
    private final XPathFactory xPathFactory;

    public XmlExtractUDF() {
        this.factory = DocumentBuilderFactory.newInstance();
        try {
            this.factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            this.factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception e) {
            // Log warning
        }
        this.factory.setXIncludeAware(false);
        this.factory.setExpandEntityReferences(false);
        this.xPathFactory = XPathFactory.newInstance();
    }

    public String eval(String xml, String xpathExpression) {
        if (xml == null || xpathExpression == null) {
            return null;
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPath xPath = xPathFactory.newXPath();
            NodeList nodeList = (NodeList) xPath.compile(xpathExpression).evaluate(doc, XPathConstants.NODESET);

            if (nodeList.getLength() > 0) {
                return nodeList.item(0).getTextContent();
            }
        } catch (Exception e) {
            // Log error or return error indication
            return "Error: " + e.getMessage();
        }
        return null;
    }
}
