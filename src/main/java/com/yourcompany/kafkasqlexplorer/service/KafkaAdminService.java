package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class KafkaAdminService {

    private final KafkaConfig kafkaConfig;
    private AdminClient adminClient;

    public KafkaAdminService(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    @PostConstruct
    public void init() {
        if (this.adminClient != null) {
            this.adminClient.close();
        }
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        this.adminClient = AdminClient.create(props);
    }

    @PreDestroy
    public void close() {
        if (adminClient != null) {
            adminClient.close();
        }
    }

    public List<String> listTopics() throws ExecutionException, InterruptedException {
        return new ArrayList<>(adminClient.listTopics(new ListTopicsOptions().listInternal(false)).names().get());
    }

    public Map<String, Long> getTopicsSize(List<String> topicNames) {
        Map<String, Long> sizes = new HashMap<>();
        if (topicNames.isEmpty()) return sizes;

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-bulk-metadata-" + UUID.randomUUID());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> allPartitions = new ArrayList<>();
            Map<String, List<TopicPartition>> topicToPartitions = new HashMap<>();

            try {
                Map<String, TopicDescription> descriptions = adminClient.describeTopics(topicNames).allTopicNames().get();
                for (String name : topicNames) {
                    TopicDescription desc = descriptions.get(name);
                    if (desc != null) {
                        List<TopicPartition> tps = desc.partitions().stream()
                                .map(p -> new TopicPartition(name, p.partition()))
                                .toList();
                        allPartitions.addAll(tps);
                        topicToPartitions.put(name, tps);
                    }
                }

                Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(allPartitions);
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(allPartitions);

                for (String name : topicNames) {
                    List<TopicPartition> tps = topicToPartitions.get(name);
                    if (tps != null) {
                        long size = tps.stream()
                                .mapToLong(tp -> endOffsets.get(tp) - beginningOffsets.get(tp))
                                .sum();
                        sizes.put(name, size);
                    } else {
                        sizes.put(name, 0L);
                    }
                }
            } catch (Exception e) {
                // Return empty/zero sizes if failed
            }
        }
        return sizes;
    }

    public TopicDescriptor getTopicDescriptor(String topicName) throws ExecutionException, InterruptedException {
        Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get();
        TopicDescription desc = descriptions.get(topicName);

        List<TopicPartition> partitions = desc.partitions().stream()
                .map(p -> new TopicPartition(topicName, p.partition()))
                .collect(Collectors.toList());

        Map<Integer, Long> minOffsets = new HashMap<>();
        Map<Integer, Long> maxOffsets = new HashMap<>();

        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-sql-explorer-metadata-" + UUID.randomUUID());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            for (TopicPartition tp : partitions) {
                minOffsets.put(tp.partition(), beginningOffsets.get(tp));
                maxOffsets.put(tp.partition(), endOffsets.get(tp));
            }
        }

        long totalSize = maxOffsets.values().stream().mapToLong(Long::longValue).sum() -
                         minOffsets.values().stream().mapToLong(Long::longValue).sum();

        return new TopicDescriptor(
                topicName,
                desc.partitions().size(),
                minOffsets,
                maxOffsets,
                MessageFormat.AUTO, // Placeholder, format detection would be elsewhere
                totalSize
        );
    }

    public boolean ping() {
        try {
            adminClient.listTopics().names().get();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> getSampleMessages(String topicName, int maxMessages) {
        return getRecentRecords(topicName, maxMessages).stream()
                .map(org.apache.kafka.clients.consumer.ConsumerRecord::value)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> getRecentRecords(String topicName, int maxMessages) {
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "recent-records-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get();
            TopicDescription desc = descriptions.get(topicName);
            if (desc == null) return records;

            List<TopicPartition> partitions = desc.partitions().stream()
                    .map(p -> new TopicPartition(topicName, p.partition()))
                    .toList();

            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            for (TopicPartition tp : partitions) {
                long endOffset = endOffsets.get(tp);
                long startOffset = Math.max(0, endOffset - maxMessages);
                consumer.seek(tp, startOffset);
            }

            int count = 0;
            boolean moreRecords = true;
            while (count < maxMessages && moreRecords) {
                org.apache.kafka.clients.consumer.ConsumerRecords<String, String> polled = consumer.poll(java.time.Duration.ofMillis(500));
                if (polled.isEmpty()) moreRecords = false;
                for (org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record : polled) {
                    records.add(record);
                    count++;
                    if (count >= maxMessages) break;
                }
            }
        } catch (Exception e) {
            // Handle error
        }
        return records;
    }
}
