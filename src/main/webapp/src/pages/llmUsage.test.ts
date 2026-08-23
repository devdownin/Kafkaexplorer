// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { describeDuration, describeUsage, formatCostUsd, totalCostUsd, totalTokens } from './llmUsage';
import type { LlmUsage } from '../api/types';

const usage = (over: Partial<LlmUsage> = {}): LlmUsage => ({
  inputTokens: 1200,
  outputTokens: 340,
  costUsd: null,
  durationMs: 8400,
  provider: 'Ollama',
  model: 'qwen3:4b',
  ...over,
});

describe('describeUsage', () => {
  it('names the model, the tokens and the time', () => {
    expect(describeUsage(usage())).toBe('qwen3:4b · 1,200 in / 340 out · 8.4 s');
  });

  it('says the tokens were not reported rather than showing zero', () => {
    const text = describeUsage(usage({ inputTokens: null, outputTokens: null }));
    expect(text).toContain('tokens not reported');
    expect(text).not.toContain('0 in');
  });

  it('keeps the duration when only the tokens are unknown', () => {
    expect(describeUsage(usage({ inputTokens: null, outputTokens: null })))
      .toContain('8.4 s');
  });

  // Half a measurement is not a measurement: reporting the known half would understate the call.
  it('treats a half-reported count as unreported', () => {
    expect(describeUsage(usage({ outputTokens: null }))).toContain('tokens not reported');
  });

  it('shows what the provider says the call cost', () => {
    expect(describeUsage(usage({ costUsd: 0.00042 })))
      .toBe('qwen3:4b · 1,200 in / 340 out · $0.000420 · 8.4 s');
  });

  // A provider that prices nothing must not make the call look free.
  it('says nothing about money when the provider priced nothing', () => {
    expect(describeUsage(usage())).not.toContain('$');
  });
});

describe('formatCostUsd', () => {
  // Two decimals would print $0.00 on every analysis and make the figure useless.
  it('goes to six decimals below a cent', () => {
    expect(formatCostUsd(0.00042)).toBe('$0.000420');
  });

  it('keeps two decimals above', () => {
    expect(formatCostUsd(1.239)).toBe('$1.24');
    expect(formatCostUsd(0.01)).toBe('$0.01');
  });

  // Zero is a real measurement — a free model — not an absent one.
  it('renders a free call as zero rather than as unknown', () => {
    expect(formatCostUsd(0)).toBe('$0.00');
  });
});

describe('totalCostUsd', () => {
  it('adds up a session', () => {
    expect(totalCostUsd([usage({ costUsd: 0.001 }), usage({ costUsd: 0.002 })]))
      .toBeCloseTo(0.003, 10);
  });

  /*
   * Une session dont un appel n'a pas été chiffré coûte *plus* que ce qu'on sait additionner :
   * afficher la somme partielle sous-estimerait une dépense réelle avec l'aplomb d'un total exact.
   */
  it('refuses a total when one call was not priced', () => {
    expect(totalCostUsd([usage({ costUsd: 0.001 }), usage()])).toBeNull();
  });

  it('has no total to give for no calls', () => {
    expect(totalCostUsd([])).toBeNull();
  });
});

describe('describeDuration', () => {
  it('uses milliseconds below a second', () => {
    expect(describeDuration(420)).toBe('420 ms');
  });

  it('uses seconds above', () => {
    expect(describeDuration(1000)).toBe('1.0 s');
    expect(describeDuration(93_500)).toBe('93.5 s');
  });
});

describe('totalTokens', () => {
  it('adds up a session', () => {
    expect(totalTokens([usage(), usage()])).toBe(3080);
  });

  it('refuses a total when one call reported nothing', () => {
    expect(totalTokens([usage(), usage({ inputTokens: null, outputTokens: null })])).toBeNull();
  });

  it('has no total to give for no calls', () => {
    expect(totalTokens([])).toBeNull();
  });
});
