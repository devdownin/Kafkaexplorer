// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Criteria for a stream-flow trace.
 *
 * <p>Every field but the key is optional and <strong>boxed</strong>: Jackson binds a record through
 * its canonical constructor, so an absent property arrives as {@code null} and a primitive component
 * fails the whole request with an unhelpful "Cannot map null into type int". A body as small as
 * {@code {"messageKey": "ORD-42"}} — what any script would send — has to work.
 *
 * @param messageKey    the value to look for — a literal, or a regex when {@code useRegex}
 * @param searchPath    where to look: empty for the record key and the whole payload,
 *                      {@code header:correlation-id} for one Kafka header, {@code /order/id} for an
 *                      XPath, or a dot-notation path ({@code order.items[].sku}, JSONPath accepted)
 *                      resolved against JSON and XML alike
 * @param caseSensitive defaults to false, like the topic search — an operator chasing an id rarely
 *                      means the case to matter
 * @param exactKey      the traced value <em>is</em> the Kafka record key, compared for equality.
 *                      Beyond being what "find record X" means, it lets the scan read only the
 *                      partition the default partitioner would have chosen — a twentieth of the
 *                      work on a twenty-partition topic. Mutually exclusive with a search path
 * @param searchHeaders widens a key/payload search to every Kafka header value. Defaults to
 *                      <strong>true</strong> here, unlike a topic search: a correlation id very
 *                      often travels only in a header, and a trace that never looks there reports
 *                      a confident "not found"
 * @param priorHits     hops already found by an earlier pass of the same trace, merged into this
 *                      one's graph. A trace stopped by the time budget leaves topics unread; the
 *                      natural next step is to scan those and keep the chain, not to start over —
 *                      and the chain rule stays server-side, where it is written once
 * @param priorCoverage what that earlier pass covered, so the coverage line describes the whole
 *                      picture instead of only its last pass
 */
public record StreamFlowRequest(
        String messageKey,
        Integer maxMessagesPerTopic,
        String searchPath,
        Integer timeLimitMinutes,
        Boolean useRegex,
        Boolean exactKey,
        Boolean caseSensitive,
        Boolean searchHeaders,
        List<String> targetTopics,
        List<StreamFlowHit> priorHits,
        StreamFlowCoverage priorCoverage
) {
    /** Never null: an absent property, and an absent list, are both "nothing already found". */
    public List<StreamFlowHit> resolvedPriorHits() {
        return priorHits == null ? List.of() : priorHits;
    }

    public boolean isUseRegex() {
        return Boolean.TRUE.equals(useRegex);
    }

    public boolean isExactKey() {
        return Boolean.TRUE.equals(exactKey);
    }

    public boolean isCaseSensitive() {
        return Boolean.TRUE.equals(caseSensitive);
    }

    public boolean isSearchHeaders() {
        return !Boolean.FALSE.equals(searchHeaders);
    }
}
