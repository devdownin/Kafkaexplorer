package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;

@Service
public class MessageFormatterService {

    private final ObjectMapper objectMapper;

    public MessageFormatterService() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String format(String message) {
        if (message == null || message.isBlank()) return message;
        String trimmed = message.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return formatJson(message);
        } else if (trimmed.startsWith("<")) {
            return formatXml(message);
        }
        return message;
    }

    private String formatJson(String json) {
        try {
            Object obj = objectMapper.readValue(json, Object.class);
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private String formatXml(String xml) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            Source xmlInput = new StreamSource(new StringReader(xml));
            StringWriter stringWriter = new StringWriter();
            transformer.transform(xmlInput, new StreamResult(stringWriter));
            return stringWriter.toString();
        } catch (Exception e) {
            return xml;
        }
    }
}
