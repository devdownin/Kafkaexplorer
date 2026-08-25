// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The one place an event-time value is resolved to epoch millis.
 *
 * <p>It was written twice, identically, and the second copy said so: {@code
 * FlinkSqlService.parseEventTimeMillis} carried the comment "mirroring the metric engine" above a
 * body byte-equivalent to {@code MetricService.toEpochMillis}, and a third file kept the threshold
 * on its own ({@code LlmHttpSupport.EPOCH_MILLIS_THRESHOLD}, for a rate-limit reset). Same argument
 * as {@code SecureXml} and {@code LogSafe}: a rule applied at several entry points is a rule that
 * drifts at all but one of them, and the process model needed a fourth caller.
 *
 * <p>What it decides, and why each branch is where it is:
 *
 * <ul>
 *   <li><strong>A number below 10<sup>10</sup> is seconds</strong>, above it is milliseconds. The
 *       threshold is the same one {@code setup-demo.sh} documents and the query engine applies, so
 *       the demo data and the analysis agree about what a timestamp column means. It stops being
 *       true in the year 2286; nothing here will.</li>
 *   <li><strong>A numeric string is a number.</strong> A digest stores every scalar as a String, so
 *       an epoch that travelled through JSON arrives as text and must not fall through to the
 *       date parsers.</li>
 *   <li><strong>ISO-8601, then a space-separated local date-time.</strong> The second is what
 *       databases and log lines emit (<code>2026-01-02 03:04:05</code>), read as UTC because a
 *       value with no offset carries none — which is a documented assumption rather than a
 *       guess.</li>
 * </ul>
 *
 * <p>Anything else answers {@code null}, and {@code null} means <em>this could not be resolved</em>,
 * never <em>the epoch</em>. Every caller has somewhere better to fall back to (the broker's own
 * record timestamp) and the distinction is the one this codebase keeps making: a measurement that
 * could not be taken is not a measurement of zero — here it would be a business event dated
 * 1 January 1970, which on a latency edge is not a wrong number but an absurd one.
 */
final class EventTime {

    /** Values below this are epoch seconds, at or above it epoch milliseconds. */
    static final long EPOCH_SECONDS_CEILING = 10_000_000_000L;

    private EventTime() {
    }

    /** Resolves a field value to epoch millis, or {@code null} when it is not a time at all. */
    static Long toEpochMillis(Object value) {
        if (value instanceof Number n) {
            return scale(n.longValue());
        }
        if (!(value instanceof String s)) {
            return null;
        }
        String text = s.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return scale(Long.parseLong(text));
        } catch (NumberFormatException notANumber) {
            // fall through to the date parsers
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception notIso) {
            // fall through to the offset-less form
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception notLocal) {
            return null;
        }
    }

    /**
     * Resolves a field value, falling back to a caller-supplied instant — the shape the two query
     * engines want, where the fallback is the Kafka record's own timestamp.
     */
    static long toEpochMillis(Object value, long fallback) {
        Long resolved = toEpochMillis(value);
        return resolved == null ? fallback : resolved;
    }

    private static long scale(long value) {
        return value < EPOCH_SECONDS_CEILING ? value * 1000L : value;
    }
}
