/**
 * Logique de la comparaison côte à côte — appariement, diff, filtrage.
 *
 * La page lisait `samples` comme un `string[]`. Ce n'en est plus un : depuis que la recherche de
 * topic existe, `GET /api/topic/{name}` renvoie des `TopicMessage`, avec les coordonnées du
 * record. Le typage du front n'a pas suivi, alors `tryParse(objet)` échouait — `JSON.parse` reçoit
 * « [object Object] » — et la carte retombait sur sa branche « pas du JSON », qui rend `{sample}`
 * tel quel. Rendre un objet comme enfant React lève l'erreur #31, et la page entière tombait sur
 * l'ErrorBoundary dès qu'on cliquait sur « Run compare ».
 *
 * Le deuxième défaut est dans l'appariement. Chaque panneau filtrait sa propre liste
 * (`displayA`, `displayB`) puis recevait la liste **non filtrée** de l'autre côté, indexée par la
 * position dans la liste filtrée : avec « Diff only », chaque message était donc comparé à un
 * message qui n'était pas son vis-à-vis. Une seule liste de paires alignées supprime la classe
 * d'erreur — les deux panneaux itèrent désormais la même chose.
 *
 * Fonctions pures → testables sans monter la page.
 */

import type { TopicMessage } from '../components/topic/topicSearch';

export type { TopicMessage };

export interface ComparePair {
  /** Rang dans la comparaison, conservé à travers le filtrage : c'est le lien entre les deux panneaux. */
  index: number;
  a?: TopicMessage;
  b?: TopicMessage;
  /** Les deux valeurs diffèrent, ou l'un des deux côtés n'a pas de message à cette position. */
  differs: boolean;
}

/** JSON ou rien. Un payload non-JSON n'est pas une erreur : il se compare comme du texte. */
export function tryParse(value: string | null | undefined): Record<string, unknown> | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value);
    // Un tableau ou un scalaire ne se compare pas champ par champ : on retombe sur le texte brut.
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

/** Le texte d'un message, quel que soit ce que le serveur a renvoyé. */
export function messageText(message: TopicMessage | undefined): string {
  return message?.value ?? '';
}

/** Statut de chaque champ entre deux objets JSON. */
export function diffFields(
  a: Record<string, unknown> | null,
  b: Record<string, unknown> | null,
): Record<string, 'added' | 'removed' | 'changed' | 'same'> {
  const result: Record<string, 'added' | 'removed' | 'changed' | 'same'> = {};
  const left = a ?? {};
  const right = b ?? {};
  new Set([...Object.keys(left), ...Object.keys(right)]).forEach((k) => {
    if (!(k in left)) result[k] = 'added';
    else if (!(k in right)) result[k] = 'removed';
    else if (JSON.stringify(left[k]) !== JSON.stringify(right[k])) result[k] = 'changed';
    else result[k] = 'same';
  });
  return result;
}

/** Deux valeurs sont-elles différentes ? Comparaison structurelle quand les deux sont du JSON. */
export function valuesDiffer(a: TopicMessage | undefined, b: TopicMessage | undefined): boolean {
  if (!a || !b) return true;
  const parsedA = tryParse(a.value);
  const parsedB = tryParse(b.value);
  if (parsedA && parsedB) return JSON.stringify(parsedA) !== JSON.stringify(parsedB);
  return messageText(a) !== messageText(b);
}

/**
 * Apparie par position, sur la longueur du plus grand des deux côtés.
 *
 * Le rang de fin de liste où un seul côté a un message est conservé : « B a trois messages de
 * plus que A » est une différence, et la masquer donnerait deux panneaux de hauteurs
 * inexplicables.
 */
export function pairSamples(a: TopicMessage[], b: TopicMessage[]): ComparePair[] {
  const length = Math.max(a.length, b.length);
  return Array.from({ length }, (_, index) => ({
    index,
    a: a[index],
    b: b[index],
    differs: valuesDiffer(a[index], b[index]),
  }));
}

/** « Diff only » filtre les paires, pas chaque colonne séparément — sans quoi elles se désalignent. */
export function visiblePairs(pairs: ComparePair[], diffOnly: boolean): ComparePair[] {
  return diffOnly ? pairs.filter((pair) => pair.differs) : pairs;
}

/** Nombre de positions où les deux côtés ont un message et où ils diffèrent. */
export function countDiffs(pairs: ComparePair[]): number {
  return pairs.filter((pair) => pair.a && pair.b && pair.differs).length;
}

/** Positions comparables — celles où les deux côtés ont un message. */
export function countCompared(pairs: ComparePair[]): number {
  return pairs.filter((pair) => pair.a && pair.b).length;
}

/** Coordonnées du record, pour que chaque carte dise de quel message on parle. */
export function coordinates(message: TopicMessage | undefined): string {
  if (!message) return '';
  return `p${message.partition} · offset ${message.offset}`;
}
