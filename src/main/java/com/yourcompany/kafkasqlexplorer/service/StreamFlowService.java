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
        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            log.error("Failed to list topics for stream flow", e);
            return new StreamFlowResponse(Collections.emptyList(), Collections.emptyList());
        }

        // Parallel scanning with limited thread pool
        List<CompletableFuture<List<Occurrence>>> futures = topics.stream()
                .map(topic -> CompletableFuture.supplyAsync(() -> scanTopic(topic, request), executorService))
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

    private List<Occurrence> scanTopic(String topic, StreamFlowRequest request) {
        List<Occurrence> topicOccurrences = new ArrayList<>();
        List<ConsumerRecord<String, String>> records;

        int limit = Math.min(request.maxMessagesPerTopic(), MAX_TOTAL_RECORDS_PER_TOPIC);

        try {
            if (request.timeLimitMinutes() != null && request.timeLimitMinutes() > 0) {
                records = kafkaAdminService.getRecordsSince(topic, request.timeLimitMinutes(), limit);
            } else {
                records = kafkaAdminService.getRecentRecords(topic, limit);
            }

            for (ConsumerRecord<String, String> record : records) {
                if (matchesKey(record, request)) {
                    topicOccurrences.add(new Occurrence(topic, record.timestamp()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan topic {} for stream flow", topic, e);
        }
        return topicOccurrences;
    }

    private boolean matchesKey(ConsumerRecord<String, String> record, StreamFlowRequest request) {
        String key = request.messageKey();
        String path = request.searchPath();

        if (key == null || key.isBlank()) return false;

        String value = record.value();

        if (path != null && !path.isBlank() && value != null) {
            String trimmed = value.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return matchJsonPath(value, path, key);
            } else if (trimmed.startsWith("<")) {
                return matchXPath(value, path, key);
            }
        }

        // Default global search
        if (record.key() != null && record.key().contains(key)) return true;
        if (value != null && value.contains(key)) return true;

        return false;
    }

    private boolean matchJsonPath(String json, String path, String expectedValue) {
        try {
            Object result = JsonPath.read(json, path);
            return result != null && String.valueOf(result).contains(expectedValue);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchXPath(String xml, String path, String expectedValue) {
        try {
            DocumentBuilder builder = xmlFactory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            XPath xPath = xPathFactory.newXPath();
            String result = (String) xPath.compile(path).evaluate(doc, XPathConstants.STRING);
            return result != null && result.contains(expectedValue);
        } catch (Exception e) {
            return false;
        }
    }

    private record Occurrence(String topic, long timestamp) {}
}
