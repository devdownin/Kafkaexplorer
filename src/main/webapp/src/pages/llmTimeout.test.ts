// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { describe, it, expect } from 'vitest';
import {
  TEST_TIMEOUT_CEILING_MS,
  TEST_TIMEOUT_FLOOR_MS,
  TEST_TIMEOUT_MARGIN_MS,
  describeTestTimeout,
  testTimeoutMs,
} from './llmTimeout';

describe('testTimeoutMs', () => {
  it('outlasts the budget the two bundled local stacks configure', () => {
    // compose/ollama.yml and compose/spectra-hub.yml both set 300s. The old hardcoded 90 000
    // aborted at less than a third of it, which is the defect this exists for.
    expect(testTimeoutMs(300)).toBe(300_000 + TEST_TIMEOUT_MARGIN_MS);
    expect(testTimeoutMs(300)).toBeGreaterThan(300_000);
  });

  it('waits exactly as long as before on the shipped default', () => {
    // 60s + margin is under the floor, so a deployment that changed nothing sees no change.
    expect(testTimeoutMs(60)).toBe(TEST_TIMEOUT_FLOOR_MS);
  });

  it('never waits less than it used to, however low the budget', () => {
    expect(testTimeoutMs(1)).toBe(TEST_TIMEOUT_FLOOR_MS);
    expect(testTimeoutMs(0)).toBe(TEST_TIMEOUT_FLOOR_MS);
    expect(testTimeoutMs(-30)).toBe(TEST_TIMEOUT_FLOOR_MS);
  });

  it('falls back to the floor when the server has not said', () => {
    // The page renders before GET /api/config answers, and an unknown budget must not be read as
    // a fast one — assuming the server is quicker than it is is the mistake being corrected.
    expect(testTimeoutMs(undefined)).toBe(TEST_TIMEOUT_FLOOR_MS);
    expect(testTimeoutMs(null)).toBe(TEST_TIMEOUT_FLOOR_MS);
    expect(testTimeoutMs(Number.NaN)).toBe(TEST_TIMEOUT_FLOOR_MS);
    expect(testTimeoutMs(Number.POSITIVE_INFINITY)).toBe(TEST_TIMEOUT_FLOOR_MS);
  });

  it('covers the largest budget the settings form accepts, and stops there', () => {
    expect(testTimeoutMs(600)).toBe(600_000 + TEST_TIMEOUT_MARGIN_MS);
    expect(testTimeoutMs(600)).toBeLessThanOrEqual(TEST_TIMEOUT_CEILING_MS);
    expect(testTimeoutMs(86_400)).toBe(TEST_TIMEOUT_CEILING_MS);
  });
});

describe('describeTestTimeout', () => {
  it('says the browser gave up, and names the budget it gave up against', () => {
    const message = describeTestTimeout(315_000, 300);
    expect(message).toContain('browser stopped waiting after 315s');
    expect(message).toContain("server's own budget is 300s");
    expect(message).toContain('claude.request-timeout-seconds');
  });

  it('claims no budget it was not told', () => {
    const message = describeTestTimeout(90_000, undefined);
    expect(message).toContain('browser stopped waiting after 90s');
    expect(message).not.toContain("server's own budget");
  });

  it('does not advise reducing topics on a call that carries none', () => {
    // The page's generic failure message did, and this is a one-word health check.
    expect(describeTestTimeout(90_000, 60)).not.toMatch(/topic|sample/i);
  });
});
