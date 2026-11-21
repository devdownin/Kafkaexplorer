// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * The metrics a topic of a Stream Flow trace proposes, ready to create — pure, so the page only
 * renders them.
 *
 * A trace is the most precise observation this application makes of a pipeline: it names the hop
 * into the selected topic and the hop out of it. What is worth watching follows from that, and
 * nothing here asks the operator to write SQL:
 *
 * - **Transit latency of a hop** — the server's own suggestion (`MetricSuggestionService`, the
 *   card the Metrics page already shows for this trace), so the correlation key and the
 *   thresholds derived from the measured latency are written once. It is taken as it comes.
 * - **Loss on a hop** — the end-to-end completeness card's shape (`TOPIC_COUNT_DELTA`,
 *   `PERCENT_GAP`, counted from offsets since the previous refresh, 5 % / 20 %) applied to one hop.
 *   The server proposes it only from the entry to the exit of a chain; one hop is where it says
 *   *which* step loses records. Edit the two together.
 * - **Consumer lag in time** of the topic, for a group that reads it (the group is required: a
 *   delay is always somebody's).
 * - **Records produced** to the topic, from its end offsets.
 *
 * Every metric carries fixed labels naming what it measures (`source_topic` / `target_topic` on a
 * hop, `topic` and `consumer_group` on a topic), bounded by construction — none comes from the
 * payload. They go in `templateParams.staticLabels`, which the server checks and adds to every
 * series of the metric.
 */
import type { FlowHit } from './streamFlowLogic';
import type { MetricConfig, MetricSuggestion } from '../api/types';
import { suggestionToDraft } from './metricSuggestions';

/** Where the selected topic sits on the trace: the topic before it and the one after it. */
export interface TopicNeighbours {
  topic: string;
  previous: string | null;
  next: string | null;
}

/** The neighbours of a topic on the trace, in the order the hits arrived (the server's chaining). */
export function neighboursOf(hits: FlowHit[], topic: string): TopicNeighbours {
  const route = hits.map(h => h.topic).filter((t, i, all) => t && all.indexOf(t) === i);
  const at = route.indexOf(topic);
  return {
    topic,
    previous: at > 0 ? route[at - 1] : null,
    next: at >= 0 && at < route.length - 1 ? route[at + 1] : null,
  };
}

export type FlowMetricKind = 'hop-latency' | 'hop-loss' | 'consumer-lag' | 'produced';

/** One metric the drawer offers: what it is, why it matters here, and the draft it creates. */
export interface FlowMetricOption {
  id: string;
  kind: FlowMetricKind;
  title: string;
  why: string;
  /** Ticked when the drawer opens. */
  defaultOn: boolean;
  /** The labels this metric carries, each one can be unticked. */
  labels: Record<string, string>;
  /** Why it cannot be created, when it cannot. */
  unavailable: string | null;
  /** A metric of the same shape already exists. */
  existing: string | null;
  draft: Partial<MetricConfig>;
}

/** A Prometheus-safe name: lower case, letters, digits and underscores, starting with a letter. */
export function metricName(...parts: string[]): string {
  const name = parts.join('_').toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '');
  return /^[a-z_]/.test(name) ? name : `m_${name}`;
}

const countSql = (topic: string) => `SELECT COUNT(*) AS metric_value\nFROM ${topic.replace(/[.-]/g, '_')}`;

/** The id the server gives its hop-latency suggestion (`MetricSuggestionService`). */
export const hopLatencyId = (from: string, to: string) => `flow:hop-latency:${from}>${to}`;

function latencyOption(from: string, to: string, suggestions: MetricSuggestion[] | null,
  suggestionsError: string | null, defaultOn: boolean): FlowMetricOption {
  const suggestion = suggestions?.find(s => s.id === hopLatencyId(from, to)) ?? null;
  const labels = { source_topic: from, target_topic: to };
  const unavailable = suggestion ? null
    : suggestionsError ? `The proposal could not be read: ${suggestionsError}`
    : suggestions === null ? 'Reading the proposal for this hop…'
    : 'The server proposes no latency metric for this hop (no usable key was traced across it).';
  return {
    id: `latency:${from}>${to}`,
    kind: 'hop-latency',
    title: `Transit latency ${from} → ${to}`,
    why: suggestion?.thresholdBasis
      ?? 'How long a record takes to cross this hop; a stalled consumer or a slow step shows here first.',
    defaultOn: defaultOn && !!suggestion && !suggestion.alreadyConfigured,
    labels,
    unavailable,
    existing: suggestion?.alreadyConfigured ? suggestion.existingMetricName ?? 'an existing metric' : null,
    draft: suggestion ? suggestionToDraft(suggestion) : {},
  };
}

function lossOption(from: string, to: string): FlowMetricOption {
  return {
    id: `loss:${from}>${to}`,
    kind: 'hop-loss',
    title: `Records lost ${from} → ${to}`,
    why: 'The share of what entered this hop that did not come out, counted from offsets since the previous refresh. '
      + 'A step that filters or fans out has a gap by design: set the thresholds from what it reports.',
    defaultOn: false,
    labels: { source_topic: from, target_topic: to },
    unavailable: null,
    existing: null,
    draft: {
      name: metricName('hop_loss', from, to),
      type: 'GAUGE',
      sql: null,
      description: `Percentage gap between ${from} and ${to}, the hop a Stream Flow trace followed.`,
      templateType: 'TOPIC_COUNT_DELTA',
      executionMode: 'TEMPLATE_BOUNDED_SCAN',
      templateParams: {
        leftSql: countSql(from), rightSql: countSql(to), operation: 'PERCENT_GAP',
        countBy: 'OFFSETS', window: 'SINCE_LAST_REFRESH', leftTopic: from, rightTopic: to,
      },
      labelTopic: from,
      labelFields: [],
      warningThreshold: 5,
      criticalThreshold: 20,
    },
  };
}

/** The consumer-lag draft once a group is chosen (the group is part of what is measured). */
export function consumerLagDraft(topic: string, group: string): Partial<MetricConfig> {
  return {
    name: metricName('consumer_lag', topic, group),
    type: 'GAUGE',
    sql: null,
    description: `Age of the oldest record of ${topic} that ${group} has not read, in milliseconds.`,
    templateType: 'CONSUMER_TIME_LAG',
    executionMode: 'TEMPLATE_BOUNDED_SCAN',
    templateParams: { topic, group, aggregation: 'MAX' },
    labelTopic: null,
    labelFields: [],
    warningThreshold: null,
    criticalThreshold: null,
  };
}

/**
 * Every metric the selected topic proposes. `suggestions` is `null` while the server's proposals
 * are being read; `groups` the consumer groups reading the topic (`null` while read).
 */
export function flowMetricOptions(n: TopicNeighbours, suggestions: MetricSuggestion[] | null,
  suggestionsError: string | null, groups: string[] | null, groupsError: string | null): FlowMetricOption[] {
  const options: FlowMetricOption[] = [];
  if (n.previous) {
    options.push(latencyOption(n.previous, n.topic, suggestions, suggestionsError, true));
    options.push(lossOption(n.previous, n.topic));
  }
  if (n.next) {
    options.push(latencyOption(n.topic, n.next, suggestions, suggestionsError, !n.previous));
    options.push(lossOption(n.topic, n.next));
  }
  const group = groups?.[0] ?? null;
  options.push({
    id: `lag:${n.topic}`,
    kind: 'consumer-lag',
    title: `Consumer lag of ${n.topic}`,
    why: 'How far behind a consumer group is, in time: the delay a downstream reader adds.',
    defaultOn: !n.previous && !n.next && groups?.length === 1,
    labels: { topic: n.topic, ...(group ? { consumer_group: group } : {}) },
    unavailable: groupsError ? `Its consumer groups could not be read: ${groupsError}`
      : groups === null ? 'Reading the consumer groups…'
      : groups.length === 0 ? 'No consumer group reads this topic.' : null,
    existing: null,
    draft: group ? consumerLagDraft(n.topic, group) : {},
  });
  options.push({
    id: `produced:${n.topic}`,
    kind: 'produced',
    title: `Records produced to ${n.topic}`,
    why: 'The topic\'s end offsets, a running total: in Prometheus, rate() of it is the throughput, and a flat line is a silent producer.',
    defaultOn: false,
    labels: { topic: n.topic },
    unavailable: null,
    existing: null,
    draft: {
      name: metricName('produced', n.topic),
      type: 'GAUGE',
      sql: null,
      description: `Records produced to ${n.topic} (sum of its end offsets).`,
      templateType: 'KAFKA_CLUSTER_COUNT',
      executionMode: 'TEMPLATE_BOUNDED_SCAN',
      templateParams: { measurement: 'PRODUCED_OFFSETS', topic: n.topic },
      labelTopic: null,
      labelFields: [],
      warningThreshold: null,
      criticalThreshold: null,
    },
  });
  // Something is always ticked once the proposals are read: where no latency could be proposed,
  // the loss on the first hop is the metric this trace still supports.
  if (suggestions !== null && !options.some(o => o.defaultOn && !o.unavailable)) {
    const loss = options.find(o => o.kind === 'hop-loss');
    if (loss) loss.defaultOn = true;
  }
  return options;
}

/** The draft with the ticked labels as fixed labels, ready for `POST /api/metrics`. */
export function withStaticLabels(draft: Partial<MetricConfig>, labels: Record<string, string>): Partial<MetricConfig> {
  const params = { ...(draft.templateParams ?? {}) };
  if (Object.keys(labels).length) params.staticLabels = labels;
  else delete params.staticLabels;
  return { ...draft, templateParams: params };
}
