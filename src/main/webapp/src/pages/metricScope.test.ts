// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { describeMetricScope, formatDurationMs, scopeNoteOf, LOW_MATCH_RATE } from './metricScope';

describe('what a metric says it measured', () => {
  it('says nothing at all when there is no summary', () => {
    expect(describeMetricScope(null)).toEqual([]);
    expect(describeMetricScope({})).toEqual([]);
    expect(scopeNoteOf(null)).toBeNull();
    expect(scopeNoteOf({ scopeNote: '   ' })).toBeNull();
  });

  it('shows the match rate even at 100 %, because an indicator only seen on bad news stops being read', () => {
    const chips = describeMetricScope({ matchRate: 1, matchedCount: 40, unmatchedSourceCount: 0 });
    expect(chips.map(c => c.label)).toEqual(['100% paired']);
    expect(chips[0].tone).toBe('neutral');
    expect(chips[0].detail).toContain('40 of 40');
  });

  it('marks a rate that makes the average describe a minority of what was read', () => {
    const chips = describeMetricScope({ matchRate: 0.25, matchedCount: 1, unmatchedSourceCount: 3 });
    expect(chips[0].label).toBe('25% paired');
    expect(chips[0].tone).toBe('warning');
    // C'est la phrase qui empêche de lire la moyenne comme un verdict.
    expect(chips[0].detail).toContain('improves when a downstream stage stalls');
    expect(LOW_MATCH_RATE).toBeLessThan(1);
  });

  it('reports a clock disagreement as a finding and silence as silence', () => {
    expect(describeMetricScope({ outOfOrderCount: 2 }).map(c => c.label)).toEqual(['2 before source']);
    // Zéro n'est pas une trouvaille : le cas ordinaire est muet.
    expect(describeMetricScope({ outOfOrderCount: 0, unmatchedTargetCount: 0 })).toEqual([]);
  });

  it('states how far apart the two counts were taken', () => {
    // Zéro compte ici : « pris au même instant » est faux et le chiffre le dit.
    expect(describeMetricScope({ readGapMs: 0 }).map(c => c.label)).toEqual(['0 ms apart']);
    expect(describeMetricScope({ readGapMs: 4200 })[0].label).toBe('4.2 s apart');
  });

  it('carries the engine caveats it was given, and the server sentence apart from them', () => {
    const summary = {
      warnings: ['Aggregate scan ceiling reached — …', 'WHERE fragment ignored'],
      scopeNote: 'Read from the most recent records backwards.',
    };
    const chips = describeMetricScope(summary);
    expect(chips.map(c => c.label)).toEqual(['2 caveats']);
    expect(chips[0].detail).toContain('WHERE fragment ignored');
    expect(scopeNoteOf(summary)).toBe('Read from the most recent records backwards.');
  });

  it('ignores a value that is not the number it claims to be', () => {
    expect(describeMetricScope({ matchRate: 'most of them', readGapMs: null })).toEqual([]);
  });

  it('formats a duration by its order of magnitude', () => {
    expect(formatDurationMs(320)).toBe('320 ms');
    expect(formatDurationMs(1500)).toBe('1.5 s');
    expect(formatDurationMs(180_000)).toBe('3 min');
  });
});
