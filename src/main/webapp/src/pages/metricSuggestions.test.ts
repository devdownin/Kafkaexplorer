// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it } from 'vitest';
import {
  describeEvidence, dismiss, dismissedSuggestions, DISMISSED_KEY, readDismissed, restore,
  sourceLabel, stalenessNote, suggestionTopics, suggestionToDraft, visibleSuggestions,
} from './metricSuggestions';
import type { MetricConfig, MetricSuggestion, MetricSuggestions } from '../api/types';

const NOW = 1_700_000_000_000;

function metric(overrides: Partial<MetricConfig> = {}): MetricConfig {
  return {
    id: 'server-side-id',
    name: 'gauge_latency_a_to_b',
    type: 'GAUGE',
    sql: '',
    description: 'Average latency',
    warningThreshold: 800,
    criticalThreshold: 1600,
    lastValue: 42,
    lastUpdateTime: NOW,
    errorMessage: 'stale error',
    history: [1, 2, 3],
    lastSummary: { rowCount: 3 },
    createTableSql: null,
    templateType: 'TOPIC_TRANSIT_LATENCY',
    templateParams: { sourceTopic: 'a.topic', targetTopic: 'b.topic' },
    executionMode: 'TEMPLATE_BOUNDED_SCAN',
    labelTopic: 'a.topic',
    labelFields: [],
    ...overrides,
  };
}

function suggestion(overrides: Partial<MetricSuggestion> = {}): MetricSuggestion {
  return {
    id: 'audit:hop-latency:a.topic>b.topic',
    source: 'AUDIT',
    title: 'Processing latency a.topic → b.topic',
    rationale: 'This hop is on a flow the audit reconstructed.',
    evidence: ['The cluster audit measured an average hop of 400 ms.'],
    thresholdBasis: 'Warning at 2× and critical at 4× the 400 ms measured.',
    caveats: ['Correlation matches `id`.'],
    alreadyConfigured: false,
    existingMetricName: null,
    metric: metric(),
    ...overrides,
  };
}

function response(overrides: Partial<MetricSuggestions> = {}): MetricSuggestions {
  return {
    suggestions: [suggestion()],
    auditAvailable: true,
    auditId: 'run-1',
    auditTimestamp: NOW,
    auditSource: 'CURRENT_RUN',
    auditTopics: 12,
    flowChainsSubmitted: 0,
    notes: [],
    ...overrides,
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe('dismissals', () => {
  it('round-trips through localStorage and is idempotent', () => {
    const once = dismiss('audit:volume:orders');
    const twice = dismiss('audit:volume:orders', once);

    expect(twice).toEqual(['audit:volume:orders']);
    expect(readDismissed()).toEqual(['audit:volume:orders']);
  });

  it('restores one without touching the others', () => {
    dismiss('a');
    const after = restore('a', dismiss('b'));

    expect(after).toEqual(['b']);
  });

  it('survives corrupt storage rather than taking the page down', () => {
    localStorage.setItem(DISMISSED_KEY, 'not json');

    expect(readDismissed()).toEqual([]);
  });
});

describe('visibleSuggestions', () => {
  it('hides what was dismissed and shows the rest', () => {
    const kept = suggestion({ id: 'kept' });
    const hidden = suggestion({ id: 'hidden' });

    expect(visibleSuggestions([kept, hidden], ['hidden']).map(s => s.id)).toEqual(['kept']);
    expect(dismissedSuggestions([kept, hidden], ['hidden']).map(s => s.id)).toEqual(['hidden']);
  });

  it('sorts what is already measured last — it is an answer, not a proposal', () => {
    const covered = suggestion({ id: 'covered', alreadyConfigured: true });
    const open = suggestion({ id: 'open' });

    expect(visibleSuggestions([covered, open], []).map(s => s.id)).toEqual(['open', 'covered']);
  });
});

describe('describeEvidence', () => {
  it('says nothing has been measured, and what would change that', () => {
    const state = describeEvidence(response({
      suggestions: [], auditAvailable: false, auditTimestamp: null, auditSource: null,
      auditTopics: 0, flowChainsSubmitted: 0,
    }));

    expect(state.waiting).toBe(true);
    expect(state.unlocks.map(u => u.to)).toEqual(['/audit', '/stream-flow']);
    expect(state.summary).toContain('Nothing has been measured');
  });

  it('distinguishes "nothing measured" from "measured, nothing to suggest"', () => {
    const state = describeEvidence(response({ suggestions: [] }));

    expect(state.waiting).toBe(false);
    expect(state.summary).toContain('No KPI could be derived');
    expect(state.summary).toContain('12 topics');
  });

  it('names both evidence families when both are present', () => {
    const state = describeEvidence(response({ flowChainsSubmitted: 2 }));

    expect(state.summary).toContain('1 KPI derived');
    expect(state.summary).toContain('2 Stream Flow traces');
    expect(state.unlocks).toEqual([]);
  });

  it('says when the audit was read back from the history topic', () => {
    expect(describeEvidence(response({ auditSource: 'HISTORY' })).summary)
      .toContain('read back from the history topic');
  });
});

describe('stalenessNote', () => {
  it('is silent on a fresh audit', () => {
    expect(stalenessNote(response(), NOW + 60_000)).toBeNull();
  });

  it('warns when the proposals rest on an old scan', () => {
    const note = stalenessNote(response(), NOW + 10 * 24 * 60 * 60 * 1000);

    expect(note).toContain('10 days old');
  });

  it('claims nothing when the run carries no timestamp', () => {
    expect(stalenessNote(response({ auditTimestamp: null }), NOW)).toBeNull();
  });
});

describe('suggestionToDraft', () => {
  it('drops the server id and every field describing a run that never happened', () => {
    const draft = suggestionToDraft(suggestion());

    // Keeping the id would make the first save overwrite the metric carrying it.
    expect(draft.id).toBeUndefined();
    expect(draft.lastValue).toBeNull();
    expect(draft.errorMessage).toBeNull();
    expect(draft.history).toEqual([]);
    expect(draft.lastSummary).toBeNull();
  });

  it('keeps what the editor needs to open on the right form', () => {
    const draft = suggestionToDraft(suggestion());

    expect(draft.templateType).toBe('TOPIC_TRANSIT_LATENCY');
    expect(draft.templateParams).toEqual({ sourceTopic: 'a.topic', targetTopic: 'b.topic' });
    expect(draft.warningThreshold).toBe(800);
    expect(draft.labelTopic).toBe('a.topic');
  });
});

describe('suggestionTopics', () => {
  it('names the pair a template metric compares', () => {
    expect(suggestionTopics(suggestion())).toEqual(['a.topic', 'b.topic']);
  });

  it('falls back to the label topic for a raw SQL metric', () => {
    const raw = suggestion({
      metric: metric({ templateType: 'RAW_SQL', templateParams: {}, labelTopic: 'demo.orders' }),
    });

    expect(suggestionTopics(raw)).toEqual(['demo.orders']);
  });

  it('names the single topic a consumer-delay metric watches', () => {
    const timeLag = suggestion({
      metric: metric({
        templateType: 'CONSUMER_TIME_LAG',
        templateParams: { topic: 'demo.payments', group: 'pay-api', aggregation: 'MAX' },
        labelTopic: 'demo.payments',
      }),
    });

    expect(suggestionTopics(timeLag)).toEqual(['demo.payments']);
  });

  it('does not repeat a topic that is both ends', () => {
    const same = suggestion({
      metric: metric({ templateParams: { leftTopic: 'x', rightTopic: 'x' } }),
    });

    expect(suggestionTopics(same)).toEqual(['x']);
  });
});

describe('sourceLabel', () => {
  it('names the evidence family in the operator’s words', () => {
    expect(sourceLabel('AUDIT')).toBe('Cluster audit');
    expect(sourceLabel('STREAM_FLOW')).toBe('Stream Flow trace');
  });
});
