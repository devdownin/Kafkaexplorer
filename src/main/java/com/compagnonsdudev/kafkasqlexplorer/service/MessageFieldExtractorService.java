// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.parser.SecureXml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MessageFieldExtractorService {

    private final ObjectMapper objectMapper;

    public MessageFieldExtractorService() {
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, String> extractLeafFields(String message) {
        if (message == null || message.isBlank()) {
            return Map.of();
        }

        String trimmed = message.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return extractJsonFields(trimmed);
        }
        if (trimmed.startsWith("<")) {
            return extractXmlFields(trimmed);
        }
        return Map.of();
    }

    public String sanitizeLabelKey(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "label";
        }
        String sanitized = fieldPath.trim()
            .replaceAll("\\[(\\d+)]", "_$1")
            .replaceAll("[^a-zA-Z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "")
            .toLowerCase(Locale.ROOT);
        if (sanitized.isBlank()) {
            return "label";
        }
        char first = sanitized.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            sanitized = "_" + sanitized;
        }
        return sanitized;
    }

    private Map<String, String> extractJsonFields(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, String> fields = new LinkedHashMap<>();
            collectJsonFields(root, "", fields);
            return fields;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void collectJsonFields(JsonNode node, String path, Map<String, String> fields) {
        if (node == null || node.isNull()) {
            if (!path.isBlank()) {
                fields.put(path, "null");
            }
            return;
        }
        if (node.isValueNode()) {
            if (!path.isBlank()) {
                fields.put(path, node.asText());
            }
            return;
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                if (!path.isBlank()) {
                    fields.put(path, "[]");
                }
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                String childPath = path.isBlank() ? "[" + i + "]" : path + "[" + i + "]";
                collectJsonFields(node.get(i), childPath, fields);
            }
            return;
        }

        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            if (names.isEmpty() && !path.isBlank()) {
                fields.put(path, "{}");
                return;
            }
            for (String name : names) {
                String childPath = path.isBlank() ? name : path + "." + name;
                collectJsonFields(node.get(name), childPath, fields);
            }
        }
    }

    private Map<String, String> extractXmlFields(String xml) {
        try {
            Document document = SecureXml.documentBuilder()
                .parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (root == null) {
                return Map.of();
            }

            Map<String, String> fields = new LinkedHashMap<>();
            collectXmlFields(root, root.getTagName(), fields);
            return fields;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void collectXmlFields(Element element, String path, Map<String, String> fields) {
        List<Element> childElements = new ArrayList<>();
        for (int i = 0; i < element.getChildNodes().getLength(); i++) {
            Node node = element.getChildNodes().item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                childElements.add((Element) node);
            }
        }

        if (childElements.isEmpty()) {
            String value = element.getTextContent();
            fields.put(path, value == null ? "" : value.trim());
            return;
        }

        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (Element child : childElements) {
            nameCounts.merge(child.getTagName(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Element child : childElements) {
            String name = child.getTagName();
            int index = seen.merge(name, 1, Integer::sum) - 1;
            String childPath = nameCounts.get(name) > 1
                ? path + "." + name + "[" + index + "]"
                : path + "." + name;
            collectXmlFields(child, childPath, fields);
        }
    }
}
