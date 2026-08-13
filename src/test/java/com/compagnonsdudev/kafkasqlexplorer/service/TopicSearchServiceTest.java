// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchResponse;
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

    private WindowAwareConsumer mockConsumer;
    private ExplorerConfig explorerConfig;
    private TopicSearchService service;

    @BeforeEach
    void setUp() {
        mockConsumer = new WindowAwareConsumer();
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

    /**
     * MockConsumer has no way to seed {@code offsetsForTimes}, and the time window is exactly what
     * these tests are about — so the lookup is answered from a map the test controls.
     */
    private static final class WindowAwareConsumer extends MockConsumer<byte[], byte[]> {
        private final Map<TopicPartition, Long> windowOffsets = new java.util.HashMap<>();
        /** Where the scan positioned itself — read after the service has closed the consumer. */
        private final Map<TopicPartition, Long> seeks = new java.util.HashMap<>();

        WindowAwareConsumer() {
            super("earliest");
        }

        void setWindowOffset(TopicPartition partition, long offset) {
            windowOffsets.put(partition, offset);
        }

        Long seekedTo(TopicPartition partition) {
            return seeks.get(partition);
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            seeks.put(partition, offset);
            super.seek(partition, offset);
        }

        @Override
        public synchronized Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp>
                offsetsForTimes(Map<TopicPartition, Long> timestamps) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndTimestamp> found =
                new java.util.LinkedHashMap<>();
            timestamps.forEach((tp, ts) -> {
                Long offset = windowOffsets.get(tp);
                found.put(tp, offset == null ? null
                    : new org.apache.kafka.clients.consumer.OffsetAndTimestamp(offset, ts));
            });
            return found;
        }
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
        return new TopicSearchRequest(query, "CONTAINS", null, null, null, null, null, null, null,
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
            null, null, null, "EARLIEST", null, null, null, Map.of("0", 2L), null, null, null, null);

        TopicSearchResponse response = service.search(TOPIC, resume);

        assertEquals(1, response.scanned(), "only the record after the cursor should be read");
        assertEquals(2L, response.hits().get(0).offset());
    }

    @Test
    void fieldSearchFiltersOnANestedPath() {
        seedRecords(
            "{\"order\": {\"total\": 10}}",
            "{\"order\": {\"total\": 250}}");
        TopicSearchRequest request = new TopicSearchRequest(null, "FIELD", null, null, null, null,
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

    // ── Exact key: partition targeting ──────────────────────────────────────

    private static TopicSearchRequest keySearch(String key, boolean keyPartitioning, String operator) {
        return new TopicSearchRequest(null, "KEY", null, null, null, keyPartitioning, null,
            operator, key, "EARLIEST", null, null, null, null, null, null, null, null);
    }

    /** Four partitions, so a narrowed scan is visibly narrower than a full one. */
    private void seedFourPartitions() {
        mockConsumer.updatePartitions(TOPIC, List.of(
            new PartitionInfo(TOPIC, 0, Node.noNode(), new Node[0], new Node[0]),
            new PartitionInfo(TOPIC, 1, Node.noNode(), new Node[0], new Node[0]),
            new PartitionInfo(TOPIC, 2, Node.noNode(), new Node[0], new Node[0]),
            new PartitionInfo(TOPIC, 3, Node.noNode(), new Node[0], new Node[0])));
        Map<TopicPartition, Long> zeros = Map.of(
            new TopicPartition(TOPIC, 0), 0L, new TopicPartition(TOPIC, 1), 0L,
            new TopicPartition(TOPIC, 2), 0L, new TopicPartition(TOPIC, 3), 0L);
        mockConsumer.updateBeginningOffsets(zeros);
        mockConsumer.updateEndOffsets(zeros);
    }

    @Test
    void keyPartitioningScansOnlyThePartitionTheDefaultPartitionerWouldHaveChosen() {
        seedFourPartitions();

        TopicSearchResponse response = service.search(TOPIC, keySearch("K-1", true, "EQ"));

        assertEquals(1, mockConsumer.assignment().size(), "only one partition should be assigned");
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("assuming the default partitioner")),
            "a narrowed scan must say what it assumed, got " + response.warnings());
    }

    /** A substring match says nothing about which key was hashed, so the shortcut does not apply. */
    @Test
    void keyPartitioningIsIgnoredAndExplainedOutsideAnExactKeySearch() {
        seedFourPartitions();

        TopicSearchResponse response = service.search(TOPIC, keySearch("K-1", true, "CONTAINS"));

        assertEquals(4, mockConsumer.assignment().size(), "every partition should still be scanned");
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("only applies to an exact")),
            "expected the shortcut to explain why it did not apply, got " + response.warnings());
    }

    @Test
    void withoutTheOptInEveryPartitionIsScanned() {
        seedFourPartitions();

        TopicSearchResponse response = service.search(TOPIC, keySearch("K-1", false, "EQ"));

        assertEquals(4, mockConsumer.assignment().size());
        assertTrue(response.warnings().isEmpty(), "nothing to warn about, got " + response.warnings());
    }

    @Test
    void keySearchComparesTheWholeKey() {
        seedRecords("{}", "{}");

        assertEquals(1, service.search(TOPIC, keySearch("key-1", false, "EQ")).hits().size());
        assertEquals(0, service.search(TOPIC, keySearch("key", false, "EQ")).hits().size(),
            "an exact key search must not match a prefix");
    }

    // ── Fenêtre temporelle : les plus récents *dans* la fenêtre ─────────────

    /**
     * A time window used to seek to its start and read forward, so the record budget went to the
     * OLDEST records of the window and a recent match could be missed. The window is now a floor
     * under LAST_N, not a replacement for it.
     */
    @Test
    void aTimeWindowReadsTheMostRecentRecordsInsideIt() {
        mockConsumer.updateEndOffsets(Map.of(PARTITION, 1_000L));
        // La fenêtre commence à l'offset 10, mais les 5 derniers records sont bien plus récents.
        mockConsumer.setWindowOffset(PARTITION, 10L);

        TopicSearchRequest request = new TopicSearchRequest("x", "CONTAINS", null, null, null, null,
            null, null, null, "LAST_N", null, 15, null, null, null, null, 5, null);
        service.search(TOPIC, request);

        // 1 000 - (5 / 1 + 1) = 994, bien après le début de fenêtre : on lit la fin, pas le début.
        assertEquals(994L, mockConsumer.seekedTo(PARTITION));
    }

    /** When the window starts after that, it wins: nothing older than the window is read. */
    @Test
    void aTimeWindowStillExcludesWhatIsOlderThanItself() {
        mockConsumer.updateEndOffsets(Map.of(PARTITION, 1_000L));
        mockConsumer.setWindowOffset(PARTITION, 998L);

        TopicSearchRequest request = new TopicSearchRequest("x", "CONTAINS", null, null, null, null,
            null, null, null, "LAST_N", null, 15, null, null, null, null, 5, null);
        service.search(TOPIC, request);

        assertEquals(998L, mockConsumer.seekedTo(PARTITION));
    }

    // ── Payloads that are not text ──────────────────────────────────────────

    /** A protobuf blob read as UTF-8 becomes replacement characters; no text search can match it. */
    @Test
    void aBinaryPayloadIsReportedRatherThanSilentlyUnmatched() {
        mockConsumer.updateEndOffsets(Map.of(PARTITION, 2L));
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0,
                "key-0".getBytes(StandardCharsets.UTF_8), new byte[]{0x08, (byte) 0x96, 0x01, 0x00}));
            mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1,
                "key-1".getBytes(StandardCharsets.UTF_8), "plain text".getBytes(StandardCharsets.UTF_8)));
        });

        TopicSearchResponse response = service.search(TOPIC, textSearch("nothing-here"));

        assertEquals(0, response.hits().size());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("not text")),
            "expected the binary payload to be reported, got " + response.warnings());
    }

    @Test
    void textPayloadsAreNotMistakenForBinary() {
        assertFalse(TopicSearchService.looksBinary("{\"a\": \"é — ok\"}"));
        assertFalse(TopicSearchService.looksBinary(""));
        assertFalse(TopicSearchService.looksBinary(null));
        assertTrue(TopicSearchService.looksBinary("abc\uFFFDdef"));
        assertTrue(TopicSearchService.looksBinary("abc\0def"));
    }

    @Test
    void invalidRegexIsSurfacedToTheCaller() {
        TopicSearchRequest request = new TopicSearchRequest("[unclosed", "REGEX", null, null, null,
            null, null, null, null, "EARLIEST", null, null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.search(TOPIC, request));
    }

    @Test
    void unknownTopicIsReportedAsAWarningRatherThanAnError() {
        TopicSearchResponse response = service.search("missing-topic", textSearch("x"));

        assertTrue(response.hits().isEmpty());
        assertFalse(response.warnings().isEmpty());
    }

    /**
     * Le point de la lecture par coordonnées : un hit de recherche est tronqué pour que cent hits
     * restent une petite réponse, ce qui ne laissait aucun moyen de lire le reste du payload.
     */
    @Test
    void readsOneRecordWholeEvenWhenASearchHitWouldBeTruncated() {
        String payload = "{\"body\":\"" + "x".repeat(200) + "\"}";
        explorerConfig.setSearchMaxValueChars(20);
        seedRecords("{\"status\":\"NEW\"}", payload);

        TopicMessage record = service.readRecord(TOPIC, 0, 1L);

        assertNotNull(record);
        assertEquals(1L, record.offset());
        assertEquals("key-1", record.key());
        assertEquals(payload, record.value(), "read on purpose, so it comes back whole");
        assertFalse(record.truncated());
        assertEquals(payload.length(), record.valueBytes());
    }

    /** Le cap de la lecture reste un cap, et la réponse le dit — un cap qui ment serait le même bug. */
    @Test
    void stillTruncatesAtItsOwnCapAndSaysSo() {
        String payload = "y".repeat(500);
        explorerConfig.setRecordMaxValueChars(100);
        seedRecords(payload);

        TopicMessage record = service.readRecord(TOPIC, 0, 0L);

        assertNotNull(record);
        assertEquals(100, record.value().length());
        assertTrue(record.truncated());
        assertEquals(500, record.valueBytes());
    }

    /**
     * Hors plage, partition ou topic inconnus : autant de « pas de record » que l'appelant doit
     * pouvoir distinguer d'une panne. Un cas par test — chaque lecture ouvre et ferme son
     * consumer, donc le double du harnais ne sert qu'une fois.
     */
    @Test
    void returnsNothingPastTheEndOffset() {
        seedRecords("{\"status\":\"NEW\"}", "{\"status\":\"SHIPPED\"}");

        assertNull(service.readRecord(TOPIC, 0, 9L));
    }

    @Test
    void returnsNothingForAnUnknownPartition() {
        seedRecords("{\"status\":\"NEW\"}");

        assertNull(service.readRecord(TOPIC, 3, 0L));
    }

    @Test
    void returnsNothingForAnUnknownTopic() {
        assertNull(service.readRecord("missing-topic", 0, 0L));
    }

    /**
     * Sur un topic compacté, l'offset peut simplement ne plus exister : le lecteur tombe sur le
     * record suivant, et lire au-delà de la cible prouve qu'elle a disparu.
     */
    @Test
    void returnsNothingWhenTheRecordWasCompactedAway() {
        mockConsumer.updateEndOffsets(Map.of(PARTITION, 6L));
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L,
                "key-0".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8)));
            mockConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 5L,
                "key-5".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8)));
        });

        assertNull(service.readRecord(TOPIC, 0, 3L));
    }
}
