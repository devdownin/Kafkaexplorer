// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Whether the topics a proposed KPI reads actually hold anything, asked of the cluster at the
 * moment the proposal is made.
 *
 * <p>A suggestion is derived from evidence that has already aged: an audit read back from
 * {@code internal.audit.history} can be weeks old, a Stream Flow chain and a measured process are
 * kept in the browser for seven days. In that interval a topic is deleted, or retention empties
 * it — and nothing asked. The card was then offered with the same confidence as any other, its
 * thresholds multiples of a count that is no longer there, and the operator found out when the
 * metric had been refreshing every thirty seconds against a topic with nothing in it.
 *
 * <p><b>Four answers rather than two, and the fourth is the one to get right.</b> "The topic is
 * empty" and "we could not ask" are different findings that call for different gestures, and
 * folding the second into the first is the flattening this codebase keeps removing: it would mark
 * every card on the panel as backed by no data on the strength of one unreachable broker.
 */
public enum MetricDataState {
    /** Every topic it reads exists and holds records: the proposal can be measured today. */
    POPULATED,
    /**
     * Every topic it reads exists, and at least one of them holds no record right now.
     *
     * <p>Marked, never dropped. A topic emptied by retention fills again, one freshly created for
     * a pipeline being built is legitimately empty, and on a gap KPI an empty target beside a
     * populated source <em>is</em> the alarm this card exists to raise. What the operator needs is
     * to know it before setting a threshold on it, not to have the card taken away.
     */
    EMPTY,
    /**
     * At least one topic it reads no longer exists on the cluster.
     *
     * <p>Never reaches the panel: such a proposal is dropped and counted in a note. The metric
     * could only fail at every refresh, which is the same reason a nested status path yields no
     * KPI rather than SQL that would fail on its first run.
     */
    ABSENT,
    /**
     * The check could not be made, or does not apply.
     *
     * <p>The topic list could not be read; the count came back for none of the topics; or the name
     * the proposal reads resolves to no Kafka topic at all — a Flink table over another connector,
     * which the lineage family can legitimately name. Nothing is dropped and nothing is marked on
     * this state: an unanswered question must not be rendered as an answer.
     */
    UNKNOWN
}
