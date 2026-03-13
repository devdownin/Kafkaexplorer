package com.yourcompany.kafkasqlexplorer.service;

import com.jayway.jsonpath.JsonPath;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class StreamFlowService {

    private static final Logger log = LoggerFactory.getLogger(StreamFlowService.class);
    private static final int MAX_TOTAL_RECORDS_PER_TOPIC = 1000;
    private static final int THREAD_POOL_SIZE = 10;

    private final KafkaAdminService kafkaAdminService;
    private final DocumentBuilderFactory xmlFactory;
    private final XPathFactory xPathFactory;
    private final ExecutorService executorService;

    public StreamFlowService(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
        this.xmlFactory = DocumentBuilderFactory.newInstance();
        try {
            this.xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.xmlFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.xmlFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            log.warn("Could not configure XML factory with secure features", e);
        }
        this.xPathFactory = XPathFactory.newInstance();
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    public StreamFlowResponse getStreamFlow(StreamFlowRequest request) {
        List<String> topicsToScan;
        try {
            if (request.targetTopics() != null && !request.targetTopics().isEmpty()) {
                topicsToScan = request.targetTopics();
            } else {
                topicsToScan = kafkaAdminService.listTopics();
            }
        } catch (Exception e) {
            log.error("Failed to list topics for stream flow", e);
            return new StreamFlowResponse(Collections.emptyList(), Collections.emptyList());
        }

        Pattern pattern = null;
        if (request.useRegex() && request.messageKey() != null) {
            try {
                pattern = Pattern.compile(request.messageKey());
            } catch (Exception e) {
                log.warn("Invalid regex provided: {}", request.messageKey());
            }
        }

        final Pattern finalPattern = pattern;

        // Parallel scanning with limited thread pool
        List<CompletableFuture<List<Occurrence>>> futures = topicsToScan.stream()
                .map(topic -> CompletableFuture.supplyAsync(() -> scanTopic(topic, request, finalPattern), executorService))
                .toList();

        List<Occurrence> allOccurrences = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Sort by timestamp
        allOccurrences.sort(Comparator.comparingLong(Occurrence::timestamp));

        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> seenTopics = new HashSet<>();

        for (int i = 0; i < allOccurrences.size(); i++) {
            Occurrence current = allOccurrences.get(i);
            if (seenTopics.add(current.topic())) {
                nodes.add(Map.of(
                        "id", current.topic(),
                        "label", current.topic(),
                        "type", "topic"
                ));
            }

            if (i > 0) {
                Occurrence previous = allOccurrences.get(i - 1);
                if (!previous.topic().equals(current.topic())) {
                    edges.add(Map.of(
                            "from", previous.topic(),
                            "to", current.topic(),
                            "label", "flow"
                    ));
                }
            }
        }

        return new StreamFlowResponse(nodes, edges);
    }

    private List<Occurrence> scanTopic(String topic, StreamFlowRequest request, Pattern pattern) {
        List<Occurrence> topicOccurrences = new ArrayList<>();
        List<ConsumerRecord<String, String>> records;

        int limit = Math.min(request.maxMessagesPerTopic(), MAX_TOTAL_RECORDS_PER_TOPIC);

        try {
            if (request.timeLimitMinutes() != null && request.timeLimitMinutes() > 0) {
                records = kafkaAdminService.getRecordsSince(topic, request.timeLimitMinutes(), limit);
            } else {
                records = kafkaAdminService.getRecentRecords(topic, limit);
            }

            if (records == null || records.isEmpty()) {
                return topicOccurrences;
            }

            for (ConsumerRecord<String, String> record : records) {
                try {
                    if (matches(record, request, pattern)) {
                        topicOccurrences.add(new Occurrence(topic, record.timestamp()));
                    }
                } catch (Exception e) {
                    log.debug("Skip record in topic {} due to match error: {}", topic, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan topic {} for stream flow", topic, e);
        }
        return topicOccurrences;
    }

    private boolean matches(ConsumerRecord<String, String> record, StreamFlowRequest request, Pattern pattern) {
        String path = request.searchPath();
        String value = record.value();

        if (path != null && !path.isBlank() && value != null) {
            String trimmed = value.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return matchJsonPath(value, path, request.messageKey(), pattern);
            } else if (trimmed.startsWith("<")) {
                return matchXPath(value, path, request.messageKey(), pattern);
            }
        }

        // Default global search
        if (record.key() != null && checkMatch(record.key(), request.messageKey(), pattern)) return true;
        if (value != null && checkMatch(value, request.messageKey(), pattern)) return true;

        return false;
    }

    private boolean checkMatch(String content, String expected, Pattern pattern) {
        if (pattern != null) {
            return pattern.matcher(content).find();
        }
        return content.contains(expected);
    }

    private boolean matchJsonPath(String json, String path, String expectedValue, Pattern pattern) {
        try {
            Object result = JsonPath.read(json, path);
            if (result == null) return false;
            String resultStr = String.valueOf(result);
            return checkMatch(resultStr, expectedValue, pattern);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchXPath(String xml, String path, String expectedValue, Pattern pattern) {
        try {
            DocumentBuilder builder = xmlFactory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            XPath xPath = xPathFactory.newXPath();
            String result = (String) xPath.compile(path).evaluate(doc, XPathConstants.STRING);
            if (result == null) return false;
            return checkMatch(result, expectedValue, pattern);
        } catch (Exception e) {
            return false;
        }
    }

    private record Occurrence(String topic, long timestamp) {}
}
