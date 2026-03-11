package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.domain.StreamFlowResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class StreamFlowServiceTest {

    @Test
    public void testGetStreamFlow() throws Exception {
        KafkaAdminService kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        StreamFlowService streamFlowService = new StreamFlowService(kafkaAdminService);

        when(kafkaAdminService.listTopics()).thenReturn(Arrays.asList("topic1", "topic2", "topic3"));

        // topic1 has the key at T=100
        ConsumerRecord<String, String> r1 = new ConsumerRecord<>("topic1", 0, 0, 100L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "key1", "value1", new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        // topic2 has the key at T=200
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>("topic2", 0, 0, 200L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "key2", "contains-key1", new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        // topic3 does not have the key
        ConsumerRecord<String, String> r3 = new ConsumerRecord<>("topic3", 0, 0, 300L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "key3", "value3", new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        when(kafkaAdminService.getRecentRecords(eq("topic1"), anyInt())).thenReturn(Collections.singletonList(r1));
        when(kafkaAdminService.getRecentRecords(eq("topic2"), anyInt())).thenReturn(Collections.singletonList(r2));
        when(kafkaAdminService.getRecentRecords(eq("topic3"), anyInt())).thenReturn(Collections.singletonList(r3));

        StreamFlowRequest request = new StreamFlowRequest("key1", 10);
        StreamFlowResponse response = streamFlowService.getStreamFlow(request);

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());

        Map<String, String> edge = response.edges().get(0);
        assertEquals("topic1", edge.get("from"));
        assertEquals("topic2", edge.get("to"));
    }

    @Test
    public void testGetStreamFlowWithJsonPath() throws Exception {
        KafkaAdminService kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        StreamFlowService streamFlowService = new StreamFlowService(kafkaAdminService);

        when(kafkaAdminService.listTopics()).thenReturn(Collections.singletonList("orders"));

        String jsonValue = "{\"orderId\": \"ORD-123\", \"status\": \"CREATED\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 0, 0, 100L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, null, jsonValue, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        when(kafkaAdminService.getRecentRecords(eq("orders"), anyInt())).thenReturn(Collections.singletonList(record));

        // Matches correct value
        StreamFlowRequest request1 = new StreamFlowRequest("ORD-123", 10, "$.orderId", null, false, null);
        StreamFlowResponse response1 = streamFlowService.getStreamFlow(request1);
        assertEquals(1, response1.nodes().size());

        // Does not match incorrect value at path
        StreamFlowRequest request2 = new StreamFlowRequest("ORD-999", 10, "$.orderId", null, false, null);
        StreamFlowResponse response2 = streamFlowService.getStreamFlow(request2);
        assertEquals(0, response2.nodes().size());

        // Does not match incorrect path
        StreamFlowRequest request3 = new StreamFlowRequest("ORD-123", 10, "$.wrongPath", null, false, null);
        StreamFlowResponse response3 = streamFlowService.getStreamFlow(request3);
        assertEquals(0, response3.nodes().size());
    }

    @Test
    public void testGetStreamFlowWithRegex() throws Exception {
        KafkaAdminService kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        StreamFlowService streamFlowService = new StreamFlowService(kafkaAdminService);

        when(kafkaAdminService.listTopics()).thenReturn(Collections.singletonList("logs"));

        String logValue = "ERROR: user-456 failed to login";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("logs", 0, 0, 100L, org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, null, logValue, new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());

        when(kafkaAdminService.getRecentRecords(eq("logs"), anyInt())).thenReturn(Collections.singletonList(record));

        // Matches regex
        StreamFlowRequest request1 = new StreamFlowRequest("user-.* failed", 10, null, null, true, null);
        StreamFlowResponse response1 = streamFlowService.getStreamFlow(request1);
        assertEquals(1, response1.nodes().size());

        // Does not match regex
        StreamFlowRequest request2 = new StreamFlowRequest("user-\\d{4} failed", 10, null, null, true, null);
        StreamFlowResponse response2 = streamFlowService.getStreamFlow(request2);
        assertEquals(0, response2.nodes().size());
    }
}
