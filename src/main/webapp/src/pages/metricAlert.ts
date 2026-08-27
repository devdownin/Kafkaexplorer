// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Comment un seuil devient un verdict, et comment il devient une règle Prometheus.
 *
 * Deux choses manquaient et elles tiennent ensemble. Le seuil n'avait **qu'une direction** —
 * `getStatus` ne connaissait que `>=` — alors qu'une opération `RATIO` est saine à 1.0 et malade
 * en *descendant* : cette opération était offerte dans le formulaire sans pouvoir être alertée dans
 * le sens qui compte. Et la page s'arrêtait une marche avant ce à quoi ces métriques servent : tout
 * ceci existe pour qu'une alerte se déclenche, et la PromQL n'était écrite nulle part.
 */

import type { MetricConfig } from '../api/types';

export type ThresholdDirection = 'above' | 'below';

/**
 * De quel côté du seuil se trouve le problème — **déduit de l'ordre des deux seuils, pas d'un
 * réglage de plus**.
 *
 * Critique est toujours « pire » qu'avertissement : c'est la seule chose qu'on sache avec certitude
 * de la paire. Donc un critique *plus bas* que l'avertissement dit que la métrique se lit vers le
 * bas — 0.99 puis 0.95 sur un ratio — et un critique plus haut, vers le haut. Aucun champ à
 * persister, aucun des quarante-trois sites de construction de `MetricConfig` à toucher, et la
 * convention se vérifie en regardant les deux nombres.
 *
 * Ce n'est pas de la magie **à condition d'être affiché** : le formulaire énonce la direction que
 * la paire saisie implique, sans quoi une faute de frappe la retournerait en silence.
 */
export function thresholdDirection(
  warningThreshold: number | null,
  criticalThreshold: number | null,
): ThresholdDirection {
  if (warningThreshold === null || criticalThreshold === null) return 'above';
  return criticalThreshold < warningThreshold ? 'below' : 'above';
}

export type MetricStatus = 'error' | 'critical' | 'warning' | 'ok' | 'pending';

/**
 * Le verdict de la carte, dans la direction que les seuils impliquent.
 *
 * Extrait de `Metrics.tsx`, où il n'était pas testé, parce qu'il porte maintenant une règle qu'on
 * ne peut pas vérifier en la lisant.
 */
export function gradeMetric(m: MetricConfig): MetricStatus {
  if (m.errorMessage) return 'error';
  if (m.lastValue === null) return 'pending';
  const direction = thresholdDirection(m.warningThreshold, m.criticalThreshold);
  const breached = (threshold: number) =>
    direction === 'below' ? m.lastValue! <= threshold : m.lastValue! >= threshold;
  if (m.criticalThreshold !== null && breached(m.criticalThreshold)) return 'critical';
  if (m.warningThreshold !== null && breached(m.warningThreshold)) return 'warning';
  return 'ok';
}

/** La phrase que le formulaire affiche sous les deux seuils, pour que la règle ne soit pas tacite. */
export function describeThresholdDirection(
  warningThreshold: number | null,
  criticalThreshold: number | null,
): string | null {
  if (warningThreshold === null && criticalThreshold === null) return null;
  if (warningThreshold === null || criticalThreshold === null) {
    return 'Fires when the value rises to the threshold. Set both to read the metric downwards '
      + 'instead — a critical below the warning says the problem is a value that falls.';
  }
  return thresholdDirection(warningThreshold, criticalThreshold) === 'below'
    ? 'Critical is below warning, so this metric is read downwards: it fires when the value falls '
      + 'to the threshold. That is the direction a ratio breaks in.'
    : 'Fires when the value rises to the threshold. A critical below the warning would read the '
      + 'metric downwards instead.';
}

/** Une règle proposée, avec ce qu'elle vise et ce qu'elle suppose. */
export interface AlertRule {
  title: string;
  promql: string;
  note: string;
}

export interface AlertAdvice {
  rules: AlertRule[];
  /** Pourquoi il n'y a pas de règle, quand il n'y en a pas. Jamais une liste vide sans raison. */
  unavailable: string | null;
}

/** Combien de temps une valeur peut être gelée avant qu'une alerte cesse de vouloir dire quelque chose. */
function freshnessSeconds(params: Record<string, unknown> | null): { seconds: number; assumed: boolean } {
  const raw = params?.refreshIntervalMs;
  const interval = typeof raw === 'number' ? raw : Number(raw);
  if (Number.isFinite(interval) && interval > 0) {
    return { seconds: Math.max(120, Math.ceil((interval * 4) / 1000)), assumed: false };
  }
  return { seconds: 120, assumed: true };
}

function guard(metricId: string, seconds: number): string {
  return `time() - explorer_metric_last_success_timestamp_seconds{metric_id="${metricId}"} < ${seconds}`;
}

/**
 * La règle Prometheus que cette métrique appelle, ou la raison qu'il n'y en ait pas.
 *
 * Deux règles la gouvernent, et la seconde est celle qui compte.
 *
 * **L'alerte doit comparer ce que la carte compare.** Pour un `GAUGE`, la série exportée *est* le
 * nombre affiché. Pour un `COUNTER`, la série est un cumul de deltas et le nombre de la carte est
 * la valeur de la requête : ce ne sont pas la même grandeur, et poser un seuil sur l'une en
 * croyant l'autre est exactement la sorte d'affirmation que ce dépôt refuse d'écrire. Pour un
 * `HISTOGRAM` ou un `SUMMARY`, ce qui est exporté est une distribution. Dans ces trois cas on ne
 * propose pas de règle : on nomme ce qui est exporté et on dit pourquoi ce n'est pas ce chiffre-là.
 *
 * **Et une valeur gelée n'est pas une valeur.** Un rafraîchissement en échec garde la précédente —
 * délibérément — donc `value > N` se déclenche pareil que la condition soit réelle et bloquée ou
 * simplement plus mesurée. `explorer_metric_last_success_timestamp_seconds` est ce qui date la
 * mesure, et il est dans chaque règle proposée.
 */
export function buildAlertRule(metric: MetricConfig): AlertAdvice {
  const id = metric.id;
  const type = (metric.type ?? 'GAUGE').toUpperCase();
  const threshold = metric.criticalThreshold ?? metric.warningThreshold;

  if (!id) {
    return { rules: [], unavailable: 'Save the metric first: a rule names it by its id.' };
  }
  if (type === 'COUNTER') {
    return {
      rules: [],
      unavailable: 'A counter exports explorer_metric_counter_total, which accumulates the deltas '
        + 'between refreshes — not the number on this card. Alert on its rate() rather than on a '
        + 'threshold copied from here.',
    };
  }
  if (type === 'HISTOGRAM' || type === 'SUMMARY') {
    const series = type === 'HISTOGRAM'
      ? `explorer_metric_histogram_bucket{metric_id="${id}"}`
      : `explorer_metric_summary{metric_id="${id}",quantile="0.95"}`;
    return {
      rules: [],
      unavailable: `A ${type.toLowerCase()} exports a distribution (${series}), not the single `
        + 'number on this card. Alert on one of its quantiles.',
    };
  }
  if (threshold === null || threshold === undefined) {
    return { rules: [], unavailable: 'Set a warning or critical threshold: a rule needs a number.' };
  }

  const direction = thresholdDirection(metric.warningThreshold, metric.criticalThreshold);
  const comparison = direction === 'below' ? '<=' : '>=';
  const { seconds, assumed } = freshnessSeconds(metric.templateParams);
  const staleness = assumed
    ? 'The freshness window assumes the default refresh cadence; widen it if this deployment polls '
      + 'more slowly.'
    : 'The freshness window is four times this metric’s own refresh interval.';

  const rules: AlertRule[] = [{
    title: metric.criticalThreshold !== null ? 'Critical' : 'Warning',
    promql: `explorer_metric_gauge{metric_id="${id}"} ${comparison} ${threshold}\n  and ${guard(id, seconds)}`,
    note: `Fires when the value ${direction === 'below' ? 'falls to' : 'reaches'} ${threshold}. `
      + 'The second half is what stops a frozen gauge firing: a failed refresh keeps the previous '
      + `value, so without it the alert cannot tell a real breach from one nobody measured. ${staleness}`,
  }];

  // Sur une latence, la moyenne est ce que la carte affiche et la queue est ce qui réveille.
  const p95 = metric.lastSummary?.p95LatencyMs;
  if (metric.templateType === 'TOPIC_TRANSIT_LATENCY' && typeof p95 === 'number') {
    rules.push({
      title: 'On the p95 instead',
      promql: `explorer_metric_correlation_latency_p95_ms{metric_id="${id}"} ${comparison} ${threshold}\n  and ${guard(id, seconds)}`,
      note: 'The gauge above carries the average, which holds still while the worst decile doubles '
        + '— the case this template exists to catch. The threshold is the same number and may want '
        + 'raising: a p95 is above an average by construction.',
    });
  }

  return { rules, unavailable: null };
}
