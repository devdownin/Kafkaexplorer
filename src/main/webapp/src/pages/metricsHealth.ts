/**
 * État réel du module Metrics, pour la page qui l'explique.
 *
 * Le guide affichait trois pavés « SLA » — 99,98 % de disponibilité SQL, 14 ms de latence de
 * scrape, 0,02 % d'erreurs — plus un pied de page « Global Status: Operational · Ingestion:
 * 1.2M msg/sec ». Aucun de ces nombres n'était mesuré : ce sont des littéraux, sur la page même
 * qui enseigne comment mesurer. Une documentation qui invente des mesures est pire qu'une
 * documentation qui n'en donne aucune, parce qu'on agit dessus.
 *
 * Ce que l'application sait vraiment est déjà servi par `GET /api/metrics` : chaque métrique
 * porte sa dernière valeur, son dernier instant de rafraîchissement et son erreur éventuelle.
 * C'est exactement la question que les faux pavés prétendaient trancher — « est-ce que ça
 * marche ? » — et elle a une vraie réponse.
 *
 * Fonctions pures → testables sans monter la page.
 */

/** Le sous-ensemble de `MetricConfig` dont l'état dépend. */
export interface MetricStatusInput {
  lastValue: number | null;
  lastUpdateTime: number | null;
  errorMessage: string | null;
}

export interface MetricsHealth {
  total: number;
  /** Une valeur a été produite au dernier passage, sans erreur. */
  reporting: number;
  /** Le dernier passage a échoué — la métrique existe mais n'expose rien de fiable. */
  failing: number;
  /** Configurée, jamais évaluée avec succès : ni valeur, ni erreur (typiquement l'alias manquant). */
  pending: number;
  /** Rafraîchissement le plus récent, tous compteurs confondus. */
  lastRefresh: number | null;
}

export function summarizeMetrics(metrics: readonly MetricStatusInput[]): MetricsHealth {
  let reporting = 0;
  let failing = 0;
  let pending = 0;
  let lastRefresh: number | null = null;

  for (const metric of metrics) {
    // Une erreur prime sur une valeur : une métrique qui a échoué au dernier passage expose
    // encore la valeur d'avant, et la compter comme « qui rapporte » serait précisément le
    // genre de flatterie que cette page doit cesser.
    if (metric.errorMessage) failing += 1;
    else if (metric.lastValue !== null && metric.lastValue !== undefined) reporting += 1;
    else pending += 1;

    if (metric.lastUpdateTime && (lastRefresh === null || metric.lastUpdateTime > lastRefresh)) {
      lastRefresh = metric.lastUpdateTime;
    }
  }

  return { total: metrics.length, reporting, failing, pending, lastRefresh };
}

/**
 * Âge lisible d'un instant, ou `null` quand il n'y en a pas.
 *
 * `null` plutôt qu'un tiret : c'est à l'affichage de décider comment dire « jamais », et un
 * formateur qui invente « il y a 0 s » pour une absence de donnée reproduirait le défaut d'origine
 * à plus petite échelle.
 */
export function describeAge(timestamp: number | null, now: number = Date.now()): string | null {
  if (!timestamp) return null;
  const seconds = Math.max(0, Math.round((now - timestamp) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.round(hours / 24)} d ago`;
}

/** Ce que l'en-tête du panneau annonce, sans jamais parler de disponibilité qu'on n'a pas mesurée. */
export function describeHealth(health: MetricsHealth): string {
  if (health.total === 0) return 'No metric configured yet';
  if (health.failing > 0) return `${health.failing} of ${health.total} failing`;
  if (health.pending > 0) return `${health.pending} of ${health.total} waiting for a first value`;
  return `${health.reporting} of ${health.total} reporting`;
}

export type HealthTone = 'neutral' | 'success' | 'warning' | 'error';

export function healthTone(health: MetricsHealth): HealthTone {
  if (health.total === 0) return 'neutral';
  if (health.failing > 0) return 'error';
  if (health.pending > 0) return 'warning';
  return 'success';
}
