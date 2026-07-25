// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether one record matches a search. Built once per search and reused for every scanned
 * record, so compiling a regex or normalizing a path happens once, not per message.
 *
 * <p>Text and regex modes work on the raw value — no parsing at all. Field mode walks the payload
 * with a streaming parser and stops at the first match, so a 1 MB document costs a partial scan
 * rather than a full object tree.</p>
 */
public final class MessageMatcher {

    /** Values at a path are collected up to this many before the walk gives up (repeated arrays). */
    private static final int MAX_PATH_MATCHES = 32;

    private final String mode;
    private final String operator;
    private final boolean caseSensitive;
    private final boolean searchKey;
    private final String needle;
    private final Pattern pattern;
    private final String fieldPath;
    private final String comparand;
    private final Double numericComparand;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final XMLInputFactory xmlInputFactory = secureXmlInputFactory();

    private MessageMatcher(String mode, String operator, boolean caseSensitive, boolean searchKey,
                            String needle, Pattern pattern, String fieldPath, String comparand) {
        this.mode = mode;
        this.operator = operator;
        this.caseSensitive = caseSensitive;
        this.searchKey = searchKey;
        this.needle = needle;
        this.pattern = pattern;
        this.fieldPath = fieldPath;
        this.comparand = comparand;
        this.numericComparand = parseNumber(comparand);
    }

    /**
     * @throws IllegalArgumentException on an invalid regex or a FIELD search with no path — the
     *         caller turns this into a 400 so the user sees what is wrong with their query
     */
    public static MessageMatcher from(TopicSearchRequest request) {
        String mode = request.resolvedMode();
        String operator = request.resolvedOperator();
        boolean caseSensitive = request.isCaseSensitive();

        if ("FIELD".equals(mode)) {
            if (request.field() == null || request.field().isBlank()) {
                throw new IllegalArgumentException("A field path is required for a FIELD search.");
            }
            Pattern fieldPattern = "REGEX".equals(operator)
                ? compile(request.value(), caseSensitive)
                : null;
            return new MessageMatcher(mode, operator, caseSensitive, false, null, fieldPattern,
                PayloadDigestService.normalizePath(request.field()), request.value());
        }

        String query = request.query() == null ? "" : request.query();
        Pattern compiled = "REGEX".equals(mode) ? compile(query, caseSensitive) : null;
        return new MessageMatcher(mode, operator, caseSensitive, request.isSearchKey(),
            caseSensitive ? query : query.toLowerCase(Locale.ROOT), compiled, null, null);
    }

    /** True when this matcher accepts everything — lets the scan skip the work entirely. */
    public boolean matchesEverything() {
        return !"FIELD".equals(mode) && (needle == null || needle.isEmpty()) && pattern == null;
    }

    public boolean matches(String key, String value) {
        if (matchesEverything()) {
            return true;
        }
        if ("FIELD".equals(mode)) {
            return matchesField(value);
        }
        if ("REGEX".equals(mode)) {
            return (value != null && pattern.matcher(value).find())
                || (searchKey && key != null && pattern.matcher(key).find());
        }
        return containsNeedle(value) || (searchKey && containsNeedle(key));
    }

    private boolean containsNeedle(String candidate) {
        if (candidate == null) {
            return false;
        }
        return caseSensitive
            ? candidate.contains(needle)
            : candidate.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean matchesField(String value) {
        List<String> values = extractPathValues(value, fieldPath);
        if ("EXISTS".equals(operator)) {
            return !values.isEmpty();
        }
        if (values.isEmpty()) {
            return false;
        }
        for (String candidate : values) {
            if (compare(candidate)) {
                return true;
            }
        }
        return false;
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
