// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce que les derniers enregistrements d'une file disent de la panne.
 *
 * L'écran répondait « ça se remplit » et « à quel taux » ; il ne disait pas **de quoi**. C'est
 * pourtant la question suivante dans tous les cas, et celle qui décide si l'incident est un
 * service en panne, un lot de messages malformés ou une seule clé qui boucle.
 *
 * Ce module regroupe un échantillon d'enregistrements par la valeur d'un champ. Trois décisions le
 * tiennent, et toutes les trois viennent de ce qu'un échantillon n'est pas une mesure :
 *
 * 1. **C'est un échantillon, et il est nommé comme tel.** `GET /api/topic/{name}` rend au plus
 *    vingt enregistrements — les plus récents quand on le lui demande. Vingt, ce n'est pas la
 *    fenêtre que les courbes couvrent, donc rien ici ne se présente comme une distribution : on
 *    répond « de quoi parlent les vingt derniers », qui est exactement la question qu'on se pose en
 *    ouvrant une file, et jamais « 40 % des échecs sont des timeouts ».
 * 2. **Le champ est proposé, jamais deviné en silence.** Les conventions divergent
 *    (`failure_reason` dans le corps, `exception` en en-tête, `original-topic` pour savoir d'où ça
 *    vient), donc les candidats sont classés par ce que leur nom promet et l'opérateur peut en
 *    choisir un autre — le classement est une commodité, pas une affirmation.
 * 3. **Un champ absent d'un enregistrement compte comme absent**, sous son propre libellé, au lieu
 *    d'être écarté de l'échantillon. Sinon un champ présent sur deux messages sur vingt donnerait
 *    « 100 % de timeouts » sur un total de deux, ce qui est la façon la plus courante de mentir
 *    avec un décompte juste.
 */

import type { TopicMessage } from '../api/types';

/** Un champ sur lequel l'échantillon peut être regroupé. */
export interface ReasonField {
  /** Clé technique : `header:exception` ou `field:failure_reason`. */
  id: string;
  /** Ce qui s'affiche : le nom seul. */
  name: string;
  origin: 'header' | 'payload';
  /** Enregistrements de l'échantillon qui portent ce champ. */
  present: number;
  /** Valeurs distinctes prises — un champ qui n'en a qu'une ne regroupe rien. */
  distinct: number;
}

/**
 * Les noms qui promettent une cause, du plus explicite au moins.
 *
 * Un classement et pas un filtre : un champ qui ne ressemble à rien reste proposé, parce que la
 * convention d'un déploiement n'a aucune raison de figurer dans cette liste. Ce qu'il gagne à être
 * ordonné, c'est que le panneau s'ouvre sur le bon champ neuf fois sur dix sans rien demander.
 */
const PROMISING = [
  /^(failure_?reason|failurecause)$/i,
  /reason/i,
  /(exception|error|cause|stack)/i,
  /(original[._-]?topic|source[._-]?topic)/i,
  /(retry[._-]?attempt|attempts?)$/i,
  /(status|state|type|code)$/i,
];

function promise(name: string): number {
  const index = PROMISING.findIndex(rule => rule.test(name));
  return index === -1 ? PROMISING.length : index;
}

/** Le corps décodé d'un enregistrement, à plat sur un niveau. `null` quand ce n'est pas du JSON. */
function payloadFields(value: string | null): Record<string, unknown> | null {
  if (!value) return null;
  const trimmed = value.trim();
  if (!trimmed.startsWith('{')) return null;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    // Un enregistrement illisible est précisément ce qu'une file de rebut contient : il ne doit
    // pas faire échouer la lecture des dix-neuf autres.
    return null;
  }
}

/** Ce qu'un champ vaut sur un enregistrement, ou `undefined` s'il n'y figure pas. */
export function fieldValue(message: TopicMessage, field: ReasonField): string | undefined {
  if (field.origin === 'header') {
    const raw = message.headers?.[field.name];
    return raw === undefined || raw === null ? undefined : raw;
  }
  const body = payloadFields(message.value);
  if (!body || !(field.name in body)) return undefined;
  const raw = body[field.name];
  if (raw === null || raw === undefined) return undefined;
  return typeof raw === 'object' ? JSON.stringify(raw) : String(raw);
}

/**
 * Les champs sur lesquels cet échantillon peut être regroupé, les plus prometteurs d'abord.
 *
 * Un champ à valeur unique par enregistrement — un identifiant, un horodatage — est écarté : il
 * produirait autant de groupes que de messages, ce qui n'est pas un regroupement. Le seuil est
 * « autant de valeurs distinctes que d'enregistrements porteurs, et plus de deux » : deux
 * enregistrements portant deux valeurs différentes restent une information sur un si petit
 * échantillon.
 */
export function reasonFields(messages: TopicMessage[]): ReasonField[] {
  const seen = new Map<string, { origin: 'header' | 'payload'; name: string; values: Set<string> }>();
  const note = (origin: 'header' | 'payload', name: string, value: string) => {
    const id = `${origin === 'header' ? 'header' : 'field'}:${name}`;
    const entry = seen.get(id) ?? { origin, name, values: new Set<string>() };
    entry.values.add(value);
    seen.set(id, entry);
  };

  const presence = new Map<string, number>();
  for (const message of messages) {
    for (const [name, raw] of Object.entries(message.headers ?? {})) {
      if (raw === null) continue;
      note('header', name, raw);
      presence.set(`header:${name}`, (presence.get(`header:${name}`) ?? 0) + 1);
    }
    const body = payloadFields(message.value);
    for (const [name, raw] of Object.entries(body ?? {})) {
      if (raw === null || raw === undefined) continue;
      note('payload', name, typeof raw === 'object' ? JSON.stringify(raw) : String(raw));
      presence.set(`field:${name}`, (presence.get(`field:${name}`) ?? 0) + 1);
    }
  }

  return [...seen.entries()]
    .map(([id, entry]) => ({
      id,
      name: entry.name,
      origin: entry.origin,
      present: presence.get(id) ?? 0,
      distinct: entry.values.size,
    }))
    .filter(field => field.distinct > 1 || field.present > 1)
    .filter(field => !(field.distinct === field.present && field.present > 2))
    .sort((a, b) =>
      promise(a.name) - promise(b.name)
      /*
       * À promesse égale, celui qui **sépare** l'échantillon passe devant. Vu sur la capture : un
       * `event-type` constant sur tous les enregistrements gagnait sur son voisin et affichait
       * « order.shipped 100 % », ce qui est exact et ne dit rien. Un champ à valeur unique reste
       * proposé — sur une file dont les vingt derniers échecs sont tous des timeouts, « une seule
       * valeur » *est* la réponse — mais il ne passe plus devant un champ qui distingue.
       */
      || (b.distinct > 1 ? 1 : 0) - (a.distinct > 1 ? 1 : 0)
      || b.present - a.present
      || a.name.localeCompare(b.name));
}

/** Une valeur prise par le champ, et combien d'enregistrements la portent. */
export interface ReasonGroup {
  value: string;
  count: number;
  /** Part de l'échantillon **entier**, absents compris — jamais des seuls porteurs. */
  percent: number;
  /** Vrai pour la ligne des enregistrements où le champ ne figure pas. */
  missing: boolean;
}

/** Ce que l'on écrit là où le champ est absent : une catégorie, pas un trou. */
export const MISSING_LABEL = 'not carried by the record';

/**
 * L'échantillon regroupé par ce champ, la valeur la plus fréquente d'abord.
 *
 * Les absents forment une ligne comme les autres et sont comptés dans le dénominateur : c'est ce
 * qui empêche « 100 % de timeouts » sur deux enregistrements porteurs parmi vingt. Ils restent en
 * dernier à égalité de compte, parce qu'une absence n'est pas une cause.
 */
export function groupByField(messages: TopicMessage[], field: ReasonField): ReasonGroup[] {
  if (messages.length === 0) return [];
  const counts = new Map<string, number>();
  let missing = 0;
  for (const message of messages) {
    const value = fieldValue(message, field);
    if (value === undefined) missing++;
    else counts.set(value, (counts.get(value) ?? 0) + 1);
  }

  const groups: ReasonGroup[] = [...counts.entries()].map(([value, count]) => ({
    value,
    count,
    percent: (count / messages.length) * 100,
    missing: false,
  }));
  if (missing > 0) {
    groups.push({
      value: MISSING_LABEL,
      count: missing,
      percent: (missing / messages.length) * 100,
      missing: true,
    });
  }
  return groups.sort((a, b) =>
    b.count - a.count
    || Number(a.missing) - Number(b.missing)
    || a.value.localeCompare(b.value));
}

/**
 * Ce que le panneau affirme, en une phrase — et ce qu'il n'affirme pas.
 *
 * La portée est dans la phrase plutôt qu'en note de bas de page : « les 20 derniers » et « sur la
 * fenêtre » sont deux mesures différentes, et l'écran porte les deux côte à côte.
 */
export function describeSample(messages: TopicMessage[], field: ReasonField | null): string {
  if (messages.length === 0) return 'No record could be read from this queue.';
  const head = `The ${messages.length} most recent record${messages.length === 1 ? '' : 's'}`;
  if (!field) {
    return `${head} carry no field that groups them — every value is distinct, or there is only one.`;
  }
  return `${head}, grouped by ${field.name}. A sample of what is arriving, not a distribution over the window the curves cover.`;
}
