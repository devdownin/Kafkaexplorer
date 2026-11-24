// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { comparedAfterEvent, eventWithinObserved, previewPath, scenarioFor } from './demoScenarios';

const event = { scenario: 'incident' as const, at: 120, metricIds: ['demo-live-shift'] };

describe('demo scenario charts', () => {
  it('uses clearly illustrative previews with semantic, scoped metric identifiers', () => {
    expect(previewPath(scenarioFor('incident').preview)).toMatch(/^M /);
    expect(scenarioFor('incident').metricIds).toEqual(['demo-live-shift', 'demo-live-jitter']);
  });
  it('only marks a confirmed event inside truly observed timestamps', () => {
    expect(eventWithinObserved(event, 'demo-live-shift', 100, 150)).toBe(true);
    expect(eventWithinObserved(event, 'demo-live-shift', 121, 150)).toBe(false);
    expect(eventWithinObserved(event, 'demo-live-shift', 90, 119)).toBe(false);
    expect(eventWithinObserved(event, 'demo-live-backlog', 100, 150)).toBe(false);
  });
  it('never fabricates a delta from unmatched or imputed observations', () => {
    const observed = [{ at: 120, actual: 130 }, { at: 135, actual: null },
      { at: 140, actual: 160 }, { at: 160, actual: 200 }];
    const previous = [{ at: 120, q50: 100 }, { at: 135, q50: 100 }, { at: 140, q50: 100 }];
    expect(comparedAfterEvent(event, 'demo-live-shift', observed, previous))
      .toEqual({ count: 2, meanDelta: 45 });
    expect(comparedAfterEvent(event, 'demo-live-backlog', observed, previous)).toBeNull();
    expect(comparedAfterEvent(event, 'demo-live-shift', observed, [])).toBeNull();
  });
});
