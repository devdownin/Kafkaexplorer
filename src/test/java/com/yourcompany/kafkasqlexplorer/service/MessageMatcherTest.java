// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageMatcherTest {

    private static final String ORDER = """
        {"order": {"id": "ORD-42", "status": "SHIPPED", "total": 120.5,
                   "items": [{"sku": "A-1", "qty": 2}, {"sku": "B-7", "qty": 1}]}}
        """;

    private static TopicSearchRequest text(String query, String mode, boolean caseSensitive, boolean searchKey) {
        return new TopicSearchRequest(query, mode, caseSensitive, searchKey, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    private static TopicSearchRequest field(String path, String operator, String value) {
        return new TopicSearchRequest(null, "FIELD", null, null, path, operator, value,
            null, null, null, null, null, null, null, null, null);
    }

    @Test
    void textSearchIsCaseInsensitiveByDefault() {
        MessageMatcher matcher = MessageMatcher.from(text("shipped", "CONTAINS", false, true));

        assertTrue(matcher.matches(null, ORDER));
        assertFalse(matcher.matches(null, "{\"status\": \"PENDING\"}"));
    }

    @Test
    void caseSensitiveTextSearchRespectsCase() {
        MessageMatcher matcher = MessageMatcher.from(text("shipped", "CONTAINS", true, true));

        assertFalse(matcher.matches(null, ORDER));
        assertTrue(matcher.matches(null, "{\"status\": \"shipped\"}"));
    }

    @Test
    void textSearchAlsoMatchesTheRecordKey() {
        MessageMatcher withKey = MessageMatcher.from(text("cust-9", "CONTAINS", false, true));
        MessageMatcher withoutKey = MessageMatcher.from(text("cust-9", "CONTAINS", false, false));

        assertTrue(withKey.matches("CUST-9", "{}"));
        assertFalse(withoutKey.matches("CUST-9", "{}"));
    }

    @Test
    void regexSearchFindsPatterns() {
        MessageMatcher matcher = MessageMatcher.from(text("ORD-\\d+", "REGEX", true, false));

        assertTrue(matcher.matches(null, ORDER));
        assertFalse(matcher.matches(null, "{\"order\": {\"id\": \"REF-X\"}}"));
    }

    @Test
    void invalidRegexIsReportedRatherThanSwallowed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> MessageMatcher.from(text("[unclosed", "REGEX", false, false)));

        assertTrue(error.getMessage().toLowerCase().contains("regular expression"));
    }

    @Test
    void fieldSearchWithoutPathIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> MessageMatcher.from(field(null, "EQ", "x")));
    }

    @Test
    void fieldEqualityUsesTheNestedPath() {
        assertTrue(MessageMatcher.from(field("order.status", "EQ", "SHIPPED")).matches(null, ORDER));
        assertFalse(MessageMatcher.from(field("order.status", "EQ", "PENDING")).matches(null, ORDER));
        // A field that exists elsewhere in the document must not match through the wrong path
        assertFalse(MessageMatcher.from(field("status", "EQ", "SHIPPED")).matches(null, ORDER));
    }

    @Test
    void fieldSearchMatchesAnyArrayElement() {
        assertTrue(MessageMatcher.from(field("order.items[].sku", "EQ", "B-7")).matches(null, ORDER));
        assertFalse(MessageMatcher.from(field("order.items[].sku", "EQ", "C-9")).matches(null, ORDER));
    }

    @Test
    void jsonPathSyntaxIsAccepted() {
        assertTrue(MessageMatcher.from(field("$.order.items[0].sku", "EQ", "A-1")).matches(null, ORDER));
    }

    @Test
    void numericComparisonsWorkOnNumbers() {
        assertTrue(MessageMatcher.from(field("order.total", "GT", "100")).matches(null, ORDER));
        assertFalse(MessageMatcher.from(field("order.total", "GT", "500")).matches(null, ORDER));
        assertTrue(MessageMatcher.from(field("order.total", "LTE", "120.5")).matches(null, ORDER));
        // A non-numeric field can never satisfy a numeric comparison
        assertFalse(MessageMatcher.from(field("order.status", "GT", "1")).matches(null, ORDER));
    }

    @Test
    void existsOperatorIgnoresTheValue() {
        assertTrue(MessageMatcher.from(field("order.id", "EXISTS", null)).matches(null, ORDER));
        assertFalse(MessageMatcher.from(field("order.missing", "EXISTS", null)).matches(null, ORDER));
    }

    @Test
    void containsOperatorMatchesSubstringsOfTheField() {
        assertTrue(MessageMatcher.from(field("order.id", "CONTAINS", "ord-")).matches(null, ORDER));
    }

    @Test
    void fieldSearchWalksXmlPayloads() {
        String xml = "<order><id>ORD-42</id><customer><name>Ada</name></customer></order>";

        assertTrue(MessageMatcher.from(field("order.customer.name", "EQ", "Ada")).matches(null, xml));
        assertFalse(MessageMatcher.from(field("order.customer.name", "EQ", "Bob")).matches(null, xml));
    }

    @Test
    void malformedPayloadDoesNotMatchAndDoesNotThrow() {
        MessageMatcher matcher = MessageMatcher.from(field("order.id", "EQ", "ORD-42"));

        assertFalse(matcher.matches(null, "{\"order\": {\"id\": "));
        assertFalse(matcher.matches(null, "not json at all"));
        assertFalse(matcher.matches(null, null));
    }

    @Test
    void emptyQueryMatchesEverythingSoTheScanCanSkipTheWork() {
        MessageMatcher matcher = MessageMatcher.from(text("", "CONTAINS", false, true));

        assertTrue(matcher.matchesEverything());
        assertTrue(matcher.matches(null, ORDER));
    }
}
