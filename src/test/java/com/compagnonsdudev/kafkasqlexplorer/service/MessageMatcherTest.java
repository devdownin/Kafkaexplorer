// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageMatcherTest {

    private static final String ORDER = """
        {"order": {"id": "ORD-42", "status": "SHIPPED", "total": 120.5,
                   "items": [{"sku": "A-1", "qty": 2}, {"sku": "B-7", "qty": 1}]}}
        """;

    private static TopicSearchRequest text(String query, String mode, boolean caseSensitive, boolean searchKey) {
        return new TopicSearchRequest(query, mode, caseSensitive, searchKey, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    private static TopicSearchRequest field(String path, String operator, String value) {
        return new TopicSearchRequest(null, "FIELD", null, null, null, null, path, operator, value,
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

    // ── Record key ──────────────────────────────────────────────────────────

    private static TopicSearchRequest key(String operator, String value) {
        return new TopicSearchRequest(null, "KEY", null, null, null, null, null, operator, value,
            null, null, null, null, null, null, null, null, null);
    }

    /** A text search finds the key as a substring anywhere; KEY mode compares the whole key. */
    @Test
    void keyModeComparesTheWholeKey() {
        assertTrue(MessageMatcher.from(key("EQ", "ORD-42")).matches("ORD-42", "{}"));
        assertFalse(MessageMatcher.from(key("EQ", "ORD-4")).matches("ORD-42", "{}"),
            "a prefix is not the key");
        assertFalse(MessageMatcher.from(key("EQ", "ORD-42")).matches(null, "{\"id\": \"ORD-42\"}"),
            "the payload is not the key");
    }

    @Test
    void keyModeSupportsTheUsualOperators() {
        assertTrue(MessageMatcher.from(key("CONTAINS", "ORD")).matches("ORD-42", "{}"));
        assertTrue(MessageMatcher.from(key("REGEX", "ORD-\\d+")).matches("ORD-42", "{}"));
        assertTrue(MessageMatcher.from(key("NEQ", "OTHER")).matches("ORD-42", "{}"));
        assertTrue(MessageMatcher.from(key("EXISTS", null)).matches("ORD-42", "{}"));
        assertFalse(MessageMatcher.from(key("EXISTS", null)).matches(null, "{}"));
    }

    @Test
    void keyModeIsCaseInsensitiveByDefaultLikeTheRest() {
        assertTrue(MessageMatcher.from(key("EQ", "ord-42")).matches("ORD-42", "{}"));
    }

    @Test
    void keySearchWithoutAValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MessageMatcher.from(key("EQ", " ")));
    }

    // ── Kafka headers ───────────────────────────────────────────────────────

    private static final Map<String, String> HEADERS = Map.of(
        "correlation-id", "abc-123",
        "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    private static TopicSearchRequest textWithHeaders(String query, String mode, boolean searchHeaders) {
        return new TopicSearchRequest(query, mode, null, true, searchHeaders, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    private static TopicSearchRequest header(String name, String operator, String value) {
        return new TopicSearchRequest(null, "HEADER", null, null, null, null, name, operator, value,
            null, null, null, null, null, null, null, null, null);
    }

    @Test
    void headersAreOnlySearchedWhenAskedFor() {
        MessageMatcher off = MessageMatcher.from(textWithHeaders("abc-123", "CONTAINS", false));
        MessageMatcher on = MessageMatcher.from(textWithHeaders("abc-123", "CONTAINS", true));

        assertFalse(off.matches(null, "{}", HEADERS), "a topic search must keep its meaning");
        assertTrue(on.matches(null, "{}", HEADERS));
        assertFalse(off.needsHeaders());
        assertTrue(on.needsHeaders());
    }

    @Test
    void regexSearchAlsoReachesHeaderValues() {
        MessageMatcher matcher = MessageMatcher.from(textWithHeaders("4bf92f\\w+", "REGEX", true));

        assertTrue(matcher.matches(null, "{}", HEADERS));
        assertFalse(matcher.matches(null, "{}", Map.of("other", "nothing")));
    }

    @Test
    void headerModeComparesOneNamedHeader() {
        assertTrue(MessageMatcher.from(header("correlation-id", "EQ", "abc-123")).matches(null, "{}", HEADERS));
        assertFalse(MessageMatcher.from(header("correlation-id", "EQ", "zzz")).matches(null, "{}", HEADERS));
        // The id is embedded in a larger tuple — CONTAINS is what a trace needs.
        assertTrue(MessageMatcher.from(header("traceparent", "CONTAINS", "00f067aa0ba902b7"))
            .matches(null, "{}", HEADERS));
    }

    /** The same id travels as correlation-id or Correlation-Id depending on the producer. */
    @Test
    void headerNamesAreMatchedCaseInsensitively() {
        assertTrue(MessageMatcher.from(header("Correlation-Id", "EQ", "abc-123")).matches(null, "{}", HEADERS));
    }

    @Test
    void headerExistsIgnoresTheValue() {
        assertTrue(MessageMatcher.from(header("traceparent", "EXISTS", null)).matches(null, "{}", HEADERS));
        assertFalse(MessageMatcher.from(header("missing", "EXISTS", null)).matches(null, "{}", HEADERS));
        assertFalse(MessageMatcher.from(header("traceparent", "EXISTS", null)).matches(null, "{}", Map.of()));
    }

    @Test
    void headerSearchWithoutANameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> MessageMatcher.from(header(" ", "EQ", "x")));
    }

    // ── XPath ───────────────────────────────────────────────────────────────

    private static final String XML_ORDER =
        "<order><item><id>A-1</id></item><item><id>A-2</id></item></order>";

    private static TopicSearchRequest xpath(String expression, String operator, String value) {
        return new TopicSearchRequest(null, "XPATH", null, null, null, null, expression, operator, value,
            null, null, null, null, null, null, null, null, null);
    }

    /** Evaluated as a NODESET: a match on the second element used to be invisible. */
    @Test
    void xpathMatchesBeyondTheFirstNode() {
        assertTrue(MessageMatcher.from(xpath("/order/item/id", "EQ", "A-2")).matches(null, XML_ORDER));
        assertFalse(MessageMatcher.from(xpath("/order/item/id", "EQ", "A-9")).matches(null, XML_ORDER));
    }

    @Test
    void xpathSupportsPredicatesTheDotPathCannotExpress() {
        String xml = "<order><item type=\"gift\"><id>G-1</id></item><item><id>N-2</id></item></order>";

        assertTrue(MessageMatcher.from(xpath("/order/item[@type='gift']/id", "EQ", "G-1")).matches(null, xml));
        assertFalse(MessageMatcher.from(xpath("/order/item[@type='gift']/id", "EQ", "N-2")).matches(null, xml));
    }

    @Test
    void invalidXPathIsReportedRatherThanSwallowed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> MessageMatcher.from(xpath("/order[", "EQ", "x")));

        assertTrue(error.getMessage().contains("XPath"), error.getMessage());
    }

    // ── Full JSONPath ───────────────────────────────────────────────────────

    private static TopicSearchRequest jsonPath(String expression, String operator, String value) {
        return new TopicSearchRequest(null, "JSONPATH", null, null, null, null, expression, operator, value,
            null, null, null, null, null, null, null, null, null);
    }

    /** What the dot-notation walker cannot express: recursive descent and filters. */
    @Test
    void jsonPathHandlesRecursiveDescent() {
        assertTrue(MessageMatcher.from(jsonPath("$..sku", "EQ", "B-7")).matches(null, ORDER));
        assertFalse(MessageMatcher.from(jsonPath("$..sku", "EQ", "C-9")).matches(null, ORDER));
    }

    @Test
    void jsonPathHandlesFilters() {
        assertTrue(MessageMatcher.from(jsonPath("$.order.items[?(@.qty > 1)].sku", "EQ", "A-1"))
            .matches(null, ORDER));
        assertFalse(MessageMatcher.from(jsonPath("$.order.items[?(@.qty > 1)].sku", "EQ", "B-7"))
            .matches(null, ORDER));
    }

    @Test
    void jsonPathExpectsJsonAndReportsWhatItGot() {
        MessageMatcher matcher = MessageMatcher.from(jsonPath("$..id", "EQ", "A-1"));

        assertEquals("JSON", matcher.expectedFormat());
        assertEquals(MessageMatcher.MatchOutcome.FORMAT_MISMATCH,
            matcher.evaluate(null, "<order><id>A-1</id></order>", null));
        assertEquals(MessageMatcher.MatchOutcome.PARSE_ERROR,
            matcher.evaluate(null, "{\"order\": {\"id\": ", null));
    }

    @Test
    void invalidJsonPathIsReportedRatherThanSwallowed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> MessageMatcher.from(jsonPath("$.[", "EQ", "x")));

        assertTrue(error.getMessage().contains("JSONPath"), error.getMessage());
    }

    // ── Why a path search did not apply ─────────────────────────────────────

    @Test
    void aPathSearchTellsFormatMismatchApartFromAMiss() {
        MessageMatcher matcher = MessageMatcher.from(field("order.id", "EQ", "ORD-42"));

        assertEquals(MessageMatcher.MatchOutcome.MATCH, matcher.evaluate(null, ORDER, null));
        assertEquals(MessageMatcher.MatchOutcome.NO_MATCH,
            matcher.evaluate(null, "{\"order\": {\"id\": \"OTHER\"}}", null));
        assertEquals(MessageMatcher.MatchOutcome.FORMAT_MISMATCH,
            matcher.evaluate(null, "plain text mentioning ORD-42", null),
            "a raw-text payload is not a miss, the path could not be applied at all");
        assertEquals(MessageMatcher.MatchOutcome.PARSE_ERROR,
            matcher.evaluate(null, "{\"order\": {\"id\": ", null));
    }

    @Test
    void anXPathSearchExpectsXml() {
        MessageMatcher matcher = MessageMatcher.from(xpath("/order/id", "EQ", "A-1"));

        assertEquals("XML", matcher.expectedFormat());
        assertEquals(MessageMatcher.MatchOutcome.FORMAT_MISMATCH,
            matcher.evaluate(null, "{\"order\": {\"id\": \"A-1\"}}", null));
        assertEquals(MessageMatcher.MatchOutcome.PARSE_ERROR,
            matcher.evaluate(null, "<order><id>A-1</id>", null));
    }

    /** A raw text or regex search is never path-scoped, so it has no format expectation. */
    @Test
    void aTextSearchIsNeverPathScoped() {
        assertFalse(MessageMatcher.from(text("x", "CONTAINS", false, true)).isPathScoped());
        assertTrue(MessageMatcher.from(field("a.b", "EQ", "x")).isPathScoped());
        assertTrue(MessageMatcher.from(xpath("/a/b", "EQ", "x")).isPathScoped());
    }
}
