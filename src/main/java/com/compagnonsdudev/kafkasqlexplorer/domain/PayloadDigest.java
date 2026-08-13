// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.Map;

/**
 * A bounded, LLM-ready view of one Kafka record. A 1 MB deeply nested document collapses to a
 * few hundred bytes: the mapped business fields, a bounded sample of other scalars, the
 * cardinality of the arrays it contains, and a reference to its {@link PayloadShape}. The raw
 * payload is never retained — this is what the live window buffers instead.
 *
 * @param payloadBytes size of the original record value, before digestion
 * @param shapeId      {@link PayloadShape#id()} of the structure, null when the payload is not structured
 * @param fields       values at the paths declared by the {@code FieldMapping} (correlation id, timestamp, status)
 * @param sample       other scalar values, document order, bounded in count and length
 * @param arrayCounts  path → number of elements, for arrays that carry more than one element
 * @param preview      bounded prefix of the raw payload, only for unstructured or unparseable values
 * @param truncated    the digest is partial (payload larger than the parse budget, or a walk cap was hit)
 * @param parseError   why structured parsing failed, null when it succeeded
 */
public record PayloadDigest(
    String topic,
    int partition,
    long offset,
    long timestamp,
    String key,
    int payloadBytes,
    String format,
    String shapeId,
    Map<String, String> fields,
    Map<String, String> sample,
    Map<String, Integer> arrayCounts,
    String preview,
    boolean truncated,
    String parseError
) {
    /** Rough heap cost of this digest, used to bound the live window by bytes rather than by count. */
    public int estimatedHeapBytes() {
        int size = 128;
        size += entriesSize(fields);
        size += entriesSize(sample);
        if (arrayCounts != null) {
            size += arrayCounts.size() * 48;
            for (String path : arrayCounts.keySet()) {
                size += path.length() * 2;
            }
        }
        if (preview != null) size += preview.length() * 2;
        if (key != null) size += key.length() * 2;
        return size;
    }

    private static int entriesSize(Map<String, String> map) {
        if (map == null) return 0;
        int size = map.size() * 48;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            size += entry.getKey().length() * 2;
            if (entry.getValue() != null) size += entry.getValue().length() * 2;
        }
        return size;
    }
}
