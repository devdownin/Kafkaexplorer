// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import {
  summarizeMetrics, describeAge, describeHealth, healthTone, hasRunningMetric, type MetricStatusInput,
} from './metricsHealth';

const metric = (over: Partial<MetricStatusInput> = {}): MetricStatusInput => ({
  lastValue: 42, lastUpdateTime: 1_700_000_000_000, errorMessage: null, ...over,
});

describe('summarizeMetrics', () => {
  it('counts nothing when nothing is configured', () => {
    expect(summarizeMetrics([])).toEqual({
      total: 0, reporting: 0, failing: 0, pending: 0, lastRefresh: null,
    });
  });

  it('separates reporting, failing and never-evaluated metrics', () => {
    const health = summarizeMetrics([
      metric(),
      metric({ errorMessage: 'Object \'orders\' not found' }),
      metric({ lastValue: null, lastUpdateTime: null }),
    ]);
    expect(health).toMatchObject({ total: 3, reporting: 1, failing: 1, pending: 1 });
  });

  /*
   * Une métrique en erreur expose encore la valeur du passage précédent. La compter comme « qui
   * rapporte » serait exactement la flatterie que cette page devait cesser.
   */
  it('counts a metric that has a stale value but failed as failing', () => {
    expect(summarizeMetrics([metric({ lastValue: 7, errorMessage: 'boom' })]))
      .toMatchObject({ reporting: 0, failing: 1 });
  });

  it('counts a legitimate zero as a value, not as missing', () => {
    expect(summarizeMetrics([metric({ lastValue: 0 })])).toMatchObject({ reporting: 1, pending: 0 });
  });

  it('reports the most recent refresh across all metrics', () => {
    const health = summarizeMetrics([
      metric({ lastUpdateTime: 1_000 }),
      metric({ lastUpdateTime: 9_000 }),
      metric({ lastUpdateTime: null }),
    ]);
    expect(health.lastRefresh).toBe(9_000);
  });
});

describe('describeAge', () => {
  const now = 1_700_000_000_000;

  it('returns null when there is no timestamp — the caller decides how to say "never"', () => {
    expect(describeAge(null, now)).toBeNull();
    expect(describeAge(0, now)).toBeNull();
  });

  it('scales the unit with the age', () => {
    expect(describeAge(now - 5_000, now)).toBe('5s ago');
    expect(describeAge(now - 120_000, now)).toBe('2 min ago');
    expect(describeAge(now - 7_200_000, now)).toBe('2 h ago');
    expect(describeAge(now - 172_800_000, now)).toBe('2 d ago');
  });

  it('never reports a negative age from a clock that ran ahead', () => {
    expect(describeAge(now + 10_000, now)).toBe('0s ago');
  });
});

describe('describeHealth / healthTone', () => {
  it('says plainly when nothing is configured', () => {
    const empty = summarizeMetrics([]);
    expect(describeHealth(empty)).toBe('No metric configured yet');
    expect(healthTone(empty)).toBe('neutral');
  });

  it('leads with failures', () => {
    const health = summarizeMetrics([metric(), metric({ errorMessage: 'boom' })]);
    expect(describeHealth(health)).toBe('1 of 2 failing');
    expect(healthTone(health)).toBe('error');
  });

  it('mentions metrics still waiting for a first value', () => {
    const health = summarizeMetrics([metric(), metric({ lastValue: null })]);
    expect(describeHealth(health)).toBe('1 of 2 waiting for a first value');
    expect(healthTone(health)).toBe('warning');
  });

  it('confirms a healthy set without inventing a percentage', () => {
    const health = summarizeMetrics([metric(), metric()]);
    expect(describeHealth(health)).toBe('2 of 2 reporting');
    expect(describeHealth(health)).not.toMatch(/%/);
    expect(healthTone(health)).toBe('success');
  });
});

describe('hasRunningMetric', () => {
  it('is false when nothing is configured', () => {
    expect(hasRunningMetric([])).toBe(false);
  });

  it('is true as soon as one metric reported a value', () => {
    expect(hasRunningMetric([metric()])).toBe(true);
  });

  it('counts a zero reading as running — zero is a measurement', () => {
    expect(hasRunningMetric([metric({ lastValue: 0 })])).toBe(true);
  });

  it('does not count a metric that has never produced a value', () => {
    expect(hasRunningMetric([metric({ lastValue: null })])).toBe(false);
  });

  it('does not count a failing metric, even though it still carries the previous value', () => {
    // C'est le cas qui distingue cette règle d'un simple `lastValue !== null` : la métrique
    // expose encore 42, et elle ne mesure plus rien.
    expect(hasRunningMetric([metric({ lastValue: 42, errorMessage: 'boom' })])).toBe(false);
  });

  it('needs only one runner among failures and pending ones', () => {
    expect(hasRunningMetric([
      metric({ errorMessage: 'boom' }),
      metric({ lastValue: null }),
      metric(),
    ])).toBe(true);
  });
});
