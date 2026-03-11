package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StreamFlowService {

    private final KafkaAdminService kafkaAdminService;

    public StreamFlowService(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    public StreamFlowResponse getStreamFlow(StreamFlowRequest request) {
        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            return new StreamFlowResponse(Collections.emptyList(), Collections.emptyList());
        }

        List<Occurrence> occurrences = new ArrayList<>();

        for (String topic : topics) {
            List<ConsumerRecord<String, String>> records = kafkaAdminService.getRecentRecords(topic, request.maxMessagesPerTopic());
            for (ConsumerRecord<String, String> record : records) {
                if (matchesKey(record, request.messageKey())) {
                    occurrences.add(new Occurrence(topic, record.timestamp()));
                }
            }
        }

        // Sort by timestamp
        occurrences.sort(Comparator.comparingLong(Occurrence::timestamp));

        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> seenTopics = new HashSet<>();

        for (int i = 0; i < occurrences.size(); i++) {
            Occurrence current = occurrences.get(i);
            if (seenTopics.add(current.topic())) {
                nodes.add(Map.of(
                        "id", current.topic(),
                        "label", current.topic(),
                        "type", "topic"
                ));
            }

            if (i > 0) {
                Occurrence previous = occurrences.get(i - 1);
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

    private boolean matchesKey(ConsumerRecord<String, String> record, String key) {
        if (key == null || key.isBlank()) return false;

        // Check Kafka key
        if (record.key() != null && record.key().contains(key)) return true;

        // Check Kafka value (plain text, JSON, or XML)
        if (record.value() != null && record.value().contains(key)) return true;

        return false;
    }

    private record Occurrence(String topic, long timestamp) {}
}
