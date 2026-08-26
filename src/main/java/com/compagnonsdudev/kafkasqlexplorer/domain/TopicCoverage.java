// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What one topic of a Process Mining run actually contributed.
 *
 * <p>Three numbers rather than two, and the third is what this record had to be corrected for. The
 * analysis used to reach the model as a per-topic sample of inlined records, so "read" against
 * "inlined" told the whole story: a topic read in full and inlined not at all was invisible in the
 * answer, which is exactly what the model's silence about it would otherwise be taken to mean.
 *
 * <p>That stopped being the shape when the prompt began opening with a process measured over
 * <em>every</em> record read, inlining only a handful of whole case traces as worked examples.
 * Counting the examples alone and calling them the analysed messages reported <em>six of three
 * thousand</em> on a run whose aggregate covered all three thousand — and sent the reader to raise a
 * prompt budget that was 6 % spent. A number that means one thing and reads as another is the
 * defect this file exists to prevent, so the two are now counted apart.
 *
 * @param messagesRead     records read from the broker for this topic
 * @param messagesMeasured records that entered the measured process: they carried a value at the
 *                         mapped correlation path, so they are counted in every transition, variant
 *                         and latency the answer rests on. Zero across the whole run means no event
 *                         log could be built and the per-topic sampling ran instead — which is what
 *                         lets a reader tell the two paths apart without a second flag to drift
 * @param messagesDetailed records inlined verbatim: a worked case trace, or, with no event log, the
 *                         per-topic sample. A low number here is ordinary on the measured path and
 *                         is <em>not</em> a reduced scope
 * @param readable {@code false} when the broker described no partition for this topic: an absent
 *                 topic or a typo, not an empty one. "Nothing to say about it" and "we never
 *                 opened it" are different answers.
 */
public record TopicCoverage(
    String topic,
    int messagesRead,
    int messagesMeasured,
    int messagesDetailed,
    boolean readable
) {
}
