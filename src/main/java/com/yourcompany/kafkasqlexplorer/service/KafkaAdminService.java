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
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
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

    public TopicDescriptor getTopicDescriptor(String topicName) throws ExecutionException, InterruptedException {
        Map<String, TopicDescription> descriptions = adminClient.describeTopics(Collections.singletonList(topicName)).allTopicNames().get();
        TopicDescription desc = descriptions.get(topicName);

        List<TopicPartition> partitions = desc.partitions().stream()
                .map(p -> new TopicPartition(topicName, p.partition()))
                .collect(Collectors.toList());

        Map<Integer, Long> minOffsets = new HashMap<>();
        Map<Integer, Long> maxOffsets = new HashMap<>();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
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
        List<String> samples = new ArrayList<>();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-messages-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            TopicPartition tp = new TopicPartition(topicName, 0);
            consumer.assign(Collections.singletonList(tp));
            consumer.seekToBeginning(Collections.singletonList(tp));

            org.apache.kafka.clients.consumer.ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofSeconds(2));
            int count = 0;
            for (org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record : records) {
                if (count >= maxMessages) break;
                if (record.value() != null) {
                    samples.add(record.value());
                    count++;
                }
            }
        } catch (Exception e) {
            // Handle error or log
        }
        return samples;
    }
}
