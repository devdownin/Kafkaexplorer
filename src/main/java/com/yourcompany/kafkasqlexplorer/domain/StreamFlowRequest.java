// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Criteria for a stream-flow trace.
 *
 * @param messageKey    the value to look for — a literal, or a regex when {@code useRegex}
 * @param searchPath    where to look: empty for the record key and the whole payload,
 *                      {@code header:correlation-id} for one Kafka header, {@code /order/id} for an
 *                      XPath, or a dot-notation path ({@code order.items[].sku}, JSONPath accepted)
 *                      resolved against JSON and XML alike
 * @param caseSensitive defaults to false, like the topic search — an operator chasing an id rarely
 *                      means the case to matter
 * @param searchHeaders widens a key/payload search to every Kafka header value. Defaults to
 *                      <strong>true</strong> here, unlike a topic search: a correlation id very
 *                      often travels only in a header, and a trace that never looks there reports
 *                      a confident "not found"
 */
public record StreamFlowRequest(
        String messageKey,
        int maxMessagesPerTopic,
        String searchPath,
        Integer timeLimitMinutes,
        boolean useRegex,
        Boolean caseSensitive,
        Boolean searchHeaders,
        List<String> targetTopics
) {
    public boolean isCaseSensitive() {
        return Boolean.TRUE.equals(caseSensitive);
    }

    public boolean isSearchHeaders() {
        return !Boolean.FALSE.equals(searchHeaders);
    }
}
