// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of one bounded scan pass.
 *
 * <p>A search never promises to have read the whole topic: it reads until one of the budgets is
 * spent, then hands back a {@code nextCursor}. The UI shows what was found so far, says how much
 * ground was covered, and can resume with the same criteria — so a long search stays responsive
 * and interruptible instead of hanging on a topic with millions of records.</p>
 *
 * @param scanned   records actually read during this pass
 * @param matched   records that matched (may exceed {@code hits.size()} when the hit cap is reached)
 * @param exhausted true when every scanned partition reached its end offset — nothing left to read
 * @param stopReason MAX_HITS / MAX_SCAN / TIMEOUT / EXHAUSTED
 */
public record TopicSearchResponse(
    List<TopicMessage> hits,
    int scanned,
    int matched,
    long elapsedMs,
    boolean exhausted,
    String stopReason,
    Map<String, Long> nextCursor,
    List<String> warnings
) {}
