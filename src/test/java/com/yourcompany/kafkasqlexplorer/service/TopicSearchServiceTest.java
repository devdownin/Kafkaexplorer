// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicSearchServiceTest {

    private static final String TOPIC = "orders";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private MockConsumer<byte[], byte[]> mockConsumer;
    private ExplorerConfig explorerConfig;
    private TopicSearchService service;

    @BeforeEach
    void setUp() {
        mockConsumer = new MockConsumer<>("earliest");
        mockConsumer.updatePartitions(TOPIC, List.of(
            new PartitionInfo(TOPIC, 0, Node.noNode(), new Node[0], new Node[0])));
        mockConsumer.updateBeginningOffsets(Map.of(PARTITION, 0L));

        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        when(kafkaAdminService.deserializeValue(anyString(), any()))
            .thenAnswer(invocation -> {
                byte[] bytes = invocation.getArgument(1);
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            });

        explorerConfig = new ExplorerConfig();
        service = new TopicSearchService(new KafkaConfig(), kafkaAdminService, explorerConfig) {
            @Override
            protected Consumer<byte[], byte[]> createConsumer(Properties props) {
                return mockConsumer;
            }
        };
    }

    private void seedRecords(String... values) {
        mockConsumer.updateEndOffsets(Map.of(PARTITION, (long) values.length));
        mockConsumer.schedulePollTask(() -> {
            for (int i = 0; i < values.length; i++) {
                mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i,
                    ("key-" + i).getBytes(StandardCharsets.UTF_8),
                    values[i].getBytes(StandardCharsets.UTF_8)));
            }
        });
    }

    private static TopicSearchRequest textSearch(String query) {
        return new TopicSearchRequest(query, "CONTAINS", null, null, null, null, null, null,
            "EARLIEST", null, null, null, null, null, null, null, null);
    }

    @Test
    void findsMatchingRecordsWithTheirCoordinates() {
        seedRecords(
            "{\"status\": \"NEW\"}",
            "{\"status\": \"SHIPPED\"}",
            "{\"status\": \"NEW\"}");

        TopicSearchResponse response = service.search(TOPIC, textSearch("SHIPPED"));

        assertEquals(1, response.hits().size());
        assertEquals(1, response.matched());
        assertEquals(3, response.scanned());
        assertEquals(0, response.hits().get(0).partition());
        assertEquals(1L, response.hits().get(0).offset());
        assertEquals("key-1", response.hits().get(0).key());
        assertTrue(response.exhausted(), "the whole partition was read");
        assertEquals("EXHAUSTED", response.stopReason());
    }

    @Test
    void reportsScanCoverageWhenNothingMatches() {
        seedRecords("{\"status\": \"NEW\"}", "{\"status\": \"NEW\"}");

        TopicSearchResponse response = service.search(TOPIC, textSearch("SHIPPED"));

        assertTrue(response.hits().isEmpty());
        assertEquals(2, response.scanned(), "the user must know how much ground was covered");
        assertTrue(response.exhausted());
    }

    @Test
    void stopsAtTheHitBudgetAndHandsBackAResumableCursor() {
        explorerConfig.setSearchMaxHits(2);
        seedRecords("a-match", "b-match", "c-match", "d-match");

        TopicSearchResponse response = service.search(TOPIC, textSearch("match"));

        assertEquals(2, response.hits().size());
        assertEquals("MAX_HITS", response.stopReason());
        assertFalse(response.exhausted());
        // Cursor points just past the last record handed back, so resuming does not repeat it.
        assertEquals(2L, response.nextCursor().get("0"));
    }

    @Test
    void resumingFromACursorContinuesWhereTheScanStopped() {
        seedRecords("first", "second", "third");
        TopicSearchRequest resume = new TopicSearchRequest(null, "CONTAINS", null, null, null, null,
            null, null, "EARLIEST", null, null, null, Map.of("0", 2L), null, null, null, null);

        TopicSearchResponse response = service.search(TOPIC, resume);

        assertEquals(1, response.scanned(), "only the record after the cursor should be read");
        assertEquals(2L, response.hits().get(0).offset());
    }

    @Test
    void fieldSearchFiltersOnANestedPath() {
        seedRecords(
            "{\"order\": {\"total\": 10}}",
            "{\"order\": {\"total\": 250}}");
        TopicSearchRequest request = new TopicSearchRequest(null, "FIELD", null, null, null,
            "order.total", "GT", "100", "EARLIEST", null, null, null, null, null, null, null, null);

        TopicSearchResponse response = service.search(TOPIC, request);

        assertEquals(1, response.hits().size());
        assertEquals(1L, response.hits().get(0).offset());
    }

    @Test
    void oversizedValuesAreTruncatedButTheirRealSizeIsReported() {
        explorerConfig.setSearchMaxValueChars(50);
        String big = "{\"blob\": \"" + "x".repeat(5_000) + "\"}";
        seedRecords(big);

        TopicSearchResponse response = service.search(TOPIC, textSearch("blob"));

        assertEquals(1, response.hits().size());
        assertEquals(50, response.hits().get(0).value().length());
        assertEquals(big.length(), response.hits().get(0).valueBytes());
        assertTrue(response.hits().get(0).truncated());
    }

    @Test
    void invalidRegexIsSurfacedToTheCaller() {
        TopicSearchRequest request = new TopicSearchRequest("[unclosed", "REGEX", null, null, null,
            null, null, null, "EARLIEST", null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.search(TOPIC, request));
    }

    @Test
    void unknownTopicIsReportedAsAWarningRatherThanAnError() {
        TopicSearchResponse response = service.search("missing-topic", textSearch("x"));

        assertTrue(response.hits().isEmpty());
        assertFalse(response.warnings().isEmpty());
    }
}
