// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether one record matches a search. Built once per search and reused for every scanned
 * record, so compiling a regex or normalizing a path happens once, not per message.
 *
 * <p>Text and regex modes work on the raw value — no parsing at all. Field mode walks the payload
 * with a streaming parser and stops at the first match, so a 1 MB document costs a partial scan
 * rather than a full object tree. {@code XPATH} and {@code JSONPATH} are the exceptions: they hand
 * the payload to a full expression engine (DOM for XPath, Jayway for JSONPath) because they exist
 * precisely for what the dot-notation path cannot express — predicates, axes, recursive descent.
 * The syntax the caller typed decides which one runs, so a simple path never pays for the general
 * engine.</p>
 *
 * <p>{@code KEY} mode compares the Kafka record key itself with the usual operators. A text search
 * already looks at the key, but only as a substring anywhere; an exact key comparison is both what
 * "find record X" means and what makes it safe to scan a single partition (see
 * {@code TopicSearchService}).</p>
 *
 * <p>Kafka <strong>headers</strong> are searchable: {@code HEADER} mode compares one named header
 * with the usual operators, and {@code searchHeaders} widens a text or regex search to every header
 * value. Correlation ids very often travel there ({@code correlation-id}, W3C {@code traceparent})
 * rather than in the key or the payload, and a search that never looks at them reports a confident
 * "not found".</p>
 *
 * <p><strong>Not thread-safe</strong>: the XML factory, the DocumentBuilder and the compiled
 * XPathExpression it holds are not. Build one matcher per scanning thread — the cost is one regex
 * compilation, not one per record, which is the point.</p>
 */
public final class MessageMatcher {

    /** Values at a path are collected up to this many before the walk gives up (repeated arrays). */
    private static final int MAX_PATH_MATCHES = 32;

    /**
     * Path evaluation must not cost an exception per record: {@code SUPPRESS_EXCEPTIONS} turns a
     * missing path into a null result instead of a thrown {@code PathNotFoundException}.
     */
    private static final Configuration JSON_PATH_CONFIG =
        Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);

    /**
     * Why a record did or did not match.
     *
     * <p>A bare boolean hid the two cases that explain an empty search: the payload was not in the
     * format the path expects, or it could not be parsed at all. Callers that report coverage
     * (stream flow, topic search) turn these into warnings instead of a silent zero.
     */
    public enum MatchOutcome { MATCH, NO_MATCH, FORMAT_MISMATCH, PARSE_ERROR }

    private final String mode;
    private final String operator;
    private final boolean caseSensitive;
    private final boolean searchKey;
    private final boolean searchHeaders;
    private final String needle;
    private final Pattern pattern;
    private final String fieldPath;
    private final String comparand;
    private final Double numericComparand;
    private final XPathExpression xpath;
    private final JsonPath jsonPath;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final XMLInputFactory xmlInputFactory = secureXmlInputFactory();
    private DocumentBuilder documentBuilder;

    private MessageMatcher(String mode, String operator, boolean caseSensitive, boolean searchKey,
                            boolean searchHeaders, String needle, Pattern pattern, String fieldPath,
                            String comparand, XPathExpression xpath, JsonPath jsonPath) {
        this.mode = mode;
        this.operator = operator;
        this.caseSensitive = caseSensitive;
        this.searchKey = searchKey;
        this.searchHeaders = searchHeaders;
        this.needle = needle;
        this.pattern = pattern;
        this.fieldPath = fieldPath;
        this.comparand = comparand;
        this.numericComparand = parseNumber(comparand);
        this.xpath = xpath;
        this.jsonPath = jsonPath;
    }

    /**
     * @throws IllegalArgumentException on an invalid regex, a FIELD / HEADER search with no path,
     *         or a malformed XPath — the caller turns this into a 400 so the user sees what is
     *         wrong with their query
     */
    public static MessageMatcher from(TopicSearchRequest request) {
        String mode = request.resolvedMode();
        String operator = request.resolvedOperator();
        boolean caseSensitive = request.isCaseSensitive();
        boolean searchHeaders = request.isSearchHeaders();

        if ("KEY".equals(mode)) {
            if (!"EXISTS".equals(operator) && (request.value() == null || request.value().isBlank())) {
                throw new IllegalArgumentException("A value is required for a KEY search.");
            }
            Pattern keyPattern = "REGEX".equals(operator) ? compile(request.value(), caseSensitive) : null;
            return new MessageMatcher(mode, operator, caseSensitive, true, searchHeaders, null,
                keyPattern, null, request.value(), null, null);
        }

        if ("FIELD".equals(mode) || "XPATH".equals(mode) || "JSONPATH".equals(mode)
            || "HEADER".equals(mode)) {
            if (request.field() == null || request.field().isBlank()) {
                throw new IllegalArgumentException(
                    "A " + ("HEADER".equals(mode) ? "header name" : "field path")
                        + " is required for a " + mode + " search.");
            }
            Pattern fieldPattern = "REGEX".equals(operator)
                ? compile(request.value(), caseSensitive)
                : null;
            String path = "FIELD".equals(mode)
                ? PayloadDigestService.normalizePath(request.field())
                : request.field().trim();
            XPathExpression expression = "XPATH".equals(mode) ? compileXPath(path) : null;
            JsonPath jsonPath = "JSONPATH".equals(mode) ? compileJsonPath(path) : null;
            return new MessageMatcher(mode, operator, caseSensitive, false, searchHeaders, null,
                fieldPattern, path, request.value(), expression, jsonPath);
        }

        String query = request.query() == null ? "" : request.query();
        Pattern compiled = "REGEX".equals(mode) ? compile(query, caseSensitive) : null;
        return new MessageMatcher(mode, operator, caseSensitive, request.isSearchKey(), searchHeaders,
            caseSensitive ? query : query.toLowerCase(Locale.ROOT), compiled, null, null, null, null);
    }

    /** True when this matcher accepts everything — lets the scan skip the work entirely. */
    public boolean matchesEverything() {
        return !isPathScoped() && !"HEADER".equals(mode) && !"KEY".equals(mode)
            && (needle == null || needle.isEmpty()) && pattern == null;
    }

    /** True when only what the path extracts decides — the raw value is never consulted. */
    public boolean isPathScoped() {
        return "FIELD".equals(mode) || "XPATH".equals(mode) || "JSONPATH".equals(mode);
    }

    /** The payload format {@link MatchOutcome#FORMAT_MISMATCH} refers to, for warning wording. */
    public String expectedFormat() {
        return switch (mode) {
            case "XPATH" -> "XML";
            case "JSONPATH" -> "JSON";
            default -> "JSON or XML";
        };
    }

    /** True when the decision reads Kafka headers — the scan only builds the map when it does. */
    public boolean needsHeaders() {
        return searchHeaders || "HEADER".equals(mode);
    }

    public boolean matches(String key, String value) {
        return matches(key, value, null);
    }

    public boolean matches(String key, String value, Map<String, String> headers) {
        return evaluate(key, value, headers) == MatchOutcome.MATCH;
    }

    /** Same decision as {@link #matches}, keeping why a path-scoped search did not apply. */
    public MatchOutcome evaluate(String key, String value, Map<String, String> headers) {
        if (matchesEverything()) {
            return MatchOutcome.MATCH;
        }
        if ("KEY".equals(mode)) {
            if ("EXISTS".equals(operator)) {
                return key != null ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
            }
            return key != null && compare(key) ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
        }
        if ("HEADER".equals(mode)) {
            return matchesHeader(headers);
        }
        if (isPathScoped()) {
            return matchesPath(value);
        }
        if ("REGEX".equals(mode)) {
            boolean hit = (value != null && pattern.matcher(value).find())
                || (searchKey && key != null && pattern.matcher(key).find())
                || (searchHeaders && anyHeader(headers, h -> pattern.matcher(h).find()));
            return hit ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
        }
        boolean hit = containsNeedle(value) || (searchKey && containsNeedle(key))
            || (searchHeaders && anyHeader(headers, this::containsNeedle));
        return hit ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
    }

    private boolean anyHeader(Map<String, String> headers, java.util.function.Predicate<String> test) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (String headerValue : headers.values()) {
            if (headerValue != null && test.test(headerValue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Header names are matched exactly first, then case-insensitively: the same id travels as
     * {@code correlation-id} and {@code Correlation-Id} depending on the producing framework.
     */
    private MatchOutcome matchesHeader(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "EXISTS".equals(operator) ? MatchOutcome.NO_MATCH : MatchOutcome.NO_MATCH;
        }
        String found = headers.get(fieldPath);
        if (found == null && !headers.containsKey(fieldPath)) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(fieldPath)) {
                    found = entry.getValue();
                    break;
                }
            }
        }
        if ("EXISTS".equals(operator)) {
            return found != null ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
        }
        if (found == null) {
            return MatchOutcome.NO_MATCH;
        }
        return compare(found) ? MatchOutcome.MATCH : MatchOutcome.NO_MATCH;
    }

    private boolean containsNeedle(String candidate) {
        if (candidate == null) {
            return false;
        }
        return caseSensitive
            ? candidate.contains(needle)
            : candidate.toLowerCase(Locale.ROOT).contains(needle);
    }

    private MatchOutcome matchesPath(String value) {
        Extraction extraction = extract(value);
        if (extraction.outcome() != null) {
            return extraction.outcome();
        }
        List<String> values = extraction.values();
        if ("EXISTS".equals(operator)) {
            return values.isEmpty() ? MatchOutcome.NO_MATCH : MatchOutcome.MATCH;
        }
        for (String candidate : values) {
            if (compare(candidate)) {
                return MatchOutcome.MATCH;
            }
        }
        return MatchOutcome.NO_MATCH;
    }

    /** Values found at the path, or the reason the path could not be applied to this payload. */
    private record Extraction(List<String> values, MatchOutcome outcome) {
        static Extraction of(List<String> values) {
            return new Extraction(values, null);
        }

        static Extraction failed(MatchOutcome outcome) {
            return new Extraction(List.of(), outcome);
        }
    }

    private Extraction extract(String payload) {
        if (payload == null || payload.isBlank()) {
            return Extraction.failed(MatchOutcome.FORMAT_MISMATCH);
        }
        String trimmed = payload.stripLeading();
        boolean looksJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean looksXml = trimmed.startsWith("<");

        if ("XPATH".equals(mode)) {
            if (!looksXml) {
                return Extraction.failed(MatchOutcome.FORMAT_MISMATCH);
            }
            return evaluateXPath(payload);
        }
        if ("JSONPATH".equals(mode)) {
            if (!looksJson) {
                return Extraction.failed(MatchOutcome.FORMAT_MISMATCH);
            }
            return evaluateJsonPath(payload);
        }
        if (!looksJson && !looksXml) {
            return Extraction.failed(MatchOutcome.FORMAT_MISMATCH);
        }
        List<String> found = new ArrayList<>();
        try {
            if (looksJson) {
                try (JsonParser parser = jsonFactory.createParser(payload)) {
                    if (parser.nextToken() != null) {
                        walkJson(parser, "", fieldPath, found);
                    }
                }
            } else {
                walkXml(payload, fieldPath, found);
            }
        } catch (Exception e) {
            // A malformed payload simply doesn't match; the scan must not fail because one
            // record out of a million is truncated garbage. It is reported, not swallowed.
            return Extraction.failed(MatchOutcome.PARSE_ERROR);
        }
        return Extraction.of(found);
    }

    /**
     * XPath needs a document tree, so this mode is the one place a payload is fully parsed. The
     * expression is evaluated as a NODESET — evaluating as a string keeps only the first matching
     * node, so a value carried by the second {@code <item>} of a document went unseen.
     */
    private Extraction evaluateXPath(String payload) {
        Document document;
        try {
            document = documentBuilder().parse(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Extraction.failed(MatchOutcome.PARSE_ERROR);
        }
        List<String> found = new ArrayList<>();
        try {
            Object nodes = xpath.evaluate(document, XPathConstants.NODESET);
            if (nodes instanceof NodeList list) {
                for (int i = 0; i < list.getLength() && found.size() < MAX_PATH_MATCHES; i++) {
                    String text = list.item(i).getTextContent();
                    found.add(text == null ? null : text.trim());
                }
                return Extraction.of(found);
            }
        } catch (Exception ignored) {
            // Expression returns a scalar (string(), count(), concat()…): read it as a string.
        }
        try {
            Object scalar = xpath.evaluate(document, XPathConstants.STRING);
            if (scalar != null) {
                found.add(String.valueOf(scalar));
            }
        } catch (Exception e) {
            return Extraction.of(List.of());
        }
        return Extraction.of(found);
    }

    private DocumentBuilder documentBuilder() throws Exception {
        if (documentBuilder == null) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            documentBuilder = factory.newDocumentBuilder();
        } else {
            documentBuilder.reset();
        }
        return documentBuilder;
    }

    private boolean compare(String candidate) {
        switch (operator) {
            case "NEQ":
                return !equalsValue(candidate);
            case "CONTAINS":
                return candidate != null && comparand != null && (caseSensitive
                    ? candidate.contains(comparand)
                    : candidate.toLowerCase(Locale.ROOT).contains(comparand.toLowerCase(Locale.ROOT)));
            case "REGEX":
                return candidate != null && pattern.matcher(candidate).find();
            case "GT":
            case "GTE":
            case "LT":
            case "LTE": {
                Double left = parseNumber(candidate);
                if (left == null || numericComparand == null) {
                    return false;
                }
                int cmp = Double.compare(left, numericComparand);
                return switch (operator) {
                    case "GT" -> cmp > 0;
                    case "GTE" -> cmp >= 0;
                    case "LT" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
            case "EXISTS":
                return true;
            default:
                return equalsValue(candidate);
        }
    }

    private boolean equalsValue(String candidate) {
        if (candidate == null || comparand == null) {
            return candidate == null && comparand == null;
        }
        return caseSensitive ? candidate.equals(comparand) : candidate.equalsIgnoreCase(comparand);
    }

    /**
     * Scalar values found at {@code path}. Array indices in the payload are collapsed to
     * {@code []}, so {@code items[].sku} matches every element without the caller enumerating them.
     */
    List<String> extractPathValues(String payload, String path) {
        List<String> found = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return found;
        }
        String trimmed = payload.stripLeading();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try (JsonParser parser = jsonFactory.createParser(payload)) {
                    if (parser.nextToken() != null) {
                        walkJson(parser, "", path, found);
                    }
                }
            } else if (trimmed.startsWith("<")) {
                walkXml(payload, path, found);
            }
        } catch (Exception e) {
            // A malformed payload simply doesn't match; the scan must not fail because one
            // record out of a million is truncated garbage.
            return found;
        }
        return found;
    }

    /**
     * Full JSONPath, for the expressions the dot-notation walker cannot express: recursive descent
     * ({@code $..id}), filters ({@code $.items[?(@.qty > 1)]}), object wildcards. A simple path
     * stays on the streaming walker, which prunes subtrees instead of building the whole document.
     */
    private Extraction evaluateJsonPath(String payload) {
        Object result;
        try {
            result = jsonPath.read(payload, JSON_PATH_CONFIG);
        } catch (Exception e) {
            return Extraction.failed(MatchOutcome.PARSE_ERROR);
        }
        if (result == null) {
            return Extraction.of(List.of());
        }
        List<String> found = new ArrayList<>();
        if (result instanceof Iterable<?> values) {
            // An indefinite path yields a list; testing String.valueOf(list) would compare against
            // the rendering of the whole collection, brackets and commas included.
            for (Object element : values) {
                if (found.size() >= MAX_PATH_MATCHES) break;
                found.add(element == null ? null : String.valueOf(element));
            }
        } else {
            found.add(String.valueOf(result));
        }
        return Extraction.of(found);
    }

    private static JsonPath compileJsonPath(String expression) {
        try {
            return JsonPath.compile(expression);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                "Invalid JSONPath \"" + expression + "\": " + e.getMessage());
        }
    }

    private static XPathExpression compileXPath(String expression) {
        try {
            return XPathFactory.newInstance().newXPath().compile(expression);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XPath \"" + expression + "\": " + e.getMessage());
        }
    }

    private void walkJson(JsonParser parser, String currentPath, String target, List<String> found)
            throws java.io.IOException {
        if (found.size() >= MAX_PATH_MATCHES) {
            return;
        }
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                String childPath = currentPath.isEmpty() ? name : currentPath + "." + name;
                parser.nextToken();
                // Prune: a subtree whose path can no longer become the target is skipped whole.
                if (!isOnPath(childPath, target)) {
                    parser.skipChildren();
                    continue;
                }
                walkJson(parser, childPath, target, found);
            }
            return;
        }
        if (token == JsonToken.START_ARRAY) {
            String arrayPath = currentPath + "[]";
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (isOnPath(arrayPath, target)) {
                    walkJson(parser, arrayPath, target, found);
                } else {
                    parser.skipChildren();
                }
            }
            return;
        }
        if (currentPath.equals(target)) {
            found.add(token == JsonToken.VALUE_NULL ? null : parser.getValueAsString());
        }
    }

    private void walkXml(String payload, String target, List<String> found) throws Exception {
        XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(new StringReader(payload));
        try {
            List<String> stack = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            while (reader.hasNext() && found.size() < MAX_PATH_MATCHES) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    stack.add(reader.getLocalName());
                    text.setLength(0);
                } else if (event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && !stack.isEmpty()) {
                    String path = String.join(".", stack);
                    if (path.equals(target)) {
                        found.add(text.toString().trim());
                    }
                    stack.remove(stack.size() - 1);
                    text.setLength(0);
                }
            }
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /** True when {@code path} is the target or a prefix of it — anything else can be skipped. */
    private static boolean isOnPath(String path, String target) {
        if (target.startsWith(path)) {
            if (target.length() == path.length()) {
                return true;
            }
            char next = target.charAt(path.length());
            return next == '.' || next == '[';
        }
        return false;
    }

    private static Pattern compile(String regex, boolean caseSensitive) {
        try {
            return Pattern.compile(regex == null ? "" : regex,
                caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
        }
    }

    private static Double parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static XMLInputFactory secureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        return factory;
    }
}
