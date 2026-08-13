// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaLiveConsumerTest {

    private static final TopicPartition PARTITION = new TopicPartition("orders", 0);
    private static final String PAYLOAD =
        "{\"order\": {\"id\": \"ORD-1\", \"status\": \"NEW\", \"items\": [{\"sku\": \"A\"}, {\"sku\": \"B\"}]}}";

    private MockConsumer<String, byte[]> mockConsumer;
    private LlmAnalysisService llmAnalysisService;
    private SseEmitterManager sseEmitterManager;
    private ClaudeConfig claudeConfig;
    private KafkaLiveConsumer liveConsumer;

    @BeforeEach
    void setUp() {
        mockConsumer = new MockConsumer<>("latest");
        mockConsumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        mockConsumer.updateEndOffsets(Map.of(PARTITION, 0L));

        llmAnalysisService = mock(LlmAnalysisService.class);
        sseEmitterManager = mock(SseEmitterManager.class);
        when(sseEmitterManager.exists(anyString())).thenReturn(true);

        claudeConfig = new ClaudeConfig();
        claudeConfig.setApiKey("test-key");
        claudeConfig.setSnapshotWindowSize(2);

        ProcessMiningConfig processMiningConfig = new ProcessMiningConfig();
        liveConsumer = new KafkaLiveConsumer(llmAnalysisService, sseEmitterManager,
            new PayloadDigestService(processMiningConfig), claudeConfig, new KafkaConfig(),
            processMiningConfig) {
            @Override
            protected Consumer<String, byte[]> createConsumer(Properties props) {
                return mockConsumer;
            }
        };
    }

    @AfterEach
    void tearDown() {
        liveConsumer.shutdown();
    }

    @Test
    void windowIsAnalysedAsDigestsAndStatsDescribeIngestion() throws Exception {
        CountDownLatch analysed = new CountDownLatch(1);
        AtomicReference<List<PayloadDigest>> analysedWindow = new AtomicReference<>();
        when(llmAnalysisService.analyzeLiveDigests(anyList(), any(), any(), any()))
            .thenAnswer(invocation -> {
                analysedWindow.set(invocation.getArgument(0));
                analysed.countDown();
                return new ProcessMiningResult("flowchart TD", "ok", List.of(), List.of(), List.of());
            });

        mockConsumer.schedulePollTask(() -> mockConsumer.rebalance(List.of(PARTITION)));
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.seek(PARTITION, 0);
            mockConsumer.addRecord(record(0));
            mockConsumer.addRecord(record(1));
        });

        liveConsumer.startSession("session-1", List.of("orders"), null);

        assertTrue(analysed.await(20, TimeUnit.SECONDS), "the window should have been analysed");

        List<PayloadDigest> window = analysedWindow.get();
        assertEquals(2, window.size());
        PayloadDigest first = window.get(0);
        assertEquals(PAYLOAD.length(), first.payloadBytes(), "the original payload size is preserved");
        assertEquals("ORD-1", first.sample().get("order.id"));
        assertEquals(2, first.arrayCounts().get("order.items[]"));
        assertNotNull(first.shapeId());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) captureEvent("WINDOW_STATS");
        assertEquals(2, stats.get("windowSize"));
        assertEquals(2L, stats.get("messagesReceived"));
        assertEquals(2L * PAYLOAD.length(), stats.get("bytesReceived"));
        assertEquals(0, stats.get("parseFailures"));
        assertEquals(1, stats.get("distinctShapes"));
    }

    @Test
    void stoppingASessionClosesTheConsumer() throws Exception {
        mockConsumer.schedulePollTask(() -> mockConsumer.rebalance(List.of(PARTITION)));
        liveConsumer.startSession("session-2", List.of("orders"), null);

        // Let the polling task subscribe before asking it to stop.
        TimeUnit.SECONDS.sleep(2);
        liveConsumer.stopSession("session-2");

        long deadline = System.currentTimeMillis() + 10_000;
        while (!mockConsumer.closed() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertTrue(mockConsumer.closed(), "the polling task must close the consumer");
        verify(sseEmitterManager, timeout(5_000)).complete("session-2");
    }

    private Object captureEvent(String eventName) {
        List<Object> payloads = new ArrayList<>();
        verify(sseEmitterManager, timeout(5_000).atLeastOnce())
            .send(anyString(), eq(eventName), argThat(payload -> {
                payloads.add(payload);
                return true;
            }));
        return payloads.get(payloads.size() - 1);
    }

    private ConsumerRecord<String, byte[]> record(long offset) {
        return new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), offset,
            "key-" + offset, PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }
}
