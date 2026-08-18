// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Logique pure de la page Data Model : sélection des topics, aller-retour par l'URL,
 * dimensionnement des nœuds-tables et disposition du graphe. La page ne fait que rendre ce que
 * ce module décide — même partage que Stream Flow (`streamFlow.ts`) et le Topic Explorer
 * (`topicSearch.ts`), et pour la même raison : c'est ce qui rend la logique testable.
 */

import type {
  DataModelEntity,
  DataModelRelation,
  DataModelResponse,
  RelationConfidence,
} from '../api/types';

/** Miroir du plafond serveur (`DataModelService.MAX_TOPICS`) : l'UI prévient avant d'envoyer. */
export const MAX_TOPICS = 30;

// ── Sélection des topics ──────────────────────────────────────────────────────

/** Filtre insensible à la casse, topics internes de l'explorateur exclus du « tout cocher ». */
export function filterTopics(topics: string[], filter: string): string[] {
  const needle = filter.trim().toLowerCase();
  if (!needle) return topics;
  return topics.filter(t => t.toLowerCase().includes(needle));
}

export function toggleTopic(selection: string[], topic: string): string[] {
  return selection.includes(topic)
    ? selection.filter(t => t !== topic)
    : [...selection, topic];
}

/**
 * Coche tout ce que le filtre montre, sans dépasser le plafond serveur : envoyer 200 topics
 * pour en voir 30 analysés serait exactement la troncature silencieuse que le serveur nomme.
 * Les topics `internal.*` — ceux que l'application s'écrit à elle-même — sont ignorés.
 */
export function selectAll(selection: string[], visible: string[]): string[] {
  const next = [...selection];
  for (const topic of visible) {
    if (topic.startsWith('internal.')) continue;
    if (next.length >= MAX_TOPICS) break;
    if (!next.includes(topic)) next.push(topic);
  }
  return next;
}

// ── Aller-retour par l'URL (même convention que Stream Flow) ──────────────────

/** `?topics=a,b,c` → sélection. Une URL partagée rejoue le modèle à l'ouverture. */
export function topicsFromQuery(search: string): string[] {
  const raw = new URLSearchParams(search).get('topics');
  if (!raw) return [];
  return [...new Set(raw.split(',').map(t => t.trim()).filter(Boolean))];
}

export function buildQuery(topics: string[]): string {
  if (topics.length === 0) return '';
  const params = new URLSearchParams();
  params.set('topics', topics.join(','));
  return `?${params.toString()}`;
}

// ── Dimensionnement des nœuds ─────────────────────────────────────────────────

export const NODE_W = 230;
export const HEADER_H = 42;
export const ROW_H = 20;
export const FOOTER_H = 8;
/** Au-delà, les colonnes restantes sont comptées plutôt que listées — un nœud n'est pas une page. */
export const MAX_COLUMNS_SHOWN = 12;

export interface DisplayedColumns {
  columns: DataModelEntity['columns'];
  /** Colonnes non listées. 0 = tout est affiché. */
  hidden: number;
}

/**
 * Les colonnes affichées d'un nœud : clés (primaire puis référentes) d'abord — ce sont elles
 * qui portent les arêtes, les cacher rendrait le graphe illisible — puis l'ordre du schéma.
 */
export function displayedColumns(entity: DataModelEntity): DisplayedColumns {
  const keys = entity.columns.filter(c => c.primaryKey || c.references);
  const rest = entity.columns.filter(c => !c.primaryKey && !c.references);
  const ordered = [...keys, ...rest];
  if (ordered.length <= MAX_COLUMNS_SHOWN) return { columns: ordered, hidden: 0 };
  return {
    columns: ordered.slice(0, MAX_COLUMNS_SHOWN),
    hidden: ordered.length - MAX_COLUMNS_SHOWN,
  };
}

/** Hauteur d'un nœud : en-tête + lignes affichées (+ la ligne « +N more » le cas échéant). */
export function entityHeight(entity: DataModelEntity): number {
  const { columns, hidden } = displayedColumns(entity);
  return HEADER_H + (columns.length + (hidden > 0 ? 1 : 0)) * ROW_H + FOOTER_H;
}

// ── Disposition ───────────────────────────────────────────────────────────────

const COL_GAP = 150;
const ROW_GAP = 50;
const MARGIN = 40;
/** Grille des nœuds sans aucune relation. */
const ORPHAN_COLS = 4;

export type Positions = Record<string, { x: number; y: number }>;

/**
 * Sépare les entités qu'au moins une relation touche de celles qu'aucune ne touche. Une seule
 * définition, parce que la page et la disposition posent la même question : la première pour
 * ranger les entités sans relation dans une section à part, la seconde pour ne pas les faire
 * entrer dans les couches.
 */
export function splitByConnectivity(
  entities: DataModelEntity[],
  relations: DataModelRelation[],
): { connected: DataModelEntity[]; isolated: DataModelEntity[] } {
  const known = new Set(entities.map(e => e.id));
  const touched = new Set<string>();
  relations.forEach(r => {
    if (!known.has(r.from) || !known.has(r.to) || r.from === r.to) return;
    touched.add(r.from);
    touched.add(r.to);
  });
  return {
    connected: entities.filter(e => touched.has(e.id)),
    isolated: entities.filter(e => !touched.has(e.id)),
  };
}

/**
 * Disposition en couches, celle du graphe de lignage : les entités que personne ne référence
 * ouvrent la première colonne, chaque relation pousse sa cible d'une colonne vers la droite.
 * Les hauteurs de nœuds sont réelles (elles varient avec le nombre de colonnes), et les
 * entités sans relation sont rangées en grille sous le graphe plutôt que d'étirer une colonne.
 */
export function computeLayout(
  entities: DataModelEntity[],
  relations: DataModelRelation[],
): Positions {
  if (entities.length === 0) return {};

  const known = new Set(entities.map(e => e.id));
  const adjacency = new Map<string, string[]>();
  const inDegree = new Map<string, number>();
  entities.forEach(e => { adjacency.set(e.id, []); inDegree.set(e.id, 0); });
  relations.forEach(r => {
    if (!known.has(r.from) || !known.has(r.to)) return;
    if (r.from === r.to) return;
    adjacency.get(r.from)!.push(r.to);
    inDegree.set(r.to, (inDegree.get(r.to) ?? 0) + 1);
  });

  const byId = new Map(entities.map(e => [e.id, e]));
  const { connected, isolated } = splitByConnectivity(entities, relations);

  const layers: string[][] = [];
  const visited = new Set<string>();
  let queue = connected.filter(e => (inDegree.get(e.id) ?? 0) === 0).map(e => e.id);
  if (queue.length === 0 && connected.length > 0) queue = [connected[0].id];

  while (queue.length > 0) {
    const layer: string[] = [];
    const next: string[] = [];
    for (const id of queue) {
      if (visited.has(id)) continue;
      visited.add(id);
      layer.push(id);
      for (const to of adjacency.get(id) ?? []) {
        if (!visited.has(to)) next.push(to);
      }
    }
    if (layer.length) layers.push(layer);
    queue = next;
  }
  // Les nœuds atteignables seulement par un cycle partagent une colonne finale.
  const leftover = connected.filter(e => !visited.has(e.id)).map(e => e.id);
  if (leftover.length) layers.push(leftover);

  const positions: Positions = {};
  let graphBottom = MARGIN;
  layers.forEach((layer, col) => {
    let y = MARGIN;
    layer.forEach(id => {
      positions[id] = { x: col * (NODE_W + COL_GAP) + MARGIN, y };
      y += entityHeight(byId.get(id)!) + ROW_GAP;
    });
    graphBottom = Math.max(graphBottom, y);
  });

  if (isolated.length > 0) {
    const gridTop = layers.length > 0 ? graphBottom + ROW_GAP : MARGIN;
    // Hauteur par rangée = le plus haut de la rangée, pour que rien ne se chevauche.
    let y = gridTop;
    for (let i = 0; i < isolated.length; i += ORPHAN_COLS) {
      const row = isolated.slice(i, i + ORPHAN_COLS);
      row.forEach((e, colIndex) => {
        positions[e.id] = { x: colIndex * (NODE_W + 60) + MARGIN, y };
      });
      y += Math.max(...row.map(entityHeight)) + ROW_GAP;
    }
  }
  return positions;
}

// ── Géométrie des arêtes ──────────────────────────────────────────────────────

/**
 * Ordonnée (relative au nœud) du centre de la ligne d'une colonne affichée, ou `null` quand la
 * colonne n'est pas listée (repliée dans « +N more ») — l'appelant retombe alors sur le centre
 * du nœud plutôt que de pointer une ligne qui n'existe pas à l'écran.
 */
export function columnRowY(entity: DataModelEntity, columnName: string | null): number | null {
  if (columnName === null) return null;
  const index = displayedColumns(entity).columns.findIndex(c => c.name === columnName);
  if (index < 0) return null;
  return HEADER_H + index * ROW_H + ROW_H / 2;
}

/** Écart du i-ème élément parmi n, centré sur zéro — ce qui sépare deux arêtes partageant une ancre. */
export function anchorSpread(index: number, count: number, gap = 10): number {
  return (index - (count - 1) / 2) * gap;
}

export interface EdgeGeometry {
  x1: number; y1: number; x2: number; y2: number;
  /** Direction du glyphe à chaque bout : +1 s'il s'étend vers +x depuis le bord du nœud. */
  d1: 1 | -1; d2: 1 | -1;
}

/**
 * La géométrie de chaque arête, ancrée **sur la ligne de la colonne** qui la porte — c'est ce
 * qui fait qu'un diagramme ER se lit sans étiquette : l'arête part de `order_id` et arrive sur
 * la ligne clé de la cible. Côté source, la colonne référente ; côté cible, `toColumn`, sinon
 * la clé détectée, sinon le centre du nœud. Les arêtes partageant une même ancre sont
 * écartées (`anchorSpread`) pour ne pas se superposer, étiquettes comprises. Une relation
 * citant une entité absente donne `null` — l'arête est sautée, jamais un crash de rendu.
 */
export function computeEdgeGeometry(
  relations: DataModelRelation[],
  entities: DataModelEntity[],
  positions: Positions,
): (EdgeGeometry | null)[] {
  const byId = new Map(entities.map(e => [e.id, e]));

  const raw = relations.map(relation => {
    const from = byId.get(relation.from);
    const to = byId.get(relation.to);
    const fromPos = positions[relation.from];
    const toPos = positions[relation.to];
    if (!from || !to || !fromPos || !toPos) return null;

    const y1 = fromPos.y + (columnRowY(from, relation.fromColumn) ?? entityHeight(from) / 2);
    const y2 = toPos.y
      + (columnRowY(to, relation.toColumn) ?? columnRowY(to, to.primaryKey) ?? entityHeight(to) / 2);

    if (toPos.x >= fromPos.x) {
      return { x1: fromPos.x + NODE_W, y1, x2: toPos.x, y2, d1: 1 as const, d2: -1 as const };
    }
    return { x1: fromPos.x, y1, x2: toPos.x + NODE_W, y2, d1: -1 as const, d2: 1 as const };
  });

  // Écartement : groupées par ancre exacte (nœud + bord + ordonnée), chaque bout séparément —
  // chaque membre sait quel bout de sa relation est ancré là, un même nœud pouvant recevoir
  // des bouts « source » et « cible » sur la même ancre.
  const groups = new Map<string, { relIndex: number; end: 1 | 2 }[]>();
  raw.forEach((g, i) => {
    if (!g) return;
    const r = relations[i];
    const ends: [string, 1 | 2][] = [
      [`${r.from}|${g.d1}|${g.y1}`, 1],
      [`${r.to}|${g.d2}|${g.y2}`, 2],
    ];
    for (const [key, end] of ends) {
      const list = groups.get(key) ?? [];
      list.push({ relIndex: i, end });
      groups.set(key, list);
    }
  });
  const offsets = new Map<number, { dy1: number; dy2: number }>();
  raw.forEach((_, i) => offsets.set(i, { dy1: 0, dy2: 0 }));
  groups.forEach(members => {
    if (members.length < 2) return;
    members.forEach((member, rank) => {
      const offset = offsets.get(member.relIndex)!;
      const dy = anchorSpread(rank, members.length);
      if (member.end === 1) offset.dy1 += dy; else offset.dy2 += dy;
    });
  });

  return raw.map((g, i) => {
    if (!g) return null;
    const { dy1, dy2 } = offsets.get(i)!;
    return { ...g, y1: g.y1 + dy1, y2: g.y2 + dy2 };
  });
}

/**
 * Patte-d'oie (côté « plusieurs », l'entité référente) : trois branches qui touchent le bord du
 * nœud. `d` pointe du bord du nœud vers l'arête. Une clé étrangère est presque toujours N→1 —
 * la notation dit la cardinalité, le style de trait reste réservé à la confiance.
 */
export function crowFootPath(x: number, y: number, d: 1 | -1): string {
  const px = x + d * 10;
  return `M${px},${y} L${x},${y - 5} M${px},${y} L${x},${y} M${px},${y} L${x},${y + 5}`;
}

/** Barre du côté « un » (l'entité référencée), perpendiculaire à l'arête près du bord du nœud. */
export function oneBarPath(x: number, y: number, d: 1 | -1): string {
  const bx = x + d * 7;
  return `M${bx},${y - 5} L${bx},${y + 5}`;
}

// ── Cadrage ───────────────────────────────────────────────────────────────────

export interface Bounds { minX: number; minY: number; maxX: number; maxY: number }

/** L'emprise du graphe, hauteurs réelles des nœuds comprises. `null` sans nœud placé. */
export function graphBounds(entities: DataModelEntity[], positions: Positions): Bounds | null {
  let bounds: Bounds | null = null;
  for (const entity of entities) {
    const pos = positions[entity.id];
    if (!pos) continue;
    const maxX = pos.x + NODE_W;
    const maxY = pos.y + entityHeight(entity);
    bounds = bounds === null
      ? { minX: pos.x, minY: pos.y, maxX, maxY }
      : {
          minX: Math.min(bounds.minX, pos.x),
          minY: Math.min(bounds.minY, pos.y),
          maxX: Math.max(bounds.maxX, maxX),
          maxY: Math.max(bounds.maxY, maxY),
        };
  }
  return bounds;
}

/**
 * La transformation qui cadre le graphe dans le viewport : centré, à l'échelle qui le fait
 * tenir — plafonnée à 1, agrandir un petit modèle au-delà de sa taille naturelle ne le rend
 * pas plus lisible. C'est le geste que Stream Flow a déjà : un reset vers `scale(1)` fixe
 * laissait la moitié d'un grand graphe hors écran.
 */
export function fitTransform(
  bounds: Bounds,
  viewportWidth: number,
  viewportHeight: number,
  padding = 40,
): { x: number; y: number; scale: number } {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxY - bounds.minY);
  const scale = Math.max(0.1, Math.min(
    1,
    (viewportWidth - 2 * padding) / width,
    (viewportHeight - 2 * padding) / height,
  ));
  return {
    scale,
    x: (viewportWidth - width * scale) / 2 - bounds.minX * scale,
    y: (viewportHeight - height * scale) / 2 - bounds.minY * scale,
  };
}

// ── Domaines de topics ────────────────────────────────────────────────────────

/**
 * Le domaine de chaque topic : le premier segment de son nom, après avoir retiré les segments
 * de tête que **tous** les topics partagent (`demo.orders.nested` et `demo.payments.captured`
 * → `orders` et `payments`, pas deux fois `demo`). Décidé sur l'ensemble, pas topic par
 * topic — c'est ce qui fait apparaître les sous-systèmes au lieu d'un groupe unique.
 */
export function topicDomains(topics: string[]): Map<string, string> {
  const segments = new Map(topics.map(t => [t, t.split(/[._-]+/).filter(Boolean)]));
  let dropped = 0;
  for (;;) {
    const heads = new Set<string>();
    let allDeep = topics.length > 0;
    for (const parts of segments.values()) {
      if (parts.length - dropped < 2) { allDeep = false; break; }
      heads.add(parts[dropped].toLowerCase());
    }
    if (!allDeep || heads.size !== 1) break;
    dropped += 1;
  }
  const domains = new Map<string, string>();
  for (const [topic, parts] of segments) {
    domains.set(topic, (parts[dropped] ?? parts[0] ?? topic).toLowerCase());
  }
  return domains;
}

/** Teintes d'en-tête par domaine : fond sombre + accent, cyclées sur les domaines triés. */
export const DOMAIN_PALETTE: { header: string; accent: string }[] = [
  { header: '#252a4a', accent: '#a3adff' },
  { header: '#1c3a2f', accent: '#7ee2a8' },
  { header: '#3a2f1c', accent: '#f5c264' },
  { header: '#3a1c2c', accent: '#f597b0' },
  { header: '#1c303a', accent: '#7ec9e2' },
  { header: '#2c1c3a', accent: '#c9a9f7' },
  { header: '#33321c', accent: '#d8d97e' },
  { header: '#3a221c', accent: '#f59782' },
];

/** Domaine → teinte, assignation stable (domaines triés) pour que deux runs se ressemblent. */
export function domainColors(domains: Map<string, string>): Map<string, { header: string; accent: string }> {
  const unique = [...new Set(domains.values())].sort();
  return new Map(unique.map((domain, i) => [domain, DOMAIN_PALETTE[i % DOMAIN_PALETTE.length]]));
}

// ── Habillage ─────────────────────────────────────────────────────────────────

/** Trait par confiance : plein quand la clé concorde, tirets sur le nom seul, pointillés sur le signal faible. */
export const CONFIDENCE_STYLE: Record<RelationConfidence, { dash: string | undefined; color: string; label: string }> = {
  HIGH:   { dash: undefined, color: '#7ee2a8', label: 'key columns agree' },
  MEDIUM: { dash: '6 4',     color: '#f5c264', label: 'name match only' },
  LOW:    { dash: '2 4',     color: '#79839a', label: 'shared key column' },
};

/**
 * Compte compacté pour l'en-tête d'un nœud, dont la largeur est fixe : `1.2M`, `12.3K`, `847`.
 * La valeur exacte voyage à côté (`<title>` sur le nœud), convention du dépôt — un nombre
 * abrégé sans son original est une information qu'on ne peut plus vérifier.
 */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  if (abs >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (abs >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(value);
}

/**
 * Ce qu'une arête dit, en une phrase : la relation, la colonne qui la porte, son grade et la
 * preuve exacte que le serveur a énoncée. Sert à la fois d'infobulle et de nom accessible —
 * une explication que seule la souris atteint n'est pas une explication.
 */
export function describeRelation(relation: DataModelRelation): string {
  const target = relation.toColumn ? ` → ${relation.to}.${relation.toColumn}` : ` → ${relation.to}`;
  return `${relation.from}.${relation.fromColumn}${target} · `
    + `${relation.confidence.toLowerCase()} confidence · ${relation.reason}`;
}

// ── Recherche d'un champ à travers les entités ────────────────────────────────

/**
 * Les colonnes que le terme désigne, par entité — « qui d'autre transporte cette clé ? » est
 * la question qu'on se pose devant ce diagramme, et la réponse est déjà côté navigateur, donc
 * elle ne coûte aucune requête. Sous-chaîne insensible à la casse, sur le chemin complet, pour
 * qu'un `customer.id` réponde autant à `customer` qu'à `id`. Un terme vide ne désigne rien :
 * tout surligner et ne rien surligner disent la même chose.
 */
export function matchingColumns(
  entities: DataModelEntity[],
  term: string,
): Map<string, Set<string>> {
  const needle = term.trim().toLowerCase();
  const matches = new Map<string, Set<string>>();
  if (!needle) return matches;
  for (const entity of entities) {
    const hit = entity.columns
      .filter(c => c.name.toLowerCase().includes(needle))
      .map(c => c.name);
    if (hit.length > 0) matches.set(entity.id, new Set(hit));
  }
  return matches;
}

/**
 * Ce que la recherche a trouvé, en toutes lettres. Zéro doit se lire comme un zéro — « aucune
 * colonne ne porte ce nom » — et non comme l'absence de réponse.
 */
export function describeColumnMatches(matches: Map<string, Set<string>>, term: string): string {
  if (!term.trim()) return '';
  if (matches.size === 0) return `No column matches “${term.trim()}”`;
  const columns = [...matches.values()].reduce((n, set) => n + set.size, 0);
  const entityWord = matches.size === 1 ? 'entity' : 'entities';
  const columnWord = columns === 1 ? 'column' : 'columns';
  return `${columns} ${columnWord} in ${matches.size} ${entityWord}`;
}

/**
 * La ligne de couverture : ce que le modèle couvre réellement. « 3 entities · 2 relations »
 * sans le « of 5 topics » laisserait un modèle partiel passer pour complet.
 */
export function describeModel(response: DataModelResponse): string {
  const entities = `${response.topicsAnalyzed} ${response.topicsAnalyzed === 1 ? 'entity' : 'entities'}`;
  const scope = response.topicsAnalyzed === response.topicsRequested
    ? entities
    : `${entities} (of ${response.topicsRequested} topics selected)`;
  const relations = `${response.relations.length} ${response.relations.length === 1 ? 'relation' : 'relations'} deduced`;
  return `${scope} · ${relations}`;
}
