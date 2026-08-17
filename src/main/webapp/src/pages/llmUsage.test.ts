// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { describeDuration, describeUsage, totalTokens } from './llmUsage';
import type { LlmUsage } from '../api/types';

const usage = (over: Partial<LlmUsage> = {}): LlmUsage => ({
  inputTokens: 1200,
  outputTokens: 340,
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
