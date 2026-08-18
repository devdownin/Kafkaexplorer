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
  const touched = new Set<string>();
  entities.forEach(e => { adjacency.set(e.id, []); inDegree.set(e.id, 0); });
  relations.forEach(r => {
    if (!known.has(r.from) || !known.has(r.to)) return;
    if (r.from === r.to) return;
    adjacency.get(r.from)!.push(r.to);
    inDegree.set(r.to, (inDegree.get(r.to) ?? 0) + 1);
    touched.add(r.from);
    touched.add(r.to);
  });

  const byId = new Map(entities.map(e => [e.id, e]));
  const connected = entities.filter(e => touched.has(e.id));
  const isolated = entities.filter(e => !touched.has(e.id));

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

/** Points d'ancrage d'une arête : bord droit → bord gauche quand la cible est à droite, symétrique sinon. */
export function edgeAnchors(
  from: { x: number; y: number; height: number },
  to: { x: number; y: number; height: number },
): { x1: number; y1: number; x2: number; y2: number } {
  const fromCenterY = from.y + from.height / 2;
  const toCenterY = to.y + to.height / 2;
  if (to.x >= from.x) {
    return { x1: from.x + NODE_W, y1: fromCenterY, x2: to.x, y2: toCenterY };
  }
  return { x1: from.x, y1: fromCenterY, x2: to.x + NODE_W, y2: toCenterY };
}

// ── Habillage ─────────────────────────────────────────────────────────────────

/** Trait par confiance : plein quand la clé concorde, tirets sur le nom seul, pointillés sur le signal faible. */
export const CONFIDENCE_STYLE: Record<RelationConfidence, { dash: string | undefined; color: string; label: string }> = {
  HIGH:   { dash: undefined, color: '#7ee2a8', label: 'key columns agree' },
  MEDIUM: { dash: '6 4',     color: '#f5c264', label: 'name match only' },
  LOW:    { dash: '2 4',     color: '#79839a', label: 'shared key column' },
};

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
