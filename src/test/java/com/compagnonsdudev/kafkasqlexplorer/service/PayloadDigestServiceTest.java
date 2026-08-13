// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PayloadDigestServiceTest {

    private ProcessMiningConfig config;
    private PayloadDigestService service;

    @BeforeEach
    void setUp() {
        config = new ProcessMiningConfig();
        service = new PayloadDigestService(config);
    }

    private PayloadDigest digest(String json, Set<String> mapped) {
        return service.digest("orders", 0, 42L, 1_700_000_000_000L, "k1",
            json.getBytes(StandardCharsets.UTF_8), mapped);
    }

    @Test
    void deeplyNestedFieldIsExtractedAndReported() {
        String json = nested(10, "\"correlationId\": \"CID-7\"");

        PayloadDigest digest = digest(json, Set.of("l1.l2.l3.l4.l5.l6.l7.l8.l9.l10.correlationId"));

        assertEquals("CID-7", digest.fields().get("l1.l2.l3.l4.l5.l6.l7.l8.l9.l10.correlationId"));
        assertFalse(digest.truncated(), "10 levels are within the default depth budget");
        PayloadShape shape = service.shapesFor(List.of(digest.shapeId())).get(0);
        assertEquals(11, shape.depth());
    }

    @Test
    void nestingBeyondTheDepthBudgetIsSkippedNotWalked() {
        config.setMaxDepth(5);
        String json = nested(20, "\"leaf\": 1");

        PayloadDigest digest = digest(json, Set.of());

        assertTrue(digest.truncated(), "a 20-level document must be reported as partially digested");
        assertNull(digest.parseError());
    }

    @Test
    void megabytePayloadCollapsesToASmallDigest() {
        String json = oneMegabyteOrder();
        assertTrue(json.length() > 1_000_000, "fixture should be around 1 MB, was " + json.length());

        PayloadDigest digest = digest(json, Set.of("order.id"));

        assertEquals(json.length(), digest.payloadBytes());
        assertEquals("ORD-1", digest.fields().get("order.id"));
        assertEquals(5_000, digest.arrayCounts().get("order.items[]"),
            "the true array cardinality is reported even though only a sample is walked");
        assertTrue(digest.sample().size() <= config.getMaxSampleFields());
        assertTrue(digest.estimatedHeapBytes() < 64 * 1024,
            "digest should be orders of magnitude smaller than the payload, was "
                + digest.estimatedHeapBytes());
    }

    @Test
    void identicalStructuresShareAShapeAndDifferentOnesDoNot() {
        PayloadDigest first = digest("{\"a\": 1, \"b\": {\"c\": \"x\"}}", Set.of());
        PayloadDigest second = digest("{\"a\": 9, \"b\": {\"c\": \"y\"}}", Set.of());
        PayloadDigest other = digest("{\"a\": 1, \"z\": true}", Set.of());

        assertEquals(first.shapeId(), second.shapeId());
        assertNotEquals(first.shapeId(), other.shapeId());
        assertEquals(2, service.shapesFor(List.of(first.shapeId(), second.shapeId(), other.shapeId())).size());
    }

    @Test
    void longValuesAreTruncatedInMappedFields() {
        String blob = "x".repeat(5_000);
        PayloadDigest digest = digest("{\"ref\": \"" + blob + "\"}", Set.of("ref"));

        String value = digest.fields().get("ref");
        assertTrue(value.length() < 300, "mapped value must be truncated, was " + value.length());
        assertTrue(value.endsWith("(5000 chars)"));
    }

    @Test
    void oversizedValuesAreKeptOutOfTheSample() {
        PayloadDigest digest = digest("{\"blob\": \"" + "y".repeat(5_000) + "\", \"id\": \"A\"}", Set.of());

        assertFalse(digest.sample().containsKey("blob"));
        assertEquals("A", digest.sample().get("id"));
    }

    @Test
    void mappedPathsAreNormalizedFromJsonPath() {
        FieldMapping mapping = new FieldMapping("m1",
            Map.of("orders", "$.order.items[0].sku"),
            Map.of("orders", "$['order']['createdAt']"),
            Map.of(), Map.of());

        Set<String> paths = service.mappedPaths(mapping, "orders");

        assertTrue(paths.contains("order.items[].sku"));
        assertTrue(paths.contains("order.createdAt"));
    }

    @Test
    void invalidJsonKeepsAPreviewAndReportsTheError() {
        PayloadDigest digest = digest("{\"a\": 1, ", Set.of());

        assertNotNull(digest.parseError());
        assertNotNull(digest.preview());
        assertEquals(PayloadDigestService.FORMAT_JSON, digest.format());
    }

    @Test
    void plainTextIsPreviewedNotParsed() {
        PayloadDigest digest = digest("z".repeat(10_000), Set.of());

        assertEquals(PayloadDigestService.FORMAT_TEXT, digest.format());
        assertEquals(config.getPreviewChars(), digest.preview().length());
        assertTrue(digest.truncated());
        assertNull(digest.shapeId());
    }

    @Test
    void xmlIsWalkedWithoutBuildingADom() {
        String xml = """
            <order><id>ORD-9</id><customer><name>Ada</name></customer>
            <items><item><sku>A</sku></item><item><sku>B</sku></item></items></order>
            """;

        PayloadDigest digest = digest(xml, Set.of("order.id"));

        assertEquals(PayloadDigestService.FORMAT_XML, digest.format());
        assertEquals("ORD-9", digest.fields().get("order.id"));
        assertEquals("Ada", digest.sample().get("order.customer.name"));
        assertEquals(2, digest.arrayCounts().get("order.items.item[]"));
    }

    @Test
    void emptyPayloadIsDigestedWithoutParsing() {
        PayloadDigest digest = service.digest("orders", 0, 1L, 1L, null, new byte[0], Set.of());

        assertEquals(PayloadDigestService.FORMAT_EMPTY, digest.format());
        assertNull(digest.shapeId());
        assertFalse(digest.truncated());
    }

    /** {"l1":{"l2":{… {inner} …}}} with the given number of levels. */
    private static String nested(int levels, String inner) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= levels; i++) {
            sb.append("{\"l").append(i).append("\": ");
        }
        sb.append("{").append(inner).append("}");
        sb.append("}".repeat(levels));
        return sb.toString();
    }

    /** A ~1 MB order document: a handful of header fields plus a 5 000-element line-item array. */
    private static String oneMegabyteOrder() {
        StringBuilder sb = new StringBuilder(1_200_000);
        sb.append("{\"order\": {\"id\": \"ORD-1\", \"status\": \"NEW\", \"items\": [");
        for (int i = 0; i < 5_000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"sku\": \"SKU-").append(i)
              .append("\", \"label\": \"").append("d".repeat(150))
              .append("\", \"qty\": ").append(i % 7)
              .append(", \"price\": {\"amount\": ").append(i)
              .append(", \"currency\": \"EUR\"}}");
        }
        sb.append("]}}");
        return sb.toString();
    }
}
