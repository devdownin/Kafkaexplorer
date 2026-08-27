// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce qu'un nom de topic dit du rôle de ce topic — reprise, file d'attente morte.
 *
 * Ces règles étaient écrites en clair, à quatre endroits et dans deux fichiers : `Dashboard.tsx`
 * les portait deux fois (l'état de la ligne, puis le filtre « Hide … ») et `TopicExplorer.tsx` une
 * troisième pour son badge, la marque de reprise en étant une quatrième. C'est exactement le motif
 * que ce dépôt a déjà payé une fois avec les quatre littéraux `'internal.'` recopiés dans le
 * navigateur, rassemblés depuis dans `internalTopics.ts` : quatre copies, c'est quatre occasions
 * pour la cinquième d'être écrite autrement, et la divergence ne se voit pas — un topic
 * simplement pas reconnu ressemble à un topic ordinaire.
 *
 * Rien ici n'est configurable, et c'est délibéré : ce sont des conventions de nommage de
 * l'écosystème, pas des réglages de ce déploiement. Le jour où il faut les paramétrer, c'est par
 * `DashboardResponse` que la valeur voyagera, comme `internalPrefix`, plutôt que par une seconde
 * copie côté navigateur.
 */

/**
 * Le marqueur de file d'attente morte, en suffixe.
 *
 * **Deux orthographes, et l'omission de la seconde était un vrai trou.** Seul
 * `DeadLetterPublishingRecoverer` de Spring Kafka écrit `.DLT` ; Spring Cloud Stream et la plus
 * grande partie de l'écosystème disent `.DLQ`. La règle ne connaissait que la première, donc la
 * bascule « Hide … » et le badge laissaient passer l'autre moitié en silence — le symptôme même
 * contre lequel `isRetryTopic` a été écrit avec `includes` plutôt qu'avec un suffixe.
 *
 * **Trois séparateurs, pour la même raison.** `orders-dlq` et `orders_dlq` sont aussi répandus que
 * `orders.dlq` ; ne reconnaître que le point aurait répété la demi-couverture d'un cran. Le
 * séparateur reste exigé, lui : il tient la règle en *suffixe*, ce qu'elle a toujours été, là où
 * la reprise se cherche n'importe où dans le nom.
 */
const DEAD_LETTER_SUFFIX = /[._-](dlt|dlq)$/i;

/** Un topic de reprise, repéré à son nom.
 *
 * `includes` et non un suffixe comme la règle voisine, et ce n'est pas une incohérence : une file
 * d'attente morte est un suffixe par convention, alors qu'une reprise se nomme aussi bien
 * `orders.retry.5m` que `retry-orders` — chercher un suffixe n'en trouverait qu'une partie, en
 * silence.
 *
 * C'est une *marque*, jamais un filtre : un topic de reprise reste vide, sain ou mort par
 * ailleurs, donc l'état n'est pas la bonne colonne pour le dire et la ligne n'est pas retirée.
 */
export function isRetryTopic(topic: string): boolean {
  return topic.toLowerCase().includes('retry');
}

/** Un topic de file d'attente morte, quelle que soit son orthographe. */
export function isDeadLetterTopic(topic: string): boolean {
  return DEAD_LETTER_SUFFIX.test(topic);
}

/**
 * Comment nommer ce topic-là sur un badge : `DLT` ou `DLQ`, l'orthographe qu'il porte réellement.
 *
 * Le badge disait `DLT` pour tout le monde, ce qui devient une petite contre-vérité dès que la
 * règle reconnaît les deux — un topic nommé `orders.dlq` étiqueté `DLT` affirme une convention que
 * son producteur n'a pas suivie. Rendre le suffixe mesuré coûte une fonction et n'invente rien.
 *
 * `null` quand le topic n'en est pas un, pour que l'appelant n'ait pas à poser la question deux
 * fois.
 */
export function deadLetterLabel(topic: string): 'DLT' | 'DLQ' | null {
  const match = DEAD_LETTER_SUFFIX.exec(topic);
  return match ? (match[1].toUpperCase() as 'DLT' | 'DLQ') : null;
}
