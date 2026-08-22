// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadShape;
import com.compagnonsdudev.kafkasqlexplorer.parser.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw Kafka payload into a {@link PayloadDigest}: bounded in size, safe to buffer, and
 * small enough to inline in an LLM prompt.
 *
 * <p>Everything is done with streaming parsers ({@link JsonParser} / {@link XMLStreamReader}) —
 * a 1 MB, ten-level document is never materialized as a tree or, for the byte[] path, even as a
 * String. Subtrees that exceed the configured depth, and array elements past the sample size,
 * are skipped by the parser rather than walked, so the cost is bounded by the token count and
 * the allocations by the digest itself.</p>
 *
 * <p>Structures are deduplicated: the leaf-path set (array indices collapsed to {@code []}) is
 * hashed into a {@link PayloadShape} id, so a window full of documents that share a schema
 * contributes that schema to the prompt once.</p>
 */
@Service
public class PayloadDigestService {

    private static final Logger log = LoggerFactory.getLogger(PayloadDigestService.class);

    public static final String FORMAT_JSON = "JSON";
    public static final String FORMAT_XML = "XML";
    public static final String FORMAT_TEXT = "TEXT";
    public static final String FORMAT_EMPTY = "EMPTY";

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final ProcessMiningConfig config;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final XMLInputFactory xmlInputFactory = SecureXml.inputFactory();

    /** shape id → shape, LRU-bounded: shapes seen in earlier windows stay resolvable for the prompt. */
    private final Map<String, PayloadShape> shapeRegistry;

    public PayloadDigestService(ProcessMiningConfig config) {
        this.config = config;
        int cacheSize = Math.max(16, config.getShapeCacheSize());
        this.shapeRegistry = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PayloadShape> eldest) {
                    return size() > cacheSize;
                }
            });
    }

    /** Digests a record straight from the wire, without ever building a String of the payload. */
    public PayloadDigest digest(String topic, int partition, long offset, long timestamp,
                                 String key, byte[] value, Set<String> mappedPaths) {
        return digest(topic, partition, offset, timestamp, key, value, mappedPaths,
            config.getMaxSampleFields());
    }

    /**
     * @param sampleFieldLimit how many non-mapped scalar values to keep. Field profiling wants a
     *                         wide view of the document; live analysis only needs a few examples.
     */
    public PayloadDigest digest(String topic, int partition, long offset, long timestamp,
                                 String key, byte[] value, Set<String> mappedPaths,
                                 int sampleFieldLimit) {
        int payloadBytes = value == null ? 0 : value.length;
        if (payloadBytes == 0) {
            return empty(topic, partition, offset, timestamp, key, 0);
        }

        int parseLimit = Math.min(payloadBytes, Math.max(1024, config.getMaxPayloadBytes()));
        boolean sizeTruncated = parseLimit < payloadBytes;
        char firstChar = firstMeaningfulChar(value, parseLimit);

        Walk walk = new Walk(mappedPaths, sampleFieldLimit);
        if (firstChar == '{' || firstChar == '[') {
            try (JsonParser parser = jsonFactory.createParser(value, 0, parseLimit)) {
                walkJson(parser, walk);
            } catch (WalkBudgetExceeded e) {
                walk.truncated = true;
            } catch (Exception e) {
                walk.parseError = shortMessage(e);
                log.debug("JSON digest failed for {}-{}@{}: {}", topic, partition, offset, walk.parseError);
            }
            return finish(topic, partition, offset, timestamp, key, payloadBytes,
                FORMAT_JSON, walk, sizeTruncated, () -> preview(value, parseLimit));
        }
        if (firstChar == '<') {
            try {
                walkXml(new ByteArrayInputStream(value, 0, parseLimit), walk);
            } catch (WalkBudgetExceeded e) {
                walk.truncated = true;
            } catch (Exception e) {
                walk.parseError = shortMessage(e);
                log.debug("XML digest failed for {}-{}@{}: {}", topic, partition, offset, walk.parseError);
            }
            return finish(topic, partition, offset, timestamp, key, payloadBytes,
                FORMAT_XML, walk, sizeTruncated, () -> preview(value, parseLimit));
        }

        return text(topic, partition, offset, timestamp, key, payloadBytes, preview(value, parseLimit));
    }

    /** Digests an already-materialized message (snapshot path). */
    public PayloadDigest digest(KafkaMessage message, Set<String> mappedPaths) {
        byte[] value = message.value() == null
            ? null
            : message.value().getBytes(StandardCharsets.UTF_8);
        return digest(message.topic(), message.partition(), message.offset(), message.timestamp(),
            message.key(), value, mappedPaths);
    }

    /** Digests a batch, resolving the mapped paths per topic. */
    public List<PayloadDigest> digestAll(List<KafkaMessage> messages, FieldMapping mapping) {
        Map<String, Set<String>> pathsByTopic = new LinkedHashMap<>();
        List<PayloadDigest> digests = new ArrayList<>(messages.size());
        for (KafkaMessage message : messages) {
            Set<String> paths = pathsByTopic.computeIfAbsent(message.topic(),
                topic -> mappedPaths(mapping, topic));
            digests.add(digest(message, paths));
        }
        return digests;
    }

    /**
     * The paths declared for {@code topic} in the field mapping, normalized to the digest's path
     * syntax ({@code $.order.items[0].sku} → {@code order.items[].sku}).
     */
    public Set<String> mappedPaths(FieldMapping mapping, String topic) {
        if (mapping == null) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        addMapped(paths, mapping.correlationIdPaths(), topic);
        addMapped(paths, mapping.timestampPaths(), topic);
        addMapped(paths, mapping.statusPaths(), topic);
        return paths;
    }

    /** Resolves shape ids referenced by a set of digests, skipping ids evicted from the registry. */
    public List<PayloadShape> shapesFor(Collection<String> shapeIds) {
        List<PayloadShape> shapes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : shapeIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            PayloadShape shape = shapeRegistry.get(id);
            if (shape != null) {
                shapes.add(shape);
            }
        }
        return shapes;
    }

    // ---------------------------------------------------------------- JSON

    private void walkJson(JsonParser parser, Walk walk) throws IOException {
        if (parser.nextToken() == null) {
            return;
        }
        walkJsonValue(parser, "", 0, walk);
    }

    private void walkJsonValue(JsonParser parser, String path, int depth, Walk walk) throws IOException {
        walk.event();
        JsonToken token = parser.currentToken();
        if (token == null) {
            return;
        }

        if (token == JsonToken.START_OBJECT) {
            if (depth >= config.getMaxDepth()) {
                parser.skipChildren();
                walk.truncated = true;
                walk.leafPath(path.isEmpty() ? "(root)" : path + ".{…}", depth);
                return;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                walkJsonValue(parser, path.isEmpty() ? name : path + "." + name, depth + 1, walk);
            }
            return;
        }

        if (token == JsonToken.START_ARRAY) {
            String arrayPath = path + "[]";
            if (depth >= config.getMaxDepth()) {
                parser.skipChildren();
                walk.truncated = true;
                walk.leafPath(arrayPath, depth);
                return;
            }
            int count = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (count < config.getArraySampleSize()) {
                    walkJsonValue(parser, arrayPath, depth + 1, walk);
                } else {
                    parser.skipChildren();
                    walk.event();
                }
                count++;
            }
            if (count == 0) {
                walk.leaf(arrayPath, "[]", depth);
            } else if (count > 1) {
                walk.arrayCounts.merge(arrayPath, count, Math::max);
            }
            return;
        }

        walk.leaf(path, token == JsonToken.VALUE_NULL ? "null" : parser.getValueAsString(), depth);
    }

    // ---------------------------------------------------------------- XML

    private void walkXml(ByteArrayInputStream input, Walk walk) throws Exception {
        XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(input);
        try {
            List<String> stack = new ArrayList<>();
            List<Boolean> hasChild = new ArrayList<>();
            Map<String, Integer> siblingCounts = new LinkedHashMap<>();
            StringBuilder text = new StringBuilder();

            while (reader.hasNext()) {
                walk.event();
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (!hasChild.isEmpty()) {
                            hasChild.set(hasChild.size() - 1, Boolean.TRUE);
                        }
                        String name = reader.getLocalName();
                        String parent = String.join(".", stack);
                        String path = parent.isEmpty() ? name : parent + "." + name;
                        int index = siblingCounts.merge(path, 1, Integer::sum);
                        if (index > 1) {
                            walk.arrayCounts.merge(path + "[]", index, Math::max);
                        }
                        if (stack.size() >= config.getMaxDepth()) {
                            walk.truncated = true;
                            walk.leafPath(path, stack.size() + 1);
                            skipXmlElement(reader, walk);
                            break;
                        }
                        stack.add(name);
                        hasChild.add(Boolean.FALSE);
                        text.setLength(0);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (text.length() < config.getMaxValueChars() * 4) {
                            text.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (stack.isEmpty()) {
                            break;
                        }
                        boolean leaf = !hasChild.remove(hasChild.size() - 1);
                        String path = String.join(".", stack);
                        if (leaf) {
                            walk.leaf(path, text.toString().trim(), stack.size());
                        }
                        stack.remove(stack.size() - 1);
                        text.setLength(0);
                    }
                    default -> { /* comments, PIs, whitespace: ignored */ }
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

    private void skipXmlElement(XMLStreamReader reader, Walk walk) throws Exception {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            walk.event();
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    // ---------------------------------------------------------------- digest assembly

    private PayloadDigest finish(String topic, int partition, long offset, long timestamp, String key,
                                  int payloadBytes, String format, Walk walk, boolean sizeTruncated,
                                  java.util.function.Supplier<String> previewSupplier) {
        boolean truncated = walk.truncated || sizeTruncated;
        boolean parsedNothing = walk.leafPaths.isEmpty();
        String shapeId = parsedNothing ? null : registerShape(format, walk);
        String preview = (parsedNothing || walk.parseError != null) ? previewSupplier.get() : null;

        return new PayloadDigest(topic, partition, offset, timestamp, key, payloadBytes, format,
            shapeId, Map.copyOf(walk.fields), Map.copyOf(walk.sample), Map.copyOf(walk.arrayCounts),
            preview, truncated, walk.parseError);
    }

    private String registerShape(String format, Walk walk) {
        List<String> paths = List.copyOf(walk.leafPaths);
        long hash = FNV_OFFSET;
        for (String path : paths) {
            for (int i = 0; i < path.length(); i++) {
                hash = (hash ^ path.charAt(i)) * FNV_PRIME;
            }
            hash = (hash ^ '\n') * FNV_PRIME;
        }
        String id = "S" + Long.toHexString(hash).substring(0, 8);
        shapeRegistry.putIfAbsent(id,
            new PayloadShape(id, format, walk.maxDepth, paths, walk.pathsTruncated));
        return id;
    }

    private PayloadDigest empty(String topic, int partition, long offset, long timestamp,
                                 String key, int payloadBytes) {
        return new PayloadDigest(topic, partition, offset, timestamp, key, payloadBytes,
            FORMAT_EMPTY, null, Map.of(), Map.of(), Map.of(), null, false, null);
    }

    private PayloadDigest text(String topic, int partition, long offset, long timestamp,
                                String key, int payloadBytes, String preview) {
        return new PayloadDigest(topic, partition, offset, timestamp, key, payloadBytes,
            FORMAT_TEXT, null, Map.of(), Map.of(), Map.of(), preview,
            preview != null && preview.length() < payloadBytes, null);
    }

    private String preview(byte[] value, int limit) {
        int length = Math.min(limit, config.getPreviewChars());
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }

    private static char firstMeaningfulChar(byte[] value, int limit) {
        for (int i = 0; i < limit; i++) {
            char c = (char) (value[i] & 0xFF);
            if (!Character.isWhitespace(c) && c != '\uFEFF') {
                return c;
            }
        }
        return '\0';
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ');
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static void addMapped(Set<String> paths, Map<String, String> mapping, String topic) {
        if (mapping == null) {
            return;
        }
        String path = mapping.get(topic);
        if (path != null && !path.isBlank()) {
            paths.add(normalizePath(path));
        }
    }

    /** {@code $.order['items'][0].sku} → {@code order.items[].sku}. */
    public static String normalizePath(String rawPath) {
        String path = rawPath.trim();
        if (path.startsWith("$")) {
            path = path.substring(1);
        }
        path = path.replaceAll("\\['([^']*)']", ".$1")
                   .replaceAll("\\[\"([^\"]*)\"]", ".$1")
                   .replaceAll("\\[\\s*(\\d+|\\*)\\s*]", "[]");
        while (path.startsWith(".")) {
            path = path.substring(1);
        }
        return path;
    }

    /** Accumulates one payload's digest state; also enforces the per-payload work budget. */
    private final class Walk {
        private final Set<String> mappedPaths;
        private final Set<String> mappedLeafNames;
        private final int sampleLimit;
        final Map<String, String> fields = new LinkedHashMap<>();
        final Map<String, String> sample = new LinkedHashMap<>();
        final Map<String, Integer> arrayCounts = new LinkedHashMap<>();
        final Set<String> leafPaths = new LinkedHashSet<>();
        int maxDepth;
        boolean truncated;
        boolean pathsTruncated;
        String parseError;
        private int events;

        Walk(Set<String> mappedPaths, int sampleLimit) {
            this.mappedPaths = mappedPaths == null ? Set.of() : mappedPaths;
            this.sampleLimit = Math.max(0, sampleLimit);
            this.mappedLeafNames = new HashSet<>();
            for (String path : this.mappedPaths) {
                int dot = path.lastIndexOf('.');
                this.mappedLeafNames.add(
                    (dot >= 0 ? path.substring(dot + 1) : path).toLowerCase(Locale.ROOT));
            }
        }

        void event() {
            if (++events > config.getMaxParseEvents()) {
                truncated = true;
                throw new WalkBudgetExceeded();
            }
        }

        void leafPath(String path, int depth) {
            maxDepth = Math.max(maxDepth, depth);
            if (leafPaths.size() < config.getMaxShapePaths()) {
                leafPaths.add(path);
            } else {
                pathsTruncated = true;
            }
        }

        void leaf(String path, String value, int depth) {
            leafPath(path, depth);
            if (isMapped(path)) {
                fields.putIfAbsent(path, truncateValue(value));
                return;
            }
            if (sample.size() < sampleLimit && value != null
                && value.length() <= config.getMaxValueChars()) {
                sample.putIfAbsent(path, value);
            }
        }

        private boolean isMapped(String path) {
            if (mappedPaths.contains(path)) {
                return true;
            }
            // Tolerate a mapping expressed against a slightly different prefix (envelope wrapper,
            // different array syntax): fall back to the leaf name.
            int dot = path.lastIndexOf('.');
            String leafName = (dot >= 0 ? path.substring(dot + 1) : path).toLowerCase(Locale.ROOT);
            return mappedLeafNames.contains(leafName);
        }

        private String truncateValue(String value) {
            if (value == null) {
                return null;
            }
            int max = config.getMaxValueChars();
            return value.length() <= max
                ? value
                : value.substring(0, max) + "…(" + value.length() + " chars)";
        }
    }

    /** Thrown to unwind the walk when the per-payload event budget is spent. */
    private static final class WalkBudgetExceeded extends RuntimeException {
        WalkBudgetExceeded() {
            super(null, null, false, false);
        }
    }
}
