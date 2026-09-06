// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Le lien qui mène d'une file d'échec à l'alerte qui la surveille.
 *
 * L'écran de supervision repère la file qui se remplit, puis laissait l'opérateur revenir à
 * `/metrics` et réécrire à la main le rapport qu'il venait de lire. C'est la boucle
 * détection → alerte qui manquait, et elle ne demande aucune mesure nouvelle : la métrique
 * proposée est **exactement la seconde courbe**, `TOPIC_COUNT_DELTA` en `RATIO` de la file sur sa
 * source.
 *
 * Le paramétrage n'est pas choisi ici, il est repris de ce que `MetricSuggestionService` écrit
 * déjà pour ses propres cartes d'écart : `countBy: OFFSETS` — un comptage de topic entier est de
 * la métadonnée, pas un scan — et `window: SINCE_LAST_REFRESH`, parce qu'un total depuis toujours
 * se désensibilise à mesure que l'historique grossit, au point qu'une panne totale d'une heure
 * passe sous n'importe quel seuil. Deux copies de ces choix finiraient par diverger ; celle-ci
 * l'assume en le disant.
 *
 * **Rien n'est créé.** Le lien ouvre l'éditeur pré-rempli, l'opérateur prévisualise et enregistre
 * — la règle que le panneau de propositions applique déjà, et pour la même raison : une page qui
 * crée des métriques en un clic finit par en créer qu'on n'a pas voulues.
 */

/** Ce qu'un lien vers `/metrics` transporte pour ouvrir l'éditeur déjà rempli. */
export interface QueueMetricDraft {
  queue: string;
  source: string;
}

/** Le préfixe des paramètres d'URL, un seul endroit pour les écrire et pour les lire. */
export const QUEUE_PARAM = 'fromQueue';
export const SOURCE_PARAM = 'againstSource';

/**
 * L'URL de l'éditeur, pré-rempli pour cette file.
 *
 * Par la query string plutôt que par l'état de navigation, comme tout le reste de cette
 * application : un lien se partage, se met en favori et se rejoue au rechargement, là où un état
 * de navigation disparaît au premier F5 — et l'opérateur qui envoie « regarde, il faut alerter
 * là-dessus » envoie une URL.
 */
export function metricDraftLink(queue: string, source: string): string {
  const params = new URLSearchParams({ [QUEUE_PARAM]: queue, [SOURCE_PARAM]: source });
  return `/metrics?${params.toString()}`;
}

/** Ce que l'URL portait, ou `null` — les deux moitiés sont exigées, une seule ne dit rien. */
export function readQueueDraft(search: string | URLSearchParams): QueueMetricDraft | null {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  const queue = params.get(QUEUE_PARAM)?.trim();
  const source = params.get(SOURCE_PARAM)?.trim();
  return queue && source ? { queue, source } : null;
}

const countSql = (topic: string) =>
  `SELECT COUNT(*) AS metric_value\nFROM ${topic.replace(/[.-]/g, '_')}`;

/**
 * Le brouillon de métrique correspondant : la part de la source qui finit dans cette file.
 *
 * `RATIO` et non `PERCENT_GAP` — l'écart entre deux étapes d'un même flux et la part qui échoue
 * sont deux questions différentes, et `RATIO` est celle que la courbe dessine : la file divisée
 * par sa source, refusée quand la source n'a rien produit, ce que le module de supervision refuse
 * déjà de son côté. La file est à **gauche** : `RATIO` divise la gauche par la droite.
 *
 * Aucun seuil n'est proposé, et c'est délibéré : les cartes du panneau de propositions posent des
 * seuils qui sont des multiples de quelque chose de mesuré et le disent, alors qu'ici rien n'a été
 * mesuré sur la durée — un chiffre rond inventé ici serait exactement ce que ce panneau refuse
 * d'écrire. L'opérateur pose le sien devant la courbe qu'il vient de lire.
 */
export function queueMetricDraft(draft: QueueMetricDraft): Record<string, unknown> {
  return {
    name: `dlq_share_${draft.queue.replace(/[^A-Za-z0-9]+/g, '_').toLowerCase()}`,
    type: 'GAUGE',
    sql: null,
    description: `Share of ${draft.source} that ends up in ${draft.queue} — the failure rate the `
      + 'Dead Letter screen draws, as a series. Counted from offsets, over what each topic produced '
      + 'since the previous refresh.',
    templateType: 'TOPIC_COUNT_DELTA',
    templateParams: {
      leftSql: countSql(draft.queue),
      rightSql: countSql(draft.source),
      operation: 'RATIO',
      countBy: 'OFFSETS',
      window: 'SINCE_LAST_REFRESH',
      leftTopic: draft.queue,
      rightTopic: draft.source,
    },
    labelTopic: draft.queue,
    warningThreshold: null,
    criticalThreshold: null,
  };
}
