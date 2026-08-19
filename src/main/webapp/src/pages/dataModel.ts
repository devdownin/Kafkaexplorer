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

/**
 * Applique le plafond serveur à une sélection déjà résolue — celle que porte une URL partagée,
 * jamais des motifs à étendre. `addTopicEntries` fait ce travail pour la saisie manuelle
 * (nom, liste, motif) ; ce chemin-là est différent, une URL colle directement une liste de noms
 * de topics, mais avait échappé au plafond entièrement : `topicsFromQuery` ne borne rien, donc
 * une URL portant plus de 30 topics (composée avant que le plafond ne baisse, ou collée à la
 * main) faisait afficher « Topics (32/30) » sans le dire, envoyait les 32 au serveur, et
 * bloquait ensuite toute nouvelle case à cocher tant que l'opérateur n'en décochait pas
 * manuellement. Ce qui dépasse est **rendu**, comme partout ailleurs sur cette page — jamais
 * tronqué en silence.
 */
export function capTopics(topics: string[]): { kept: string[]; overflow: string[] } {
  return { kept: topics.slice(0, MAX_TOPICS), overflow: topics.slice(MAX_TOPICS) };
}

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
  /**
   * Réserve verticale au-dessus du graphe, distincte de `padding`. Le bandeau de couverture et
   * d'avertissements se dessine par-dessus le SVG sans réduire sa taille : sans cette réserve,
   * un graphe contraint par sa hauteur — un hub très référencé, par exemple, une forme tout à
   * fait ordinaire — se centre à quelques pixels du haut du viewport, pile sous le bandeau, et
   * un avertissement (qui peut tenir sur deux lignes) rend cette situation courante plutôt
   * qu'exceptionnelle. Par défaut égal à `padding`, ce qui reproduit exactement l'ancien centrage
   * symétrique — l'appelant qui ne connaît pas la hauteur du bandeau n'a rien à changer.
   */
  topPadding = padding,
): { x: number; y: number; scale: number } {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxY - bounds.minY);
  const availableHeight = viewportHeight - topPadding - padding;
  const scale = Math.max(0.1, Math.min(
    1,
    (viewportWidth - 2 * padding) / width,
    availableHeight / height,
  ));
  return {
    scale,
    x: (viewportWidth - width * scale) / 2 - bounds.minX * scale,
    y: topPadding + (availableHeight - height * scale) / 2 - bounds.minY * scale,
  };
}

/**
 * Centre le viewport sur une entité précise, sans passer par un cadrage de tout le graphe :
 * un modèle de trente tables se parcourt mal à la souris, et retrouver une entité par son nom
 * est la question qu'on se pose devant lui. L'échelle courante est conservée si elle est déjà
 * assez proche pour lire la table (`minScale`) ; sinon elle est relevée jusque-là — un modèle
 * vu d'assez loin pour tenir en entier ne laisse pas lire un nom de colonne.
 */
export function centerOnEntity(
  entity: DataModelEntity,
  pos: { x: number; y: number },
  viewportWidth: number,
  viewportHeight: number,
  currentScale: number,
  minScale = 0.8,
): { x: number; y: number; scale: number } {
  const scale = Math.min(1, Math.max(currentScale, minScale));
  const cx = pos.x + NODE_W / 2;
  const cy = pos.y + entityHeight(entity) / 2;
  return {
    scale,
    x: viewportWidth / 2 - cx * scale,
    y: viewportHeight / 2 - cy * scale,
  };
}

// ── Filtre de confiance ───────────────────────────────────────────────────────

/**
 * Les relations à *dessiner*, parmi celles que le serveur a déduites.
 *
 * Un modèle riche en concordances de noms seules (`MEDIUM`) ou en simples colonnes de clé
 * partagées (`LOW`) noie les arêtes `HIGH` — celles où la colonne référente et la clé de la
 * cible concordent — sous des traits que rien n'étaye vraiment. Le filtre est donc une commande
 * de *lecture* : il ne change ni ce que le serveur a répondu, ni ce que l'inspecteur montre.
 *
 * Ce qu'il ne touche pas, délibérément : la disposition et la répartition connectées/isolées
 * restent calculées sur **toutes** les relations. Basculer un grade doit masquer des traits, pas
 * réarranger le diagramme sous les yeux de l'opérateur — et surtout, « No deduced relation (N) »
 * doit continuer de vouloir dire *aucune*, jamais « aucune parmi celles que vous regardez ».
 */
export function filterRelations(
  relations: DataModelRelation[],
  enabled: ReadonlySet<RelationConfidence>,
): DataModelRelation[] {
  return relations.filter(relation => enabled.has(relation.confidence));
}

/**
 * Ce que le filtre retire, en toutes lettres. `null` quand il ne retire rien : une mention
 * permanente disant « 0 masquée » apprendrait à ne plus lire la ligne.
 */
export function describeRelationFilter(total: number, shown: number): string | null {
  const hidden = total - shown;
  if (hidden <= 0) return null;
  return `${hidden} of ${total} relations hidden by the confidence filter`;
}

// ── Colonnes qui ressemblent à une clé sans porter de relation ─────────────────

/**
 * Miroir de `DataModelService.idBaseOf` — le mot de base d'un champ qui se lit comme un
 * identifiant. `order_id` → `order` ; `id` seul → `""` (un identifiant de l'entité elle-même) ;
 * `amount` → `null`. Seul le dernier segment du chemin compte, donc `customer.id` se lit comme
 * un identifiant appartenant à `customer`.
 *
 * C'est un miroir, avec la même discipline que `components/processmining/schemaMapping.ts` :
 * il n'est **jamais plus strict que le serveur**. Ce qu'il produit ne décide de rien — il ne
 * sert qu'à signaler une colonne à l'écran — et dans le doute, ne rien signaler vaut mieux que
 * d'accuser une colonne ordinaire d'être une clé cassée.
 */
const SNAKE_ID = /^(?:(.*?)[_-])?(?:id|uuid|key|ref|reference|code)$/i;
const CAMEL_ID = /^(.*?)(?:Id|Uuid|Key|Ref|Reference|Code)$/;

export function idBaseOf(fieldPath: string): string | null {
  const leaf = fieldPath.slice(fieldPath.lastIndexOf('.') + 1);
  const match = SNAKE_ID.exec(leaf) ?? CAMEL_ID.exec(leaf);
  if (!match) return null;
  const base = match[1];
  if (base === undefined || base === '') return '';
  // Convention FK `<entité><suffixe>` : dans un composé camelCase, le mot de l'entité est celui
  // qui précède le suffixe. Découpe sur une frontière minuscule→majuscule seulement, sinon un
  // nom tout en capitales serait éclaté lettre par lettre.
  const humps = base.split(/(?<=\p{Lower})(?=\p{Upper})/u);
  return humps[humps.length - 1].toLowerCase();
}

/** Miroir de `DataModelService.topicTokens`. */
function topicTokens(topic: string): string[] {
  return topic.toLowerCase().split(/[._-]+/).filter(s => s !== '' && !/^\d+$/.test(s));
}

/** Miroir de `DataModelService.tokenMatches` — pluriels anglais seulement, comme le serveur. */
function tokenMatches(token: string, base: string): boolean {
  if (token === base) return true;
  return token === `${base}s` || token === `${base}es`
    || (token.endsWith('ies') && base.endsWith('y')
        && token.slice(0, base.length - 1) === base.slice(0, base.length - 1));
}

export interface OrphanKey {
  column: string;
  /** Le mot d'entité que le nom désigne — `customer_id` → `customer`. */
  base: string;
  /** Une autre entité du modèle porte-t-elle ce nom ? C'est la moitié vérifiable de l'explication. */
  namedEntityInModel: boolean;
}

/**
 * Les colonnes qui **se lisent comme une clé étrangère** et dont pourtant aucune relation n'est
 * sortie. Sans ça elles sont indiscernables d'une colonne ordinaire, et un diagramme qui paraît
 * incomplet ne dit pas pourquoi il l'est.
 *
 * Un `id` nu est exclu : sa base est vide, donc il identifie *cette* entité et ne désigne
 * personne d'autre. La clé primaire l'est aussi — elle ne référence rien par construction.
 *
 * Rien n'est deviné sur la cause. La seule moitié vérifiable côté navigateur est de savoir si
 * une entité du modèle porte ce nom : sinon le topic visé n'est simplement pas dans la
 * sélection, et c'est actionnable. Quand il y est, on dit que la déduction n'a pas conclu — pas
 * pourquoi.
 */
export function orphanKeyColumns(
  entity: DataModelEntity,
  entities: DataModelEntity[],
): OrphanKey[] {
  const orphans: OrphanKey[] = [];
  for (const column of entity.columns) {
    if (column.primaryKey || column.references !== null) continue;
    const base = idBaseOf(column.name);
    if (base === null || base === '') continue;
    orphans.push({
      column: column.name,
      base,
      namedEntityInModel: entities.some(other => other.id !== entity.id
        && topicTokens(other.topic).some(token => tokenMatches(token, base))),
    });
  }
  return orphans;
}

/** La phrase qui accompagne une colonne orpheline dans l'inspecteur. */
export function describeOrphanKey(orphan: OrphanKey): string {
  return orphan.namedEntityInModel
    ? `Reads as a key naming '${orphan.base}', but the deduction drew no relation from it.`
    : `Reads as a key naming '${orphan.base}' — no selected topic is named after it.`;
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

// ── SQL depuis une relation ───────────────────────────────────────────────────

/** Colonnes projetées par côté : au-delà, la requête cesse d'être un point de départ lisible. */
const JOIN_MAX_COLUMNS = 5;

export interface JoinSql {
  sql: string;
  /** Ce que la requête suppose et que l'opérateur doit vérifier avant de l'exécuter. */
  caveats: string[];
}

/**
 * La requête de jointure qu'une relation déduite décrit.
 *
 * Une relation `HIGH` **est** un prédicat de jointure — `payments.order_id = orders.order_id` —
 * et c'est le seul endroit de l'application où ce prédicat est déjà connu. Le diagramme cesse
 * donc d'être une image qu'on regarde pour devenir le point de départ d'une requête, via le
 * `?sql=` que l'éditeur accepte déjà.
 *
 * Ce qu'elle ne fait pas, et pourquoi :
 *
 *  * **pas de `SELECT *`** — les deux tables partagent le nom de la colonne de jointure, donc
 *    l'étoile produit deux colonnes homonymes ; les colonnes sont nommées et qualifiées ;
 *  * **les chemins imbriqués sont écartés de la projection**, avec une réserve qui le dit : un
 *    `customer.address.city` derrière un alias de table n'a pas la même signification en Flink
 *    SQL qu'à l'écran, et une requête qui échoue à la première exécution est pire qu'une
 *    requête plus courte ;
 *  * **la jointure est régulière, donc non bornée**, ce que la requête énonce en commentaire :
 *    Flink garde les deux côtés en état, ce qui est sans conséquence sur le jeu de démonstration
 *    et ne l'est pas sur un vrai topic.
 *
 * `null` quand aucune colonne cible ne peut être résolue — une relation `MEDIUM` sur une entité
 * sans clé détectée ne décrit aucun prédicat, et en inventer un serait précisément la
 * supposition présentée comme un fait que ce diagramme évite partout ailleurs.
 */
export function buildJoinSql(
  relation: DataModelRelation,
  entities: DataModelEntity[],
): JoinSql | null {
  const from = entities.find(e => e.id === relation.from);
  const to = entities.find(e => e.id === relation.to);
  if (!from || !to) return null;

  const caveats: string[] = [];

  // La colonne que la relation nomme ; à défaut, la clé détectée de la cible — signalée comme
  // supposée, jamais posée en silence.
  let targetColumn = relation.toColumn;
  if (targetColumn === null) {
    if (to.primaryKey === null) return null;
    targetColumn = to.primaryKey;
    caveats.push(`The relation names no column on ${to.id}; the join uses its detected key `
      + `'${targetColumn}'.`);
  }

  const [fromAlias, toAlias] = joinAliases(from.topic, to.topic);
  const projected = (entity: DataModelEntity, alias: string, joinColumn: string) => {
    const flat = entity.columns.filter(c => !c.name.includes('.'));
    if (flat.length < entity.columns.length) {
      caveats.push(`${entity.columns.length - flat.length} nested field(s) of ${entity.id} are `
        + 'left out of the projection — a nested path behind a table alias does not mean the '
        + 'same thing in Flink SQL as it does on the diagram.');
    }
    const ordered = [
      ...flat.filter(c => c.name === joinColumn || c.primaryKey),
      ...flat.filter(c => c.name !== joinColumn && !c.primaryKey),
    ];
    return ordered.slice(0, JOIN_MAX_COLUMNS).map(c => `    ${alias}.${c.name}`);
  };

  const columns = [
    ...projected(from, fromAlias, relation.fromColumn),
    ...projected(to, toAlias, targetColumn),
  ];

  if (relation.fromColumn.includes('.') || targetColumn.includes('.')) {
    caveats.push('The join column is a nested path — check how it resolves before running.');
  }

  const sql = [
    `-- ${from.id} → ${to.id}, from a deduced relation (${relation.confidence.toLowerCase()} confidence).`,
    `-- ${relation.reason}`,
    ...caveats.map(c => `-- ${c}`),
    '-- Regular join: Flink keeps both sides in state, which is fine here and is not on a large topic.',
    'SELECT',
    columns.join(',\n'),
    `FROM ${from.id} AS ${fromAlias}`,
    `JOIN ${to.id} AS ${toAlias}`,
    `  ON ${fromAlias}.${relation.fromColumn} = ${toAlias}.${targetColumn}`,
    'LIMIT 50',
  ].join('\n');

  return { sql, caveats };
}

/**
 * Deux alias courts, distincts et **valides** pour une paire de topics.
 *
 * Premier choix : le segment distinctif que `topicDomains` isole déjà en retirant les segments de
 * tête partagés — `demo.payments.authorized` et `demo.orders.1.received` donnent `payments` et
 * `orders`, qui est ce qui se lit le mieux dans un `ON`.
 *
 * Mais deux topics *frères* (`demo.orders.1.received`, `demo.orders.2.validated`) partagent aussi
 * `orders`, donc ce que la règle isole est ce qui les sépare vraiment : `1` et `2`. Un alias qui
 * commence par un chiffre n'est pas un identifiant SQL, et la requête proposée échouerait au
 * parseur — le test qui pose ce cas a trouvé exactement ça. Le repli est alors le dernier segment
 * non numérique (`received`, `validated`), qui est de toute façon ce qui distingue ces deux-là ;
 * en dernier ressort seulement, un suffixe.
 */
export function joinAliases(fromTopic: string, toTopic: string): [string, string] {
  const domains = topicDomains([fromTopic, toTopic]);
  const domain = (topic: string) => sqlAlias(domains.get(topic) ?? '');
  const tail = (topic: string) => sqlAlias(
    topic.split(/[._-]+/).filter(s => s && !/^\d+$/.test(s)).pop() ?? '');

  for (const pick of [domain, tail]) {
    const first = pick(fromTopic);
    const second = pick(toTopic);
    if (first && second && first !== second) return [first, second];
  }
  const base = tail(fromTopic) || domain(fromTopic) || 'a';
  return [`${base}_1`, `${base}_2`];
}

/** Un identifiant SQL sans guillemets, ou la chaîne vide s'il n'en reste rien d'exploitable. */
function sqlAlias(value: string): string {
  const cleaned = value.replace(/[^A-Za-z0-9_]/g, '_');
  // Un alias ne peut pas commencer par un chiffre : sans ça la requête meurt au parseur.
  return /^[A-Za-z_]/.test(cleaned) ? cleaned : '';
}

// ── Export ────────────────────────────────────────────────────────────────────

const EXPORT_PAD = 32;
const EXPORT_TITLE_H = 24;
const EXPORT_LINE_H = 15;
const EXPORT_LEGEND_ROW_H = 16;
const EXPORT_BG = '#0d1015';

export interface ExportLegendEntry {
  color: string;
  dash?: string;
  label: string;
}

export interface ExportCaption {
  title: string;
  /** Ce que le modèle couvre — la même phrase que la ligne de couverture à l'écran. */
  coverage: string;
  /** Ce que le dessin ne montre pas : entités non dessinées, avertissements du serveur. */
  notes: string[];
}

/**
 * Le modèle en `erDiagram` Mermaid — la forme texte du même diagramme.
 *
 * Elle existe pour ce que les deux exports binaires ne savent pas faire : **se relire et se
 * différencier**. Un PNG collé dans une revue ne dit pas ce qui a changé depuis la version
 * précédente ; ces quelques lignes, si. Mermaid est déjà une dépendance du dépôt (Process
 * Mining), donc GitHub, la plupart des wikis et l'application elle-même savent la rendre.
 *
 * Deux contraintes de la syntaxe, et une décision :
 *
 *  * les identifiants Mermaid n'acceptent ni point ni tiret — les noms d'entités sont déjà les
 *    noms de tables Flink, donc sains, mais les noms de colonnes viennent des charges utiles et
 *    peuvent porter un chemin imbriqué : ils sont assainis, et le nom d'origine reste en
 *    commentaire de colonne, jamais perdu ;
 *  * la cardinalité est `}o--||` (plusieurs-vers-un facultatif), la seule que les déductions
 *    justifient : rien ici n'établit qu'une référence est obligatoire.
 *
 * La décision : **la confiance voyage dans le libellé de la relation**, pas dans un commentaire.
 * Mermaid n'a pas de style de trait par arête, donc un `erDiagram` qui tairait le grade
 * présenterait une supposition et une concordance de clés exactement de la même façon — ce que
 * tout le reste de cette page existe pour éviter.
 */
export function toMermaidEr(
  response: DataModelResponse,
  caption: ExportCaption,
): string {
  const lines: string[] = [];
  lines.push(`%% ${caption.title}`);
  lines.push(`%% ${caption.coverage}`);
  caption.notes.forEach(note => lines.push(`%% ${note}`));
  lines.push('erDiagram');

  const drawn = new Set(response.entities.map(e => e.id));
  for (const relation of response.relations) {
    if (!drawn.has(relation.from) || !drawn.has(relation.to)) continue;
    const label = `${relation.fromColumn} (${relation.confidence.toLowerCase()})`;
    lines.push(`    ${mermaidId(relation.to)} ||--o{ ${mermaidId(relation.from)} : "${mermaidLabel(label)}"`);
  }

  for (const entity of response.entities) {
    lines.push(`    ${mermaidId(entity.id)} {`);
    for (const column of entity.columns) {
      const key = column.primaryKey ? ' PK' : column.references ? ' FK' : '';
      // Le nom d'origine en commentaire : l'assainissement ne doit rien coûter en information.
      const comment = mermaidId(column.name) !== column.name ? ` "${mermaidLabel(column.name)}"` : '';
      lines.push(`        ${mermaidId(column.type)} ${mermaidId(column.name)}${key}${comment}`);
    }
    lines.push('    }');
  }

  return lines.join('\n') + '\n';
}

/** Un identifiant Mermaid : lettres, chiffres et tirets bas. Vide → `_`, jamais rien. */
function mermaidId(value: string): string {
  const cleaned = value.replace(/[^A-Za-z0-9_]/g, '_');
  return cleaned === '' ? '_' : cleaned;
}

/** Un libellé entre guillemets : les guillemets internes casseraient la ligne. */
function mermaidLabel(value: string): string {
  return value.replace(/"/g, "'");
}

/** Au-delà, les avertissements sont comptés — un bandeau n'est pas un journal. */
const EXPORT_MAX_NOTES = 4;

/**
 * Ce que le dessin ne montre pas, en toutes lettres sous le titre : les entités laissées de
 * côté et les réserves du serveur. Un diagramme détaché de l'application ne peut plus être
 * interrogé — s'il ne porte pas ses limites, il se lit comme un modèle complet.
 */
export function exportNotes(
  response: DataModelResponse,
  hiddenEntities: number,
  /**
   * Arêtes retirées par le filtre de confiance. Le SVG sérialise le DOM, donc un export pris
   * sous filtre est déjà filtré : sans cette mention il se lirait comme le modèle entier, ce que
   * cette fonction existe précisément pour empêcher.
   */
  hiddenRelations = 0,
): string[] {
  const notes: string[] = [];
  if (hiddenEntities > 0) {
    notes.push(`${hiddenEntities} ${hiddenEntities === 1 ? 'entity has' : 'entities have'}`
      + ' no deduced relation and are not drawn.');
  }
  if (hiddenRelations > 0) {
    notes.push(`${hiddenRelations} ${hiddenRelations === 1 ? 'relation is' : 'relations are'}`
      + ' hidden by the confidence filter and not drawn.');
  }
  const warnings = response.warnings ?? [];
  notes.push(...warnings.slice(0, EXPORT_MAX_NOTES));
  if (warnings.length > EXPORT_MAX_NOTES) {
    notes.push(`… and ${warnings.length - EXPORT_MAX_NOTES} more warnings.`);
  }
  return notes;
}

/** Échappe le texte destiné à un nœud SVG. Les noms de topics et de colonnes passent par là. */
export function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * Un SVG autonome à partir du diagramme réellement rendu.
 *
 * Le balisage interne vient du DOM vivant (sérialisé par l'appelant) plutôt que d'être
 * réécrit ici : deux moteurs de rendu pour une même image finiraient par diverger, et
 * l'export montrerait autre chose que l'écran. Ce qui s'ajoute autour est ce qu'un visuel
 * détaché doit porter — **la couverture et les légendes** : collé dans un ticket, un
 * diagramme qui ne dit pas sur combien de topics il porte ni ce qu'un trait pointillé
 * signifie est une image qu'on ne peut pas interpréter. Même règle que les exports JSON de
 * Stream Flow.
 */
export function buildExportSvg(
  innerMarkup: string,
  bounds: Bounds,
  caption: ExportCaption,
  confidenceLegend: ExportLegendEntry[],
  domainLegend: ExportLegendEntry[] = [],
): string {
  const graphW = Math.max(1, bounds.maxX - bounds.minX);
  const graphH = Math.max(1, bounds.maxY - bounds.minY);

  const headerH = EXPORT_TITLE_H + EXPORT_LINE_H * (1 + caption.notes.length) + 12;
  const legendRows = Math.max(confidenceLegend.length, domainLegend.length);
  const footerH = legendRows > 0 ? 18 + EXPORT_LEGEND_ROW_H * legendRows : 0;

  // Le bandeau peut être plus large que le graphe sur un modèle d'une seule table.
  const captionW = 8 * Math.max(
    caption.title.length, caption.coverage.length, ...caption.notes.map(n => n.length));
  const width = Math.round(Math.max(graphW, captionW, 420) + EXPORT_PAD * 2);
  const height = Math.round(graphH + headerH + footerH + EXPORT_PAD * 2);

  const dx = EXPORT_PAD - bounds.minX;
  const dy = EXPORT_PAD + headerH - bounds.minY;

  const parts: string[] = [];
  parts.push(`<rect width="${width}" height="${height}" fill="${EXPORT_BG}"/>`);
  parts.push(
    `<text x="${EXPORT_PAD}" y="${EXPORT_PAD + 4}" fill="#ffffff" font-size="16"`
    + ` font-family="Inter, sans-serif" font-weight="bold">${escapeXml(caption.title)}</text>`);
  parts.push(
    `<text x="${EXPORT_PAD}" y="${EXPORT_PAD + EXPORT_TITLE_H}" fill="#a3adff" font-size="11"`
    + ` font-family="Inter, sans-serif">${escapeXml(caption.coverage)}</text>`);
  caption.notes.forEach((note, i) => {
    parts.push(
      `<text x="${EXPORT_PAD}" y="${EXPORT_PAD + EXPORT_TITLE_H + EXPORT_LINE_H * (i + 1)}"`
      + ` fill="#79839a" font-size="10" font-family="Inter, sans-serif">${escapeXml(note)}</text>`);
  });

  parts.push(`<g transform="translate(${dx}, ${dy})">${innerMarkup}</g>`);

  if (legendRows > 0) {
    const top = EXPORT_PAD + headerH + graphH + 18;
    const column = (entries: ExportLegendEntry[], x: number) => entries.forEach((entry, i) => {
      const y = top + EXPORT_LEGEND_ROW_H * i;
      parts.push(
        `<line x1="${x}" y1="${y - 3}" x2="${x + 22}" y2="${y - 3}" stroke="${entry.color}"`
        + ` stroke-width="2"${entry.dash ? ` stroke-dasharray="${entry.dash}"` : ''}/>`);
      parts.push(
        `<text x="${x + 30}" y="${y}" fill="#c5cad6" font-size="10"`
        + ` font-family="Inter, sans-serif">${escapeXml(entry.label)}</text>`);
    });
    column(confidenceLegend, EXPORT_PAD);
    if (domainLegend.length > 0) column(domainLegend, EXPORT_PAD + Math.round(width / 2));
  }

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"`
    + ` viewBox="0 0 ${width} ${height}">${parts.join('')}</svg>`;
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

// ── Comparaison de deux sélections ─────────────────────────────────────────────

/**
 * Un modèle n'a pas d'historique côté serveur — contrairement à l'audit, rien ne le persiste —
 * donc « comparer » ici veut dire : deux sélections de topics, deux appels indépendants à
 * `POST /api/data-model`, et un diff calculé côté client sur les deux réponses. C'est le même
 * choix que la page Compare pour Stream Flow : rejouer en direct plutôt que lire un historique
 * qui n'existe pas.
 *
 * Les relations n'ont pas d'id stable côté serveur — elles sont recalculées à chaque appel — donc
 * l'appariement se fait sur ce qui les décrit : la paire de colonnes qu'elles relient. Deux
 * relations identiques sur ce plan mais de confiance différente sont un changement, pas une
 * paire ajout+suppression : la même affirmation, mieux ou moins bien étayée d'un run à l'autre.
 */
export function relationKey(relation: DataModelRelation): string {
  return `${relation.from} ${relation.fromColumn} ${relation.to} ${relation.toColumn ?? ''}`;
}

export interface RelationConfidenceChange {
  relation: DataModelRelation;
  from: RelationConfidence;
  to: RelationConfidence;
}

export interface ModelDiff {
  addedEntities: DataModelEntity[];
  removedEntities: DataModelEntity[];
  addedRelations: DataModelRelation[];
  removedRelations: DataModelRelation[];
  changedRelations: RelationConfidenceChange[];
  unchangedEntityCount: number;
  unchangedRelationCount: number;
}

export function diffModels(before: DataModelResponse, after: DataModelResponse): ModelDiff {
  const beforeIds = new Set(before.entities.map(e => e.id));
  const afterIds = new Set(after.entities.map(e => e.id));
  const addedEntities = after.entities.filter(e => !beforeIds.has(e.id));
  const removedEntities = before.entities.filter(e => !afterIds.has(e.id));

  const beforeByKey = new Map(before.relations.map(r => [relationKey(r), r]));
  const afterByKey = new Map(after.relations.map(r => [relationKey(r), r]));

  const addedRelations: DataModelRelation[] = [];
  const changedRelations: RelationConfidenceChange[] = [];
  let unchangedRelationCount = 0;
  afterByKey.forEach((relation, key) => {
    const prior = beforeByKey.get(key);
    if (!prior) { addedRelations.push(relation); return; }
    if (prior.confidence !== relation.confidence) {
      changedRelations.push({ relation, from: prior.confidence, to: relation.confidence });
    } else {
      unchangedRelationCount += 1;
    }
  });
  const removedRelations: DataModelRelation[] = [];
  beforeByKey.forEach((relation, key) => {
    if (!afterByKey.has(key)) removedRelations.push(relation);
  });

  return {
    addedEntities,
    removedEntities,
    addedRelations,
    removedRelations,
    changedRelations,
    unchangedEntityCount: after.entities.length - addedEntities.length,
    unchangedRelationCount,
  };
}

/** Vrai quand les deux sélections produisent exactement le même modèle. */
export function diffIsEmpty(diff: ModelDiff): boolean {
  return diff.addedEntities.length === 0 && diff.removedEntities.length === 0
    && diff.addedRelations.length === 0 && diff.removedRelations.length === 0
    && diff.changedRelations.length === 0;
}

/** Une phrase, comme `describeModel` — pour la ligne de résumé au-dessus du détail. */
export function describeDiff(diff: ModelDiff): string {
  if (diffIsEmpty(diff)) return 'No difference — same entities and relations.';
  const parts: string[] = [];
  if (diff.addedEntities.length > 0) parts.push(`+${diff.addedEntities.length} ${diff.addedEntities.length === 1 ? 'entity' : 'entities'}`);
  if (diff.removedEntities.length > 0) parts.push(`-${diff.removedEntities.length} ${diff.removedEntities.length === 1 ? 'entity' : 'entities'}`);
  if (diff.addedRelations.length > 0) parts.push(`+${diff.addedRelations.length} relation${diff.addedRelations.length === 1 ? '' : 's'}`);
  if (diff.removedRelations.length > 0) parts.push(`-${diff.removedRelations.length} relation${diff.removedRelations.length === 1 ? '' : 's'}`);
  if (diff.changedRelations.length > 0) parts.push(`${diff.changedRelations.length} confidence changed`);
  return parts.join(', ');
}
