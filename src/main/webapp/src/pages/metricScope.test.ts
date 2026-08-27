// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { describeMeasurement, describeMetricScope, describeRefreshCost, formatDurationMs, scopeNoteOf, LOW_MATCH_RATE } from './metricScope';

describe('what a metric says it measured', () => {
  it('says nothing at all when there is no summary', () => {
    expect(describeMetricScope(null)).toEqual([]);
    expect(describeMetricScope({})).toEqual([]);
    expect(scopeNoteOf(null)).toBeNull();
    expect(scopeNoteOf({ scopeNote: '   ' })).toBeNull();
  });

  it('says how a count was obtained and what it compares, because both change the measurement', () => {
    const chips = describeMetricScope({ countedBy: 'OFFSETS', window: 'SINCE_LAST_REFRESH' });
    expect(chips.map(c => c.label)).toEqual(['by offsets', 'per interval']);
    expect(chips[0].detail).toContain('compacted away still counts');
    // Le mode par défaut ne dit rien : une puce toujours présente cesse d'être lue.
    expect(describeMetricScope({ countedBy: 'RECORDS', window: 'TOTAL' })).toEqual([]);
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

describe('la fenêtre, la perte totale, la lecture unique et la cadence', () => {
  it('nomme la fenêtre commune et le bord de fin qu’elle fabrique', () => {
    const chips = describeMetricScope({ windowMs: 600_000, matchRate: 0.8 });
    const window = chips.find(c => c.label.includes('window'));
    expect(window).toBeDefined();
    // Une source produite juste avant la fermeture a sa cible après : ça ressemble à une perte et
    // n'en est pas une, donc c'est dit plutôt que corrigé.
    expect(window!.detail).toContain('trailing edge');
  });

  it('dit « même instant » sur la foi du serveur, jamais sur la foi du zéro', () => {
    // Une seule lecture, dite comme telle.
    expect(describeMetricScope({ readGapMs: 0, sharedScan: true }).map(c => c.label))
      .toContain('same instant');
    expect(describeMetricScope({ readGapMs: 0, countedBy: 'OFFSETS' }).map(c => c.label))
      .toContain('same instant');
    // Deux lectures qui tombent dans la même milliseconde restent deux lectures : le chiffre ne
    // permet pas de conclure, donc on ne conclut pas.
    expect(describeMetricScope({ readGapMs: 0 }).map(c => c.label)).toContain('0 ms apart');
  });

  it('garde l’écart quand il y en a un', () => {
    const chips = describeMetricScope({ readGapMs: 1_500 });
    expect(chips.map(c => c.label)).toContain('1.5 s apart');
  });

  it('signale la perte totale, qui est l’état que la métrique existe pour attraper', () => {
    const chips = describeMetricScope({ totalLoss: true, readGapMs: 0 });
    const chip = chips.find(c => c.label === 'total loss');
    expect(chip?.tone).toBe('warning');
  });

  it('affiche la cadence propre d’une métrique, et rien quand elle suit le cycle', () => {
    expect(describeMetricScope(null, { refreshIntervalMs: 300_000 }).map(c => c.label))
      .toContain('every 5 min');
    // Le défaut n'a pas à être annoncé : une puce sur chaque carte n'apprend rien.
    expect(describeMetricScope(null, {})).toEqual([]);
    expect(describeMetricScope(null, { refreshIntervalMs: 0 })).toEqual([]);
  });
});

describe('les composantes du nombre affiché', () => {
  it('rend les deux côtés d’un écart, qui sont le diagnostic', () => {
    const parts = describeMeasurement({ leftValue: 12, rightValue: 7, operation: 'LEFT_MINUS_RIGHT' });
    expect(parts.map(p => `${p.label} ${p.value}`)).toEqual(['left 12', 'right 7']);
    expect(parts[1].detail).toContain('LEFT_MINUS_RIGHT');
  });

  it('rend la queue d’une latence, pas seulement la moyenne que la carte affiche', () => {
    const parts = describeMeasurement({ avgLatencyMs: 120, p95LatencyMs: 4_200, maxLatencyMs: 9_000 });
    expect(parts.map(p => p.label)).toEqual(['avg', 'p95', 'worst']);
    expect(parts[1].value).toBe('4.2 s');
  });

  it('ne produit rien pour une mesure qui n’a pas de composantes', () => {
    expect(describeMeasurement(null)).toEqual([]);
    expect(describeMeasurement({ rowCount: 3 })).toEqual([]);
  });

  it('compte zéro comme une mesure', () => {
    // Zéro des deux côtés est un fait sur le cluster, pas une absence de mesure.
    expect(describeMeasurement({ leftValue: 0, rightValue: 0 })).toHaveLength(2);
  });
});

describe('ce que la configuration coûtera', () => {
  it('dit « aucun enregistrement » quand le compte passe par les offsets', () => {
    const note = describeRefreshCost('TOPIC_COUNT_DELTA', {
      countBy: 'OFFSETS', leftTopic: 'a', rightTopic: 'b',
    });
    expect(note).toContain('Reads no record');
  });

  it('n’invente aucun total quand des enregistrements sont lus', () => {
    const note = describeRefreshCost('TOPIC_COUNT_DELTA', {
      countBy: 'RECORDS', maxRowsPerSide: 10_000,
    }) ?? '';
    // Le lecteur direct borne un agrégat à son propre plafond quoi que dise maxRowsPerSide, donc
    // un nombre d'enregistrements affiché ici serait fabriqué.
    expect(note).not.toMatch(/\d{3},\d{3}/);
    expect(note).toContain('aggregate ceiling');
  });

  it('porte la cadence propre de la métrique quand elle en a une', () => {
    expect(describeRefreshCost('TOPIC_TRANSIT_LATENCY', { refreshIntervalMs: 300_000 }))
      .toContain('at most every 5 min');
    expect(describeRefreshCost('TOPIC_TRANSIT_LATENCY', {})).toContain('on every refresh cycle');
  });

  it('énonce la fenêtre quand les deux côtés en partagent une', () => {
    expect(describeRefreshCost('TOPIC_TRANSIT_LATENCY', { windowMs: 900_000, maxRowsPerSide: 5_000 }))
      .toContain('the same 15 min on each side');
  });
});
