// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce qu'une métrique a réellement mesuré, en quelques mots sur sa carte.
 *
 * `MetricConfig.lastSummary` portait déjà le taux d'appariement, les décomptes non appariés, le
 * délai entre les deux lectures et la note de portée — calculés, persistés dans
 * `internal.metrics.config`, et affichés à personne : seul l'aperçu du modal les rendait, donc
 * uniquement avant l'enregistrement. Une métrique en service n'en montrait rien.
 *
 * Ça compte surtout pour la latence de transit : un événement source dont la cible n'est jamais
 * arrivée ne pèse pas dans la moyenne, donc la valeur *s'améliore* quand le pipeline casse. Le
 * taux d'appariement est ce qui empêche de lire la moyenne comme un verdict — voir
 * METRICS-TWO-QUERY-AUDIT.md, D6.
 */

export type ScopeTone = 'neutral' | 'warning';

export interface ScopeChip {
  /** Ce qui s'affiche sur la puce. */
  label: string;
  /** Ce que la puce veut dire, en une phrase, pour l'infobulle. */
  detail: string;
  tone: ScopeTone;
}

/** En dessous, la moyenne décrit une minorité des événements lus et le dit. */
export const LOW_MATCH_RATE = 0.9;

function num(source: Record<string, unknown>, key: string): number | null {
  const value = source[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** `4.2 s`, `320 ms` — l'unité suit l'ordre de grandeur, comme partout ailleurs. */
export function formatDurationMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms / 60_000)} min`;
}

/**
 * Les faits de portée d'une métrique, prêts à afficher — jamais un verdict.
 *
 * Ce qui vaut zéro et ce qui est absent ne sont pas la même chose : un décompte absent ne produit
 * aucune puce (la métrique ne mesure pas ça), un décompte à zéro non plus quand zéro est le cas
 * ordinaire et donc muet. Le taux d'appariement fait exception et s'affiche même à 100 % : une
 * indication qui n'apparaît que sur mauvaise nouvelle est une indication qu'on cesse de lire.
 */
export function describeMetricScope(
  lastSummary: Record<string, unknown> | null,
  templateParams: Record<string, unknown> | null = null,
): ScopeChip[] {
  const chips: ScopeChip[] = [];

  // La cadence vient des paramètres, pas du résumé : elle décrit ce que la métrique *va* faire,
  // pas ce qu'elle a mesuré. Elle est affichée quand même, et seulement quand elle s'écarte du
  // défaut — une métrique qui ne se rafraîchit qu'à l'heure et ne le dit pas se lit comme figée.
  const interval = templateParams ? num(templateParams, 'refreshIntervalMs') : null;
  if (interval !== null && interval > 0) {
    chips.push({
      label: `every ${formatDurationMs(interval)}`,
      detail:
        'This metric asks for its own cadence rather than running on every cycle. It can only be ' +
        'slower than the loop\u2019s own tick, never faster, and the last-success timestamp is what ' +
        'dates the value in between.',
      tone: 'neutral',
    });
  }

  if (!lastSummary) return chips;

  // Comment le compte a été obtenu, et sur quoi il porte : deux mesures différentes sous un même
  // nom de métrique, donc la carte le dit plutôt que de laisser deviner.
  if (lastSummary.countedBy === 'OFFSETS') {
    chips.push({
      label: 'by offsets',
      detail:
        'Counted from the log\u2019s own offsets rather than by reading records: no scan ceiling, ' +
        'and both sides come out of one call so they describe the same instant. It counts what was ' +
        'produced — a transaction marker counts, and a record later compacted away still counts.',
      tone: 'neutral',
    });
  }
  if (lastSummary.window === 'SINCE_LAST_REFRESH') {
    chips.push({
      label: 'per interval',
      detail:
        'Compares what each side produced since the previous refresh, not the lifetime totals — ' +
        'which lose their sensitivity as history accumulates.',
      tone: 'neutral',
    });
  }

  const matchRate = num(lastSummary, 'matchRate');
  if (matchRate !== null) {
    const matched = num(lastSummary, 'matchedCount');
    const unmatched = num(lastSummary, 'unmatchedSourceCount');
    const of = matched !== null && unmatched !== null ? ` of ${matched + unmatched}` : '';
    chips.push({
      label: `${Math.round(matchRate * 100)}% paired`,
      detail:
        `${matched ?? '?'}${of} source event(s) found a target event. The value averages those alone, ` +
        'so it improves when a downstream stage stalls — read it against this rate, not on its own.',
      tone: matchRate < LOW_MATCH_RATE ? 'warning' : 'neutral',
    });
  }

  const unmatchedTargets = num(lastSummary, 'unmatchedTargetCount');
  if (unmatchedTargets !== null && unmatchedTargets > 0) {
    chips.push({
      label: `${unmatchedTargets} target${unmatchedTargets > 1 ? 's' : ''} unpaired`,
      detail: 'Target events no source event claimed: a replay, a duplicate, or a source outside the window read.',
      tone: 'neutral',
    });
  }

  const outOfOrder = num(lastSummary, 'outOfOrderCount');
  if (outOfOrder !== null && outOfOrder > 0) {
    chips.push({
      label: `${outOfOrder} before source`,
      detail:
        'Target events stamped before the source they match: two producers whose clocks disagree, ' +
        'or an event back-dated on the way. Dropped from the average — a negative latency is not a latency.',
      tone: 'warning',
    });
  }

  const windowMs = num(lastSummary, 'windowMs');
  if (windowMs !== null && windowMs > 0) {
    chips.push({
      label: `${formatDurationMs(windowMs)} window`,
      detail:
        'Both sides were read over the same stretch of time, from one instant computed once — so ' +
        'the pairing is not an accident of the two topics\u2019 throughputs, which is what a row cap ' +
        'makes it. A source produced near the end of the window has its target after it, outside ' +
        'both reads: the trailing edge understates the rate above by about one hop\u2019s worth of traffic.',
      tone: 'neutral',
    });
  }

  if (lastSummary.totalLoss === true) {
    chips.push({
      label: 'total loss',
      detail:
        'The right side counted zero against a non-zero left side. The gap is reported as 100 % — a ' +
        'definition for that case, not the formula\u2019s own answer, which divides by the right side.',
      tone: 'warning',
    });
  }

  const readGapMs = num(lastSummary, 'readGapMs');
  if (readGapMs !== null) {
    /*
     * « Même instant » est une affirmation sur la façon dont les deux comptes ont été pris, pas
     * une lecture du chiffre : deux requêtes séparées peuvent tomber dans la même milliseconde,
     * et zéro dirait alors quelque chose de faux. Le serveur sait laquelle des deux — offsets, ou
     * un scan partagé entre les deux côtés — et c'est ce drapeau qu'on lit.
     */
    const oneRead = lastSummary.sharedScan === true || lastSummary.countedBy === 'OFFSETS';
    chips.push(
      oneRead
        ? {
            label: 'same instant',
            detail:
              'Both sides came out of a single read, so no traffic falls between them and the ' +
              'comparison leans neither way.',
            tone: 'neutral',
          }
        : {
            label: `${formatDurationMs(readGapMs)} apart`,
            detail:
              'The two sides were read this far apart, never at one instant. Traffic in between lands in ' +
              'one of the two counts and not the other.',
            tone: 'neutral',
          },
    );
  }

  const warnings = lastSummary.warnings;
  if (Array.isArray(warnings) && warnings.length > 0) {
    chips.push({
      label: `${warnings.length} caveat${warnings.length > 1 ? 's' : ''}`,
      detail: warnings.filter(w => typeof w === 'string').join(' · '),
      tone: 'warning',
    });
  }

  return chips;
}

/** La phrase de portée du serveur, quand il en a écrit une. */
export function scopeNoteOf(lastSummary: Record<string, unknown> | null): string | null {
  const note = lastSummary?.scopeNote;
  return typeof note === 'string' && note.trim() !== '' ? note : null;
}

// ── Ce que la mesure vaut, et non seulement ce qu'elle a couvert ────────────

/** Une composante du nombre affiché : son nom, sa valeur déjà formatée, ce qu'elle veut dire. */
export interface MeasurementPart {
  label: string;
  value: string;
  detail: string;
}

/**
 * Le nom lisible de chaque clé de composante, écrit **une fois**.
 *
 * `describeMeasurement` l'affiche à côté du nombre et `componentSeries` l'affiche dans la légende
 * du graphe : deux endroits qui nomment la même chose, donc une seule table — sans quoi la puce
 * dirait « worst » et la légende « max » pour la même série.
 */
const COMPONENT_LABELS: Record<string, string> = {
  leftValue: 'left',
  rightValue: 'right',
  avgLatencyMs: 'avg',
  p95LatencyMs: 'p95',
  maxLatencyMs: 'worst',
  maxLagMs: 'worst partition',
  avgLagMs: 'avg partition',
};

/** Les clés qui se lisent comme une durée plutôt que comme un décompte. */
const DURATION_KEYS = new Set(['avgLatencyMs', 'p95LatencyMs', 'maxLatencyMs', 'maxLagMs', 'avgLagMs']);

function count(n: number): string {
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/**
 * Les composantes du nombre que la carte affiche en grand.
 *
 * La carte rendait `lastValue` et rien d'autre. Sur un écart, **`5` ne dit rien et `12 contre 7`
 * est le diagnostic** — et les deux côtés étaient dans `lastSummary`, calculés, persistés, montrés
 * à personne. Sur une latence c'est pire depuis que le p95 est exporté vers Prometheus : il partait
 * vers un scraper et pas vers la personne qui regarde la carte.
 *
 * Rien n'est dérivé ici : chaque partie est une valeur que le serveur a mesurée et nommée. Une clé
 * absente ne produit pas de partie — la métrique ne mesure pas ça — et une clé à zéro en produit
 * une, parce que zéro est une mesure.
 */
export function describeMeasurement(lastSummary: Record<string, unknown> | null): MeasurementPart[] {
  if (!lastSummary) return [];

  // Écart de comptage : les deux côtés, dans l'ordre où l'opération les nomme.
  const left = num(lastSummary, 'leftValue');
  const right = num(lastSummary, 'rightValue');
  if (left !== null && right !== null) {
    const windowed = lastSummary.window === 'SINCE_LAST_REFRESH';
    const over = windowed ? ' over this interval' : '';
    return [
      {
        label: COMPONENT_LABELS.leftValue,
        value: count(left),
        detail: `What the left side counted${over}.`,
      },
      {
        label: COMPONENT_LABELS.rightValue,
        value: count(right),
        detail: `What the right side counted${over}. The value above is these two compared by `
          + `${typeof lastSummary.operation === 'string' ? lastSummary.operation : 'the chosen operation'}.`,
      },
    ];
  }

  // Latence corrélée : la moyenne est ce que la carte affiche, et c'est la queue qui réveille.
  const avg = num(lastSummary, 'avgLatencyMs');
  const p95 = num(lastSummary, 'p95LatencyMs');
  const max = num(lastSummary, 'maxLatencyMs');
  if (avg !== null || p95 !== null || max !== null) {
    const parts: MeasurementPart[] = [];
    if (avg !== null) parts.push({
      label: COMPONENT_LABELS.avgLatencyMs, value: formatDurationMs(avg),
      detail: 'The average of the pairs this read could form — which is the number above.',
    });
    if (p95 !== null) parts.push({
      label: COMPONENT_LABELS.p95LatencyMs, value: formatDurationMs(p95),
      detail: 'The 95th percentile. An average holds still while the worst decile doubles, so this '
        + 'is what a latency alert is set on; it is exported as explorer_metric_correlation_latency_p95_ms '
        + 'for a GAUGE metric.',
    });
    if (max !== null) parts.push({
      label: COMPONENT_LABELS.maxLatencyMs, value: formatDurationMs(max),
      detail: 'The slowest pair observed in this read.',
    });
    return parts;
  }

  // Retard d'un groupe, en temps.
  const maxLag = num(lastSummary, 'maxLagMs');
  const avgLag = num(lastSummary, 'avgLagMs');
  if (maxLag !== null || avgLag !== null) {
    const parts: MeasurementPart[] = [];
    if (maxLag !== null) parts.push({
      label: COMPONENT_LABELS.maxLagMs, value: formatDurationMs(maxLag),
      detail: 'The age of the oldest waiting record, on the partition furthest behind.',
    });
    if (avgLag !== null) parts.push({
      label: COMPONENT_LABELS.avgLagMs, value: formatDurationMs(avgLag),
      detail: 'The same age averaged over the partitions that could be measured.',
    });
    return parts;
  }

  return [];
}

/**
 * Ce que cette configuration coûtera au broker, dit avant plutôt qu'après.
 *
 * `explorer_metrics_refresh_duration_seconds` mesure le cycle une fois passé ; l'éditeur peut
 * énoncer le coût au moment où on le choisit. **Aucun total n'est inventé** : le lecteur direct
 * borne un agrégat à son propre plafond quoi que dise `maxRowsPerSide`, et une projection s'arrête
 * à sa limite de lignes — deux formes, deux bornes. Ce qui est dit est ce qui est configuré et ce
 * qui le borne, jamais un nombre d'enregistrements fabriqué.
 */
export function describeRefreshCost(
  templateType: string,
  params: Record<string, unknown>,
): string | null {
  const str = (key: string) => {
    const v = params[key];
    return v == null ? '' : String(v).trim();
  };
  const interval = num(params, 'refreshIntervalMs');
  const cadence = interval !== null && interval > 0
    ? `at most every ${formatDurationMs(interval)}`
    : 'on every refresh cycle';

  if (templateType === 'TOPIC_COUNT_DELTA') {
    const countBy = str('countBy').toUpperCase();
    const named = str('leftTopic') !== '' && str('rightTopic') !== '';
    const plainCount = (key: string) => {
      const sql = str(key);
      return sql === '' || /^select\s+count\s*\(\s*\*\s*\)\s*(?:as\s+`?\w+`?\s*)?from\s+`?\w[\w.]*`?\s*;?$/is.test(sql);
    };
    const byOffsets = countBy === 'OFFSETS'
      || ((countBy === '' || countBy === 'AUTO') && named && plainCount('leftSql') && plainCount('rightSql'));
    if (byOffsets) {
      return `Reads no record: two listOffsets calls, ${cadence}.`;
    }
    return `Reads records on both sides, ${cadence}. A side the direct reader answers is bounded `
      + 'by its own aggregate ceiling rather than by Max rows / side — count these topics by '
      + 'offsets to read nothing at all.';
  }

  if (templateType === 'TOPIC_TRANSIT_LATENCY') {
    const rows = num(params, 'maxRowsPerSide') ?? 10_000;
    const windowMs = num(params, 'windowMs');
    const scope = windowMs !== null && windowMs > 0
      ? `the same ${formatDurationMs(windowMs)} on each side, up to ${count(rows)} row(s) each`
      : `up to ${count(rows)} row(s) on each side`;
    return `Reads ${scope}, ${cadence}.`;
  }

  if (templateType === 'CONSUMER_TIME_LAG') {
    return `Reads one record per lagging partition — none when the group is caught up — ${cadence}.`;
  }

  return null;
}


// ── Les composantes dans le temps ──────────────────────────────────────────

/** Une série à tracer : sa clé, son nom, sa couleur, ses points alignés sur `history`. */
export interface ComponentSeries {
  key: string;
  label: string;
  color: string;
  values: (number | null)[];
  /** Formate une valeur de cette série pour l'infobulle — une durée n'est pas un décompte. */
  format: (value: number) => string;
}

/**
 * Une couleur par série, fixe par clé.
 *
 * Fixe, pas tirée de l'ordre : une carte qui recolore ses lignes selon ce que ce
 * rafraîchissement-ci a mesuré est une carte qu'on ne peut pas relire d'un coup d'œil.
 */
const COMPONENT_COLORS: Record<string, string> = {
  leftValue: '#a3adff',
  rightValue: '#7fd1b9',
  avgLatencyMs: '#a3adff',
  p95LatencyMs: '#f5c264',
  maxLatencyMs: '#f58c8c',
  maxLagMs: '#f5c264',
  avgLagMs: '#a3adff',
};

/**
 * Les séries traçables d'une métrique, ou rien.
 *
 * `history` porte la valeur de la métrique, qui pour un gabarit à deux requêtes est la
 * *comparaison* et non la mesure : sur un écart c'est la différence, et ce qu'un opérateur a besoin
 * de voir bouger, ce sont les deux comptes. Le serveur garde ces séries alignées index par index
 * sur `history` et met `null` là où il n'a rien mesuré ; ici on ne fait que les nommer et les
 * colorer — **rien n'est complété, rien n'est interpolé**, un trou reste un trou.
 *
 * Rien n'est renvoyé sous deux points : une ligne d'un seul point n'est pas une évolution.
 */
export function componentSeries(
  componentHistory: Record<string, (number | null)[]> | null,
  historyLength: number,
): ComponentSeries[] {
  if (!componentHistory || historyLength < 2) return [];
  const out: ComponentSeries[] = [];
  for (const [key, values] of Object.entries(componentHistory)) {
    if (!Array.isArray(values) || values.length < 2) continue;
    // Une série désalignée ne se trace pas : l'index n'y voudrait plus dire « ce
    // rafraîchissement-là », et deux lignes décalées se lisent comme un décalage réel.
    if (values.length !== historyLength) continue;
    if (!values.some(v => typeof v === 'number' && Number.isFinite(v))) continue;
    out.push({
      key,
      label: COMPONENT_LABELS[key] ?? key,
      color: COMPONENT_COLORS[key] ?? '#8f93a3',
      values: values.map(v => (typeof v === 'number' && Number.isFinite(v) ? v : null)),
      format: DURATION_KEYS.has(key) ? formatDurationMs : (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 2 }),
    });
  }
  return out;
}
