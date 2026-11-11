// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import type { AssistantSqlResponse, AssistantStatus } from '../api/types';
import {
  assistantAvailability, describeAssistantEffective, describeAssistantHelp, describeContextWindow, describeProvenance, describeScope, isFixableError, metricDraftPatch, privacyLine,
  proposalTabSql, proposalVerdict,
} from './sqlAssistant';

const response = (over: Partial<AssistantSqlResponse> = {}): AssistantSqlResponse => ({
  status: 'OK',
  sql: 'SELECT status FROM orders LIMIT 100',
  explanation: 'Lists statuses.',
  missing: null,
  validated: true,
  validationError: null,
  repairRounds: 0,
  tablesConsidered: ['orders'],
  tablesOmitted: [],
  model: 'gemma4:e2b',
  usage: null,
  durationMs: 8400,
  error: null,
  ...over,
});

const status = (over: Partial<AssistantStatus> = {}): AssistantStatus => ({
  enabled: true, problem: null, provider: 'Ollama', model: 'gemma4:e2b', localDeployment: true, baseUrl: null, requestTimeoutSeconds: 60, promptCharBudget: 12000, contextWindowTokens: 0, providerCode: 'OLLAMA', dataRetentionRefused: false, ...over,
});

describe('assistantAvailability', () => {
  it('says nothing before the status has loaded', () => {
    expect(assistantAvailability(null)).toEqual({ available: false, reason: null });
  });

  it('carries the reason when the assistant cannot answer', () => {
    expect(assistantAvailability(status({ enabled: false, problem: 'off' })).reason).toBe('off');
    expect(assistantAvailability(status({ problem: 'No API key' }))).toEqual({ available: false, reason: 'No API key' });
    expect(assistantAvailability(status()).available).toBe(true);
  });
});

describe('privacyLine — the Settings sentence, about what the assistant sends', () => {
  it('says nothing under the input when nothing leaves the host', () => {
    expect(privacyLine(status())).toBeNull();
  });

  it('carries OpenRouter\'s retention restriction, as Settings does', () => {
    expect(privacyLine(status({ localDeployment: false, providerCode: 'OPENROUTER', dataRetentionRefused: true })))
      .toBe('No retention: Table and column names (never a message value) leave this host, and routing is '
        + 'restricted to providers that do not retain or train on them — OpenRouter enforces that, so a '
        + 'provider which does cannot serve these requests.');
  });

  it('says retention is the endpoint\'s business where this application cannot enforce it', () => {
    expect(privacyLine(status({ localDeployment: false, providerCode: 'ANTHROPIC' })))
      .toContain('Governed by the endpoint: Table and column names');
  });
});

describe('proposalVerdict', () => {
  it('never lets an unvalidated statement pass for an accepted one', () => {
    const v = proposalVerdict(response({ validated: false, validationError: "Column 'x' not found" }));
    expect(v.tone).toBe('warning');
    expect(v.label).toBe('Not validated');
    expect(v.detail).toBe("Column 'x' not found");
  });

  it('says when the planner accepted it, and after how many refusals', () => {
    expect(proposalVerdict(response()).tone).toBe('success');
    expect(proposalVerdict(response({ repairRounds: 1 })).detail).toBe('Corrected after 1 refusal from the planner.');
  });

  it('renders the states of the assistant as states, not as failures of the page', () => {
    expect(proposalVerdict(response({ status: 'NEEDS_CLARIFICATION', sql: null, missing: 'No customer table' })).detail)
      .toBe('No customer table');
    expect(proposalVerdict(response({ status: 'BUSY', sql: null, error: 'busy' })).tone).toBe('warning');
    expect(proposalVerdict(response({ status: 'ERROR', sql: null, error: 'connection refused' })))
      .toEqual({ tone: 'error', label: 'Failed', detail: 'connection refused' });
  });
});

describe('describeScope and describeProvenance', () => {
  it('names what was shown and what was left out', () => {
    expect(describeScope(response({ tablesConsidered: ['a', 'b'], tablesOmitted: ['c'] })))
      .toBe('Shown to the model: a, b. Left out: c.');
    expect(describeScope(response({ tablesConsidered: [], tablesOmitted: [] }))).toBeNull();
  });

  it('reports the model and the time, and a cost only when one was reported', () => {
    expect(describeProvenance(response())).toBe('gemma4:e2b · 8.4 s');
  });

  it('uses the Process Mining formatter when the call reported its usage', () => {
    const usage = { inputTokens: 1200, outputTokens: 80, costUsd: 0.000123, cachedInputTokens: null,
      reasoningTokens: null, durationMs: 7000, provider: 'OpenRouter', model: 'openai/gpt-4o-mini' };
    expect(describeProvenance(response({ usage })))
      .toBe('openai/gpt-4o-mini · 1,200 in / 80 out · $0.000123 · 8.4 s');
  });
});

describe('isFixableError', () => {
  it('offers a fix for a statement error', () => {
    expect(isFixableError('Encountered "FROM" at line 1, column 12', 'SELECT a, FROM t')).toBe(true);
  });

  it('does not send a busy engine or a timeout to the model as if the statement were wrong', () => {
    expect(isFixableError('The Flink runtime was busy', 'SELECT 1')).toBe(false);
    expect(isFixableError('Query timed out after 30s', 'SELECT 1')).toBe(false);
    expect(isFixableError('Cannot reach Kafka broker: x', 'SELECT 1')).toBe(false);
    expect(isFixableError('anything', '  ')).toBe(false);
  });
});

describe('proposalTabSql', () => {
  it('opens with what to know, in comments the engine strips', () => {
    const text = proposalTabSql(response(), 'statuses\nof orders');
    expect(text.split('\n')[0]).toBe('-- Asked: statuses of orders');
    expect(text).toContain('-- Accepted by the planner.');
    expect(text.trim().endsWith('SELECT status FROM orders LIMIT 100')).toBe(true);
  });

  it('marks a statement the planner refused', () => {
    expect(proposalTabSql(response({ validated: false, validationError: 'bad\ncolumn' }), null))
      .toContain('-- NOT validated: bad column');
  });

  it('opens nothing when there is no statement', () => {
    expect(proposalTabSql(response({ sql: null }), 'q')).toBe('');
  });
});

describe('metricDraftPatch', () => {
  const draft = (over: Partial<AssistantSqlResponse> = {}) => ({
    proposal: response({ sql: 'SELECT COUNT(*) AS metric_value FROM orders', ...over }),
    name: 'orders_total', type: 'COUNTER', description: null,
  });

  it('fills the raw-SQL form and leaves the thresholds and an absent description alone', () => {
    const patch = metricDraftPatch(draft(), 'RAW_SQL');
    expect(patch).toEqual({
      templateType: 'RAW_SQL', templateParams: {}, executionMode: 'SQL', type: 'COUNTER',
      sql: 'SELECT COUNT(*) AS metric_value FROM orders', name: 'orders_total',
    });
    expect(patch).not.toHaveProperty('warningThreshold');
    expect(patch).not.toHaveProperty('description');
  });

  it('changes nothing when there is no statement to take', () => {
    expect(metricDraftPatch(draft({ sql: null }), 'RAW_SQL')).toBeNull();
    expect(metricDraftPatch(draft({ status: 'NEEDS_CLARIFICATION' }), 'RAW_SQL')).toBeNull();
  });
});

describe('describeAssistantHelp', () => {
  it('says nothing when the server reported nothing', () => {
    expect(describeAssistantHelp(null)).toBeNull();
  });

  it('says how to turn it on, on the page where the question comes up', () => {
    const help = describeAssistantHelp(status({ enabled: false, problem: 'off' }))!;
    expect(help.label).toBe('Off on this server');
    expect(help.howToEnable).toContain('EXPLORER_ASSISTANT_ENABLED=true');
    expect(help.howToEnable).toContain('compose/ollama.yml');
  });

  it('reports an assistant that is on but cannot answer, with the reason and no how-to', () => {
    const help = describeAssistantHelp(status({ problem: 'No API key is configured for OpenRouter' }))!;
    expect(help.tone).toBe('warning');
    expect(help.detail).toContain('No API key');
    expect(help.howToEnable).toBeNull();
  });

  it('claims nothing leaves the host only for a loopback endpoint', () => {
    expect(describeAssistantHelp(status())!.detail).toContain('no table or column name leaves this machine');
    expect(describeAssistantHelp(status({ localDeployment: false, providerCode: 'OPENROUTER', model: 'm' }))!.detail)
      .toContain('leave this host');
  });
});

describe('describeContextWindow', () => {
  it('says that nothing is checked when no window is declared', () => {
    expect(describeContextWindow(0, null)).toContain('nothing checks that they fit');
  });

  it('names the window and the ceiling it puts on both consumers', () => {
    expect(describeContextWindow(16384, 36864))
      .toBe('Context window declared: 16,384 tokens — every prompt is capped at 36,864 characters, '
        + 'for Process Mining and the assistant alike.');
  });
});

describe('describeAssistantEffective', () => {
  it('reports the model, the endpoint and the effective budgets', () => {
    expect(describeAssistantEffective(status({ baseUrl: 'http://ollama:11434/v1', requestTimeoutSeconds: 300 })))
      .toBe('Ollama · gemma4:e2b at http://ollama:11434/v1 — 300 s per call, 12,000 characters of prompt.');
  });
});
