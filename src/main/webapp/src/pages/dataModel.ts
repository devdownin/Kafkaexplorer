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
  DataModelLimits,
  DataModelRelation,
  DataModelResponse,
  RelationConfidence,
} from '../api/types';
import { clearDraft, readDraft, writeDraft } from '../draftStore';
import { isTopicPattern, topicPatternMatcher } from './streamFlow';
import { isInternalTopic } from '../internalTopics';

/**
 * Ce qu'une génération analyse par défaut — le même nombre que `DataModelService`.
 *
 * Ce n'est plus un plafond, et ce n'est plus un miroir : le plafond est configurable côté serveur
 * (`explorer.data-model-max-topics`) et la page le lit par `GET /api/data-model/limits`, au lieu
 * d'en garder une copie qui divergeait le jour où le serveur changeait. Ceci n'est que la valeur
 * de départ du champ, quand la page n'a pas encore obtenu les bornes.
 */
export const DEFAULT_MAX_TOPICS = 30;

/**
 * Ramène une valeur saisie dans les bornes que le serveur annonce. Tant qu'elles ne sont pas
 * connues, le plafond est celui du défaut : on ne suppose pas un serveur plus permissif qu'il
 * ne l'est — une demande refusée coûte une génération entière.
 */
export function clampMaxTopics(value: number, limits: DataModelLimits | null): number {
  const ceiling = Math.max(1, limits?.maxTopics ?? DEFAULT_MAX_TOPICS);
  if (!Number.isFinite(value)) return Math.min(DEFAULT_MAX_TOPICS, ceiling);
  return Math.min(Math.max(1, Math.floor(value)), ceiling);
}

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
export function capTopics(topics: string[], cap: number): { kept: string[]; overflow: string[] } {
  const limit = Math.max(1, cap);
  return { kept: topics.slice(0, limit), overflow: topics.slice(limit) };
}

// ── Sélection des topics ──────────────────────────────────────────────────────

/**
 * Filtre la liste à cocher. Insensible à la casse, et **il comprend les motifs**.
 *
 * Il ne les comprenait pas, et c'était un piège : le champ juste au-dessus annonce
 * `demo.orders.*` dans son placeholder, alors taper la même chose ici ne rendait rien du tout.
 * Deux champs voisins sur un même panneau, dont l'un enseigne une syntaxe, ne peuvent pas
 * répondre différemment au même texte — et un filtre qui rend une liste vide se lit comme
 * « aucun topic », pas comme « cette syntaxe n'est pas la mienne ».
 *
 * Un texte sans `*` reste une sous-chaîne, ce qu'on attend d'un filtre. Dès qu'il y a un `*`,
 * c'est `topicPatternMatcher` qui décide — la règle exacte de la sélection, donc ancrée : ce que
 * le filtre montre est ce que la saisie ajouterait.
 */
export function filterTopics(topics: string[], filter: string): string[] {
  const needle = filter.trim();
  if (!needle) return topics;
  if (isTopicPattern(needle)) {
    return topics.filter(topicPatternMatcher(needle));
  }
  const lower = needle.toLowerCase();
  return topics.filter(t => t.toLowerCase().includes(lower));
}

export function toggleTopic(selection: string[], topic: string): string[] {
  return selection.includes(topic)
    ? selection.filter(t => t !== topic)
    : [...selection, topic];
}

/**
 * Coche tout ce que le filtre montre, sans dépasser le plafond de la génération : envoyer 200 topics
 * pour en voir 30 analysés serait exactement la troncature silencieuse que le serveur nomme.
 * Les topics internes — ceux que l'application s'écrit à elle-même — sont ignorés. Le préfixe vient
 * du serveur (`explorer.internal-topic-prefix`) et vaut `''` sur un déploiement qui n'en pose pas,
 * ce qui rend le prédicat identique au littéral qu'il remplace.
 */
export function selectAll(
  selection: string[], visible: string[], cap: number, internalPrefix = '',
): string[] {
  const next = [...selection];
  for (const topic of visible) {
    if (isInternalTopic(topic, internalPrefix)) continue;
    if (next.length >= cap) break;
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

/**
 * Le budget que porte l'URL, `null` quand elle n'en porte pas.
 *
 * L'URL ne portait que la liste de topics, ce qui suffisait tant que le plafond valait 30 des
 * deux côtés. Depuis qu'il se règle, un lien décrivant une génération de cinquante topics
 * s'ouvrait chez son destinataire avec un champ à 30, `capTopics` en retirait vingt et un toast
 * annonçait qu'ils étaient « laissés de côté » — d'un modèle qui, lui, avait bien été construit
 * sur les cinquante. Un lien doit rejouer la génération qu'il décrit, budget compris.
 *
 * Une valeur illisible vaut « pas de budget » plutôt qu'une valeur inventée : le champ garde
 * alors son défaut, ce qui est la même réponse que pour une URL qui n'en porte pas.
 */
export function maxTopicsFromQuery(search: string): number | null {
  const raw = new URLSearchParams(search).get('max');
  if (!raw) return null;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed < 1) return null;
  return Math.floor(parsed);
}

/**
 * L'URL d'une génération. `maxTopics` n'y figure que s'il diffère du défaut — un lien n'a pas à
 * porter un paramètre qui ne dit rien, et c'est ce qui garde inchangées les URLs déjà partagées.
 */
export function buildQuery(topics: string[], maxTopics: number = DEFAULT_MAX_TOPICS): string {
  if (topics.length === 0) return '';
  const params = new URLSearchParams();
  params.set('topics', topics.join(','));
  if (maxTopics !== DEFAULT_MAX_TOPICS) params.set('max', String(maxTopics));
  return `?${params.toString()}`;
}

/**
 * La sélection non lancée, gardée d'une visite à l'autre.
 *
 * L'URL ne porte qu'une sélection **déjà exécutée** — elle est réécrite après chaque génération.
 * Choisir quinze topics, aller vérifier un nom de champ dans le Topic Explorer et revenir
 * rendait donc un formulaire vide, alors que Stream Flow a exactement le même geste et a été
 * corrigé pour ça. Même module, même règle : une URL qui décrit une sélection l'emporte
 * toujours, sinon un lien partagé écraserait le formulaire de son destinataire.
 */
const SELECTION_DRAFT = 'data-model';

/** Une sélection non lancée : les topics **et** le budget sous lequel ils devaient être lus. */
export interface SelectionDraft {
  topics: string[];
  /** `null` quand le brouillon n'en porte pas — écrit par une version antérieure. */
  maxTopics: number | null;
}

/**
 * Le brouillon est lu **avant** que le plafond serveur ne soit connu, donc il ne peut pas être
 * borné ici par le vrai plafond. Il l'est par `cap`, ce que l'appelant sait de mieux à cet
 * instant ; le budget relu est rendu tel quel et c'est `clampMaxTopics` qui le ramènera dans
 * les bornes dès que `GET /api/data-model/limits` aura répondu.
 */
export function readSelectionDraft(cap: number): SelectionDraft | null {
  const draft = readDraft<unknown>(SELECTION_DRAFT, null);
  // Une version antérieure écrivait le tableau nu. Il se relit, plutôt que d'être jeté : le
  // brouillon est précisément ce qu'on ne veut pas perdre en changeant de version.
  const raw = Array.isArray(draft)
    ? { topics: draft as unknown[], maxTopics: null }
    : draft as { topics?: unknown; maxTopics?: unknown } | null;
  if (!raw || !Array.isArray(raw.topics)) return null;

  const topics = raw.topics.filter((t): t is string => typeof t === 'string' && t !== '');
  if (topics.length === 0) return null;
  const budget = typeof raw.maxTopics === 'number' && Number.isFinite(raw.maxTopics)
    && raw.maxTopics >= 1
    ? Math.floor(raw.maxTopics)
    : null;
  return { topics: capTopics(topics, budget ?? cap).kept, maxTopics: budget };
}

export function saveSelectionDraft(topics: string[], maxTopics: number): void {
  if (topics.length === 0) clearDraft(SELECTION_DRAFT);
  else writeDraft(SELECTION_DRAFT, { topics, maxTopics } satisfies SelectionDraft);
}

// ── Dimensionnement des nœuds ─────────────────────────────────────────────────

/**
 * La taille d'une boîte-table, et tout ce qui en dépend.
 *
 * C'était six constantes de module, donc une seule taille pour un modèle de deux entités comme
 * pour un de cent — et c'est cette constante qui décidait, sans que rien ne le dise, si le
 * diagramme pouvait tenir en entier à l'écran. Une boîte de 230 px avec douze lignes est
 * confortable à trois tables et se cadre à l'échelle 0,25 à cinquante, où plus aucun nom de
 * colonne ne se lit : le graphe « tient » et n'apprend plus rien.
 *
 * Le calibre est donc **choisi** (`chooseNodeSizing`) entre `MIN_NODE_W` et `MAX_NODE_W` selon la
 * place réellement disponible. Une boîte plus étroite qui liste moins de colonnes réduit l'emprise
 * du graphe, donc relève l'échelle de cadrage — et le texte rendu (corps × échelle) finit *plus
 * gros* qu'avec une grande boîte trop dézoomée. C'est le seul arbitrage qui compte ici : moins de
 * colonnes lisibles vaut mieux que toutes les colonnes illisibles.
 */
export interface NodeSizing {
  /** Largeur de la boîte. Toujours entre `MIN_NODE_W` et `MAX_NODE_W`. */
  width: number;
  headerH: number;
  rowH: number;
  footerH: number;
  /** Au-delà, les colonnes restantes sont comptées plutôt que listées — un nœud n'est pas une page. */
  maxColumns: number;
  /** Écarts de la disposition : entre colonnes, entre nœuds empilés, dans la grille des isolés. */
  colGap: number;
  rowGap: number;
  orphanGap: number;
  /** Corps de chaque texte du nœud, et longueur au-delà de laquelle il est élidé. */
  titleSize: number; titleChars: number;
  subtitleSize: number; subtitleChars: number;
  rowSize: number; rowChars: number;
  typeSize: number;
}

/**
 * Les bornes de la boîte, assumées et nommées.
 *
 * En dessous de `MIN_NODE_W` un nom de table qualifié n'est plus reconnaissable même à l'échelle 1 :
 * rétrécir encore échangerait une illisibilité contre une autre. Au-dessus de `MAX_NODE_W` une
 * table cesse de se lire d'un coup d'œil et le graphe s'étale sans rien gagner.
 */
export const MIN_NODE_W = 160;
export const MAX_NODE_W = 300;

/** Le calibre large : peu d'entités, beaucoup de place — on montre tout ce qu'on peut. */
export const COMFORTABLE_NODE_SIZING: NodeSizing = {
  width: MAX_NODE_W, headerH: 46, rowH: 22, footerH: 10, maxColumns: 20,
  colGap: 195, rowGap: 55, orphanGap: 78,
  titleSize: 13, titleChars: 34,
  subtitleSize: 10, subtitleChars: 42,
  rowSize: 11, rowChars: 30,
  typeSize: 10,
};

/** Le calibre historique, inchangé — c'est lui que toutes les signatures prennent par défaut. */
export const DEFAULT_NODE_SIZING: NodeSizing = {
  width: 230, headerH: 42, rowH: 20, footerH: 8, maxColumns: 12,
  colGap: 150, rowGap: 50, orphanGap: 60,
  titleSize: 12, titleChars: 26,
  subtitleSize: 9, subtitleChars: 32,
  rowSize: 10, rowChars: 22,
  typeSize: 9,
};

/** Le calibre serré : c'est lui qui fait tenir un grand modèle à une échelle encore lisible. */
export const COMPACT_NODE_SIZING: NodeSizing = {
  width: MIN_NODE_W, headerH: 32, rowH: 15, footerH: 6, maxColumns: 6,
  colGap: 104, rowGap: 34, orphanGap: 42,
  titleSize: 10, titleChars: 20,
  subtitleSize: 8, subtitleChars: 24,
  rowSize: 9, rowChars: 15,
  typeSize: 8,
};

/** Du plus large au plus serré : `chooseNodeSizing` prend le premier qui reste lisible. */
export const NODE_SIZINGS: NodeSizing[] = [
  COMFORTABLE_NODE_SIZING, DEFAULT_NODE_SIZING, COMPACT_NODE_SIZING,
];

// Les anciennes constantes, dérivées du calibre par défaut : elles restent la référence de tout
// appelant qui ne choisit pas de calibre, et de ce que les tests de disposition épinglent.
export const NODE_W = DEFAULT_NODE_SIZING.width;
export const HEADER_H = DEFAULT_NODE_SIZING.headerH;
export const ROW_H = DEFAULT_NODE_SIZING.rowH;
export const FOOTER_H = DEFAULT_NODE_SIZING.footerH;
export const MAX_COLUMNS_SHOWN = DEFAULT_NODE_SIZING.maxColumns;

export interface DisplayedColumns {
  columns: DataModelEntity['columns'];
  /** Colonnes non listées. 0 = tout est affiché. */
  hidden: number;
}

/**
 * Les colonnes affichées d'un nœud : clés (primaire puis référentes) d'abord — ce sont elles
 * qui portent les arêtes, les cacher rendrait le graphe illisible — puis l'ordre du schéma.
 */
/**
 * Une colonne porte-t-elle une information de clé ? La clé primaire et une référence résolue,
 * évidemment — mais aussi une colonne qui *se lit* comme une clé étrangère sans avoir produit de
 * relation (`keyBase`), qui est justement celle que le diagramme marque d'un `?`.
 *
 * Elle était absente de ce classement, donc sur une entité à charge utile imbriquée — beaucoup
 * de chemins pointés, vite au-delà du plafond de 12 — le marqueur disparaissait dans le
 * `+N more` exactement quand la carte est chargée, c'est-à-dire quand il sert le plus.
 */
function carriesKeyInfo(column: DataModelEntity['columns'][number]): boolean {
  return column.primaryKey || column.references !== null
    || (column.keyBase !== null && column.keyBase !== '');
}

export function displayedColumns(
  entity: DataModelEntity,
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): DisplayedColumns {
  const keys = entity.columns.filter(carriesKeyInfo);
  const rest = entity.columns.filter(c => !carriesKeyInfo(c));
  const ordered = [...keys, ...rest];
  if (ordered.length <= sizing.maxColumns) return { columns: ordered, hidden: 0 };
  return {
    columns: ordered.slice(0, sizing.maxColumns),
    hidden: ordered.length - sizing.maxColumns,
  };
}

/** Hauteur d'un nœud : en-tête + lignes affichées (+ la ligne « +N more » le cas échéant). */
export function entityHeight(
  entity: DataModelEntity,
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): number {
  const { columns, hidden } = displayedColumns(entity, sizing);
  return sizing.headerH + (columns.length + (hidden > 0 ? 1 : 0)) * sizing.rowH + sizing.footerH;
}

// ── Disposition ───────────────────────────────────────────────────────────────

const MARGIN = 40;

/**
 * Largeur de la grille des nœuds sans aucune relation.
 *
 * C'était quatre colonnes quel que soit leur nombre : à trente isolés, la grille faisait huit
 * rangées et doublait à elle seule la hauteur de l'emprise, donc divisait par deux l'échelle de
 * cadrage de tout le reste. La grille suit maintenant la forme d'un viewport — plus large que
 * haute — pour coûter le moins de hauteur possible, bornée pour ne pas partir à l'horizontale sur
 * un modèle qui n'a que ça à montrer.
 */
export function orphanColumns(count: number): number {
  if (count <= 0) return 1;
  return Math.min(8, Math.max(4, Math.ceil(Math.sqrt(count * 1.7))));
}

/**
 * Combien de nœuds au plus dans une même colonne de la disposition en couches.
 *
 * Il n'y avait pas de borne : une couche prenait autant de hauteur qu'elle avait de nœuds. Sur la
 * forme la plus banale d'un modèle de données — un hub que trente topics référencent — cela donne
 * une colonne de trente boîtes, soit une emprise de 424 × 5 099 px, qui ne peut par construction
 * *jamais* remplir un écran : le cadrage est contraint par la hauteur, l'échelle tombe à 0,14 et
 * les deux tiers de la largeur restent vides. Une couche trop peuplée se replie donc sur
 * plusieurs sous-colonnes adjacentes, et la disposition tend vers un rectangle plutôt que vers un
 * trait vertical.
 *
 * Ce que ça coûte, et qui est assumé : une arête entrant dans la seconde sous-colonne passe
 * derrière les nœuds de la première (les arêtes sont dessinées avant les nœuds, donc elle
 * disparaît sous une boîte au lieu de la traverser). Sur un éventail de trente arêtes convergeant
 * vers un hub, aucune disposition ne les sépare de toute façon.
 */
export function maxNodesPerColumn(count: number): number {
  return Math.max(1, Math.ceil(Math.sqrt(Math.max(1, count) * 0.75)));
}

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

export interface DrawnEntities {
  /** Ce que le canevas dessine. */
  drawn: DataModelEntity[];
  /** Ce qui est rangé dans la liste à côté — jamais perdu, jamais dessiné. */
  setAside: DataModelEntity[];
}

/**
 * Ce que le diagramme dessine, et ce qui part dans la liste.
 *
 * La règle vivait dans une expression ternaire de la page ; elle décide pourtant de la moitié de
 * la place disponible, puisque chaque entité sans relation ajoute une case à la grille du bas et
 * fait donc baisser l'échelle de cadrage de **tout le reste**. C'est la raison d'être de la liste :
 * une entité qu'aucune relation ne touche n'apprend rien sur un diagramme de relations, et lui
 * garder une place sur le canevas coûte au seul contenu qui en dit quelque chose.
 *
 * Une exception, et une seule : quand *aucune* relation n'a été déduite, tout mettre de côté
 * laisserait un canevas vide après une génération réussie — une page qui ne montre rien alors
 * qu'elle a un modèle. Dans ce cas la grille est le diagramme.
 */
export function drawnEntities(
  entities: DataModelEntity[],
  relations: DataModelRelation[],
  showSetAside: boolean,
): DrawnEntities {
  const { connected, isolated } = splitByConnectivity(entities, relations);
  if (showSetAside || connected.length === 0) return { drawn: entities, setAside: [] };
  return { drawn: connected, setAside: isolated };
}

/**
 * Ce que la liste annonce au-dessus d'elle-même. Elle dit ce qu'elle contient *et* pourquoi ces
 * entités n'ont pas de trait : « 4 » seul se lit comme une troncature, alors que c'est une
 * réponse — aucune relation ne les touche.
 */
/**
 * En dessous, un champ de filtre est du bruit : la liste tient à l'écran et se lit d'un coup
 * d'œil. Au-dessus, elle défile, et cinquante entités sans relation est un résultat parfaitement
 * ordinaire sur une grande sélection.
 */
export const SET_ASIDE_FILTER_MIN = 8;

/**
 * Filtre la liste des entités mises de côté. Même grammaire que le filtre de topics juste
 * au-dessus dans le même panneau (`filterTopics`) — sous-chaîne, ou motif ancré dès qu'il y a
 * un `*` — parce que deux champs voisins ne peuvent pas répondre différemment au même texte.
 *
 * Il compare **le nom de table et le topic**, les deux : la liste affiche l'identifiant Flink
 * (`demo_iot_sensor_0`) et porte le topic en infobulle, donc les deux sont à l'écran et l'un ou
 * l'autre sera tapé.
 */
export function filterEntities(
  entities: DataModelEntity[],
  filter: string,
): DataModelEntity[] {
  const needle = filter.trim();
  if (!needle) return entities;
  if (isTopicPattern(needle)) {
    const matches = topicPatternMatcher(needle);
    return entities.filter(e => matches(e.topic) || matches(e.id));
  }
  const lower = needle.toLowerCase();
  return entities.filter(e => e.id.toLowerCase().includes(lower)
    || e.topic.toLowerCase().includes(lower));
}

/**
 * Ce que le filtre de cette liste a retenu. `null` quand il ne filtre rien — une mention
 * permanente « 6 sur 6 » apprendrait à ne plus lire la ligne. Zéro doit se lire comme un zéro.
 */
export function describeSetAsideFilter(shown: number, total: number): string | null {
  if (shown === total) return null;
  if (shown === 0) return `No set-aside entity matches — ${total} in the list.`;
  return `${shown} of ${total} shown`;
}

export function describeSetAside(count: number, drawn: boolean): string {
  const noun = `${count} ${count === 1 ? 'entity' : 'entities'}`;
  return drawn
    ? `${noun} with no deduced relation, drawn in the grid below the graph`
    : `${noun} with no deduced relation — listed here rather than drawn, so the diagram keeps the room`;
}

/**
 * En dessous, grouper ne compresse rien : la liste tient à l'écran, et un en-tête par famille
 * coûte plus de lignes que la répartition n'en fait gagner. Au-dessus, la liste défile, et la
 * question qu'on lui pose cesse d'être « lesquelles » pour devenir « lesquelles ensemble ».
 *
 * Son propre seuil, plus bas que `SET_ASIDE_FILTER_MIN` : filtrer ne sert qu'une liste qu'on ne
 * peut plus parcourir, alors que grouper apprend quelque chose dès qu'il y a deux familles.
 */
export const SET_ASIDE_GROUP_MIN = 5;

export interface SetAsideGroup {
  /** Le domaine, tel que `topicDomains` le nomme — la famille, pas le topic. */
  domain: string;
  entities: DataModelEntity[];
}

/**
 * Regroupe par famille de topics les entités qu'aucune relation ne touche.
 *
 * Cinquante entités sans relation est un résultat ordinaire sur une grande sélection, et la liste
 * les rendait en une suite plate d'identifiants monospace : ce qu'on y cherche n'est pas
 * *lesquelles*, c'est *lesquelles ensemble* — « toute la famille `iot` est hors du modèle » est
 * un fait, douze lignes n'en sont pas un.
 *
 * Le découpage est celui que la page applique **déjà** aux teintes d'en-tête (`topicDomains`), et
 * la carte est passée plutôt que recalculée : elle se construit sur *tous* les topics du modèle,
 * en retirant les segments de tête que tous partagent, donc la recalculer sur les seules mises de
 * côté nommerait les mêmes familles autrement — deux réponses à « de quelle famille est ce
 * topic ? » sur un même écran. Un topic que la carte ne nomme pas est sa propre famille, ce qui
 * ne peut arriver qu'à un appelant qui passe une carte construite sur d'autres topics.
 *
 * Rend `null` quand grouper n'apprend rien, plutôt qu'un groupe unique que la page aurait à
 * refuser elle-même : sous le seuil, avec une seule famille — l'en-tête répéterait le titre de la
 * section — ou quand chaque famille tient en une entité, où chaque en-tête ne nomme que sa propre
 * ligne. L'ordre met la plus grosse famille d'abord (c'est elle qui est le constat), puis le nom
 * pour que deux modèles identiques se ressemblent.
 */
export function groupSetAside(
  entities: DataModelEntity[],
  domains: Map<string, string>,
): SetAsideGroup[] | null {
  if (entities.length < SET_ASIDE_GROUP_MIN) return null;
  const byDomain = new Map<string, DataModelEntity[]>();
  for (const e of entities) {
    const domain = domains.get(e.topic) ?? e.topic;
    const bucket = byDomain.get(domain);
    if (bucket) bucket.push(e);
    else byDomain.set(domain, [e]);
  }
  if (byDomain.size < 2) return null;
  const groups = [...byDomain.entries()].map(([domain, list]) => ({ domain, entities: list }));
  if (groups.every(g => g.entities.length === 1)) return null;
  groups.sort((a, b) => b.entities.length - a.entities.length || a.domain.localeCompare(b.domain));
  return groups;
}

/**
 * Le nom accessible d'un groupe. L'en-tête visible porte le domaine et son compte côte à côte ;
 * lu à voix haute, « demo.iot 12 » ne dit pas de quoi 12 est le compte.
 */
export function describeSetAsideGroup(group: SetAsideGroup): string {
  const n = group.entities.length;
  return `${group.domain} — ${n} ${n === 1 ? 'entity' : 'entities'} with no deduced relation`;
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
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
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
  // Une couche trop peuplée se replie sur plusieurs sous-colonnes adjacentes : sans ça, un hub
  // référencé par trente topics donne une colonne de trente boîtes qu'aucun écran ne cadre.
  const perColumn = maxNodesPerColumn(connected.length);
  let column = 0;
  layers.forEach(layer => {
    const subColumns = Math.max(1, Math.ceil(layer.length / perColumn));
    // Réparti à égalité plutôt que « perColumn puis le reste » : deux colonnes de cinq se lisent
    // mieux qu'une de sept et une de trois, et l'emprise en hauteur est la même.
    const perSubColumn = Math.ceil(layer.length / subColumns);
    for (let c = 0; c < subColumns; c++) {
      let y = MARGIN;
      for (const id of layer.slice(c * perSubColumn, (c + 1) * perSubColumn)) {
        positions[id] = { x: (column + c) * (sizing.width + sizing.colGap) + MARGIN, y };
        y += entityHeight(byId.get(id)!, sizing) + sizing.rowGap;
      }
      graphBottom = Math.max(graphBottom, y);
    }
    column += subColumns;
  });

  if (isolated.length > 0) {
    const gridTop = layers.length > 0 ? graphBottom + sizing.rowGap : MARGIN;
    const cols = orphanColumns(isolated.length);
    // Hauteur par rangée = le plus haut de la rangée, pour que rien ne se chevauche.
    let y = gridTop;
    for (let i = 0; i < isolated.length; i += cols) {
      const row = isolated.slice(i, i + cols);
      row.forEach((e, colIndex) => {
        positions[e.id] = { x: colIndex * (sizing.width + sizing.orphanGap) + MARGIN, y };
      });
      y += Math.max(...row.map(e => entityHeight(e, sizing))) + sizing.rowGap;
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
export function columnRowY(
  entity: DataModelEntity,
  columnName: string | null,
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): number | null {
  if (columnName === null) return null;
  const index = displayedColumns(entity, sizing).columns.findIndex(c => c.name === columnName);
  if (index < 0) return null;
  return sizing.headerH + index * sizing.rowH + sizing.rowH / 2;
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
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): (EdgeGeometry | null)[] {
  const byId = new Map(entities.map(e => [e.id, e]));

  const raw = relations.map(relation => {
    const from = byId.get(relation.from);
    const to = byId.get(relation.to);
    const fromPos = positions[relation.from];
    const toPos = positions[relation.to];
    if (!from || !to || !fromPos || !toPos) return null;

    const y1 = fromPos.y
      + (columnRowY(from, relation.fromColumn, sizing) ?? entityHeight(from, sizing) / 2);
    const y2 = toPos.y
      + (columnRowY(to, relation.toColumn, sizing)
        ?? columnRowY(to, to.primaryKey, sizing)
        ?? entityHeight(to, sizing) / 2);

    if (toPos.x >= fromPos.x) {
      return { x1: fromPos.x + sizing.width, y1, x2: toPos.x, y2, d1: 1 as const, d2: -1 as const };
    }
    return { x1: fromPos.x, y1, x2: toPos.x + sizing.width, y2, d1: -1 as const, d2: 1 as const };
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
export function graphBounds(
  entities: DataModelEntity[],
  positions: Positions,
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): Bounds | null {
  let bounds: Bounds | null = null;
  for (const entity of entities) {
    const pos = positions[entity.id];
    if (!pos) continue;
    const maxX = pos.x + sizing.width;
    const maxY = pos.y + entityHeight(entity, sizing);
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
 * Au-delà de cette échelle, agrandir n'apprend plus rien : les textes du nœud sont dessinés à
 * corps fixe, donc un modèle d'une table finirait en affiche. En deçà de 1 — ce qui était le
 * plafond — un petit modèle laissait au contraire les trois quarts du canevas vides, ce qui est
 * exactement le reproche fait à cette page.
 */
export const MAX_FIT_SCALE = 1.75;

/**
 * Plancher d'échelle : en dessous, plus rien n'est lisible et le graphe cesse d'être manipulable
 * (un panoramique ne déplacerait plus rien de perceptible). Un graphe qui l'atteint déborde et
 * se parcourt à la minicarte — c'est un aveu, pas un cadrage.
 */
export const MIN_FIT_SCALE = 0.1;

/**
 * L'échelle qui fait tenir le graphe, seule — ce que `fitTransform` calcule et ce que
 * `chooseNodeSizing` compare d'un calibre à l'autre. Les deux ne veulent pas du même plancher :
 * le cadrage en a besoin, la **comparaison** doit s'en passer, sinon deux calibres qui touchent
 * tous les deux le plancher se déclarent équivalents alors que l'un tient dans dix fois moins de
 * place — et le comparateur choisissait alors la plus grosse boîte sur le modèle qui la supporte
 * le moins.
 */
export function fitScale(
  bounds: Bounds,
  viewportWidth: number,
  viewportHeight: number,
  padding = 40,
  topPadding = padding,
  maxScale = 1,
  minScale = MIN_FIT_SCALE,
): number {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxY - bounds.minY);
  return Math.max(minScale, Math.min(
    maxScale,
    (viewportWidth - 2 * padding) / width,
    (viewportHeight - topPadding - padding) / height,
  ));
}

/**
 * La transformation qui cadre le graphe dans le viewport : centré, à l'échelle qui le fait
 * tenir. C'est le geste que Stream Flow a déjà : un reset vers `scale(1)` fixe laissait la
 * moitié d'un grand graphe hors écran.
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
  /**
   * Plafond d'agrandissement. Il vaut 1 par défaut — ce qu'il a toujours valu — pour qu'un
   * appelant qui ne se prononce pas voie exactement le comportement d'avant ; la page, elle,
   * passe `MAX_FIT_SCALE`, parce qu'un modèle de trois tables doit occuper le canevas.
   */
  maxScale = 1,
): { x: number; y: number; scale: number } {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxY - bounds.minY);
  const availableHeight = viewportHeight - topPadding - padding;
  const scale = fitScale(bounds, viewportWidth, viewportHeight, padding, topPadding, maxScale);
  return {
    scale,
    x: (viewportWidth - width * scale) / 2 - bounds.minX * scale,
    y: topPadding + (availableHeight - height * scale) / 2 - bounds.minY * scale,
  };
}

/**
 * En dessous, un texte de nœud n'est plus lu, il est deviné. C'est le seuil qui fait passer au
 * calibre suivant : mesuré sur le **texte rendu** (corps du calibre × échelle de cadrage), qui est
 * la seule grandeur que l'œil voit — un corps de 11 px cadré à 0,4 est plus petit qu'un corps de
 * 9 px cadré à 0,9, alors que la « grande » boîte semblait le choix confortable.
 */
export const MIN_READABLE_TEXT_PX = 7;

export interface FitOptions {
  padding?: number;
  topPadding?: number;
  maxScale?: number;
}

/**
 * Le calibre de boîte qui fait le mieux tenir *ce* modèle dans *ce* viewport.
 *
 * Chaque calibre candidat est disposé et cadré pour de vrai — la disposition est en O(n) et le
 * modèle est borné à cent entités, donc trois passes ne coûtent rien à côté de ce qu'elles
 * évitent — et le premier qui garde son texte lisible gagne. Aucun ne l'est ? Alors c'est celui
 * dont le texte rendu est le plus grand : sur un modèle de cinquante tables, aucune boîte ne rend
 * un diagramme confortable, et le choix honnête est celui qui en montre le plus.
 *
 * Le calibre par défaut est rendu tant que rien n'est mesurable (viewport nul, modèle vide) :
 * choisir sur une taille de zéro donnerait un résultat absurde qui resterait affiché.
 */
export function chooseNodeSizing(
  entities: DataModelEntity[],
  relations: DataModelRelation[],
  viewport: ViewportSize,
  options: FitOptions = {},
): NodeSizing {
  if (entities.length === 0 || viewport.width < 1 || viewport.height < 1) {
    return DEFAULT_NODE_SIZING;
  }
  const { padding = 40, topPadding = padding, maxScale = MAX_FIT_SCALE } = options;

  let best = DEFAULT_NODE_SIZING;
  let bestRendered = -1;
  for (const sizing of NODE_SIZINGS) {
    const bounds = graphBounds(entities, computeLayout(entities, relations, sizing), sizing);
    if (!bounds) continue;
    // Sans plancher : c'est une comparaison, pas un cadrage — voir `fitScale`.
    const scale = fitScale(
      bounds, viewport.width, viewport.height, padding, topPadding, maxScale, 0);
    const rendered = sizing.rowSize * scale;
    if (rendered >= MIN_READABLE_TEXT_PX) return sizing;
    if (rendered > bestRendered) { bestRendered = rendered; best = sizing; }
  }
  return best;
}

/**
 * Le corps réellement affiché du texte de ligne pour un calibre donné : son corps multiplié par
 * l'échelle à laquelle il se cadrerait. C'est la grandeur que `chooseNodeSizing` compare, et la
 * seule que l'œil voie — sortie ici parce que l'interface doit pouvoir la dire quand un calibre
 * est imposé à la main, où plus rien ne garantit qu'elle reste lisible.
 */
export function renderedTextPx(
  sizing: NodeSizing,
  entities: DataModelEntity[],
  relations: DataModelRelation[],
  viewport: ViewportSize,
  options: FitOptions = {},
): number {
  if (entities.length === 0 || viewport.width < 1 || viewport.height < 1) return sizing.rowSize;
  const { padding = 40, topPadding = padding, maxScale = MAX_FIT_SCALE } = options;
  const bounds = graphBounds(entities, computeLayout(entities, relations, sizing), sizing);
  if (!bounds) return sizing.rowSize;
  return sizing.rowSize * fitScale(
    bounds, viewport.width, viewport.height, padding, topPadding, maxScale, 0);
}

/**
 * Le calibre est **choisi par la page, pas imposé par elle**.
 *
 * `chooseNodeSizing` optimise pour la lisibilité, ce qui est le bon défaut et n'est pas toujours
 * ce qu'on veut : un opérateur qui cherche une colonne précise sur un modèle de cinquante tables
 * préfère les voir toutes et zoomer, et l'automatisme lui répondait « six colonnes sur vingt »
 * sans recours. `auto` reste le défaut ; les trois autres valeurs court-circuitent le calcul.
 *
 * Délibérément **non persisté**, contrairement au repli du panneau : le bon calibre dépend du
 * modèle affiché, donc un `comfortable` gardé d'une visite à l'autre s'appliquerait un jour à un
 * modèle de cent entités et le rendrait illisible sans que rien ne l'explique. `auto` couvre déjà
 * celui qui préfère le compact, puisqu'il l'élit dès que le modèle le demande.
 */
export type DensityChoice = 'auto' | 'comfortable' | 'default' | 'compact';

export const DENSITY_SIZINGS: Record<Exclude<DensityChoice, 'auto'>, NodeSizing> = {
  comfortable: COMFORTABLE_NODE_SIZING,
  default: DEFAULT_NODE_SIZING,
  compact: COMPACT_NODE_SIZING,
};

export function resolveNodeSizing(
  choice: DensityChoice,
  entities: DataModelEntity[],
  relations: DataModelRelation[],
  viewport: ViewportSize,
  options: FitOptions = {},
): NodeSizing {
  if (choice !== 'auto') return DENSITY_SIZINGS[choice];
  return chooseNodeSizing(entities, relations, viewport, options);
}

/** Le nom du calibre, par identité — l'interface doit pouvoir dire ce que `auto` a élu. */
export function densityLabel(sizing: NodeSizing): Exclude<DensityChoice, 'auto'> {
  if (sizing === COMFORTABLE_NODE_SIZING) return 'comfortable';
  if (sizing === COMPACT_NODE_SIZING) return 'compact';
  return 'default';
}

/**
 * Ce que `auto` a décidé, en toutes lettres. `null` sur un choix explicite : le sélecteur le dit
 * déjà, et répéter la valeur choisie juste en dessous n'apprend rien.
 */
export function describeDensity(choice: DensityChoice, sizing: NodeSizing): string | null {
  return choice === 'auto' ? `Auto chose ${densityLabel(sizing)} boxes for this model.` : null;
}

/**
 * Ce que le calibre courant coûte, quand il coûte quelque chose : un texte qui se rend sous le
 * seuil de lisibilité. Dit quel que soit le choix — `auto` y tombe aussi quand aucun calibre ne
 * s'en sort — parce que c'est un fait sur ce qui est à l'écran, pas un reproche à l'opérateur.
 */
export function describeDensityCost(renderedPx: number): string | null {
  if (renderedPx >= MIN_READABLE_TEXT_PX) return null;
  return `Column names render around ${renderedPx.toFixed(1)} px at this fit — zoom in to read them.`;
}

/**
 * Sous ce seuil, un changement de taille n'est pas un changement de cadrage : une barre de
 * défilement qui apparaît, un arrondi de sous-pixel. Recadrer là-dessus ferait sautiller le
 * graphe sans rien améliorer.
 */
export const RESIZE_EPSILON_PX = 8;

export interface ViewportSize { width: number; height: number }

/**
 * Ce changement de taille mérite-t-il un recadrage ?
 *
 * Le cadrage est calculé pour un rectangle donné, et il n'était recalculé qu'après une
 * génération, au franchissement du seuil desktop, et sur le bouton de recadrage. Tout le reste
 * le laissait décrire un viewport qui n'existait plus : redimensionner la fenêtre, et surtout
 * **ouvrir l'inspecteur**, qui prend 320 px au canevas sur desktop — le graphe se retrouvait
 * décalé, une partie passant sous le panneau qui venait de s'ouvrir.
 *
 * Une première mesure ne déclenche rien (`before` nul) : le cadrage initial vient d'ailleurs.
 * Une taille nulle non plus — un onglet caché ou un panneau replié mesurent zéro, et cadrer sur
 * zéro donne une transformation absurde qui resterait à l'écran au retour.
 */
export function isSignificantResize(before: ViewportSize | null, after: ViewportSize): boolean {
  if (after.width < 1 || after.height < 1) return false;
  if (!before) return false;
  return Math.abs(after.width - before.width) >= RESIZE_EPSILON_PX
      || Math.abs(after.height - before.height) >= RESIZE_EPSILON_PX;
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
  sizing: NodeSizing = DEFAULT_NODE_SIZING,
): { x: number; y: number; scale: number } {
  // Le plafond de 1 borne le *plancher*, pas l'échelle courante : `Math.min(1, …)` appliqué au
  // résultat faisait dézoomer un cadrage déjà plus près que 1 — sauter sur une entité est un
  // geste pour s'en approcher, jamais pour s'en éloigner.
  const scale = Math.max(currentScale, Math.min(1, minScale));
  const cx = pos.x + sizing.width / 2;
  const cy = pos.y + entityHeight(entity, sizing) / 2;
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
 * Raccourcit un nom de colonne pour la carte, **en gardant la fin**.
 *
 * Les chemins imbriqués gardent leur notation pointée (`shipping.address.city`), et une coupe
 * par la droite — `shipping.address.ci…` — ampute précisément la partie qui distingue une
 * colonne d'une autre : trois champs d'un même sous-objet deviennent trois lignes identiques.
 * C'est le préfixe qui est redondant sur une carte où il se répète, donc c'est lui qu'on élide.
 *
 * Un nom sans point est tronqué par la droite comme avant : il n'a pas de partie redondante, et
 * en couper le début rendrait le champ méconnaissable.
 */
export function shortenColumnName(name: string, max: number): string {
  if (name.length <= max) return name;
  if (!name.includes('.')) return `${name.slice(0, max - 1)}…`;

  // Garde le plus de segments de queue possible ; si la feuille seule dépasse déjà, on la coupe
  // par la droite plutôt que de rendre une ellipse seule.
  const segments = name.split('.');
  let tail = segments[segments.length - 1];
  if (tail.length + 1 > max) return `…${tail.slice(0, max - 2)}…`;
  for (let i = segments.length - 2; i >= 0; i--) {
    const candidate = `${segments[i]}.${tail}`;
    if (candidate.length + 1 > max) break;
    tail = candidate;
  }
  return `…${tail}`;
}

export interface OrphanKey {
  column: string;
  /** Le mot d'entité que le nom désigne — `customer_id` → `customer`. */
  base: string;
}

/**
 * Les colonnes qui **se lisent comme une clé étrangère** et dont pourtant aucune relation n'est
 * sortie. Sans ça elles sont indiscernables d'une colonne ordinaire, et un diagramme qui paraît
 * incomplet ne dit pas pourquoi il l'est.
 *
 * La question « ce nom désigne-t-il une autre entité ? » est tranchée par le serveur et voyage
 * dans `keyBase` : c'est la règle exacte que `deduceRelations` applique avant de chercher une
 * cible. Elle a d'abord été réimplémentée ici, et ce miroir avait un défaut que le serveur n'a
 * pas — une colonne dont le nom reprend les mots de son propre topic (`order_id` sur un topic
 * orders) est une identité, pas une référence, et se faisait marquer à tort.
 *
 * La clé primaire reste exclue explicitement : le serveur ne peut pas la désigner par ce chemin
 * (son nom échoit à la règle ci-dessus), mais la garde est gratuite et rend la règle lisible.
 */
export function orphanKeyColumns(entity: DataModelEntity): OrphanKey[] {
  const orphans: OrphanKey[] = [];
  for (const column of entity.columns) {
    if (column.primaryKey || column.references !== null) continue;
    if (column.keyBase === null || column.keyBase === '') continue;
    orphans.push({ column: column.name, base: column.keyBase });
  }
  return orphans;
}

/**
 * La phrase qui accompagne une colonne orpheline dans l'inspecteur.
 *
 * Une seule branche, et c'est le gain du passage côté serveur : une cible présente dans le
 * modèle produit *toujours* une relation, donc une base sans référence veut dire exactement que
 * le topic visé n'est pas dans la sélection. C'était auparavant une vérification côté
 * navigateur, avec une seconde phrase pour le cas où elle disait le contraire — un cas qui ne
 * pouvait pas se produire.
 */
export function describeOrphanKey(orphan: OrphanKey): string {
  return `Reads as a key naming '${orphan.base}' — no selected topic is named after it.`;
}

// ── Minicarte ─────────────────────────────────────────────────────────────────

export interface MinimapLayout {
  scale: number;
  /** Décalage en coordonnées de la boîte, pour centrer le graphe dedans. */
  offsetX: number;
  offsetY: number;
}

/** La transformation qui fait tenir tout le graphe dans la boîte de la minicarte. */
export function minimapLayout(bounds: Bounds, boxWidth: number, boxHeight: number,
                              padding = 4): MinimapLayout {
  const width = Math.max(1, bounds.maxX - bounds.minX);
  const height = Math.max(1, bounds.maxY - bounds.minY);
  const scale = Math.min(
    (boxWidth - 2 * padding) / width,
    (boxHeight - 2 * padding) / height,
  );
  return {
    scale,
    offsetX: (boxWidth - width * scale) / 2 - bounds.minX * scale,
    offsetY: (boxHeight - height * scale) / 2 - bounds.minY * scale,
  };
}

/** Le rectangle du graphe actuellement visible, en coordonnées du graphe. */
export function visibleGraphRect(
  transform: { x: number; y: number; scale: number },
  viewportWidth: number,
  viewportHeight: number,
): Bounds {
  const minX = -transform.x / transform.scale;
  const minY = -transform.y / transform.scale;
  return {
    minX,
    minY,
    maxX: minX + viewportWidth / transform.scale,
    maxY: minY + viewportHeight / transform.scale,
  };
}

/**
 * Le graphe tient-il entièrement dans le viewport ?
 *
 * C'est la condition d'affichage de la minicarte : quand tout est déjà à l'écran, elle ne
 * montrerait qu'un rectangle couvrant sa propre boîte — un ornement qui n'apprend rien et prend
 * un coin du canevas.
 */
export function graphFullyVisible(
  bounds: Bounds,
  transform: { x: number; y: number; scale: number },
  viewportWidth: number,
  viewportHeight: number,
): boolean {
  const visible = visibleGraphRect(transform, viewportWidth, viewportHeight);
  return visible.minX <= bounds.minX && visible.minY <= bounds.minY
    && visible.maxX >= bounds.maxX && visible.maxY >= bounds.maxY;
}

/** La transformation qui centre le viewport sur un point du graphe — le clic dans la minicarte. */
export function centerOnGraphPoint(
  point: { x: number; y: number },
  viewportWidth: number,
  viewportHeight: number,
  scale: number,
): { x: number; y: number; scale: number } {
  return {
    scale,
    x: viewportWidth / 2 - point.x * scale,
    y: viewportHeight / 2 - point.y * scale,
  };
}

// ── Repli du panneau de sélection ─────────────────────────────────────────────

export const PANEL_KEY = 'kse:data-model-panel';

/**
 * Le panneau de sélection coûte 256 px en permanence, et une fois le modèle généré il ne sert
 * plus à rien tant qu'on lit le diagramme — qui est la seule chose que cette page existe pour
 * montrer. Il se replie donc en rail, et l'état suit l'opérateur d'une visite à l'autre : même
 * mécanique que le panneau de critères de Stream Flow (`streamFlow.ts`), volontairement sous une
 * clé distincte, les deux replis ne décrivant pas le même geste.
 */
export function readPanelOpen(fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(PANEL_KEY);
    // Une valeur inconnue retombe sur le défaut : un stockage abîmé ne décide pas de la mise en page.
    return raw === '1' ? true : raw === '0' ? false : fallback;
  } catch {
    return fallback;
  }
}

export function writePanelOpen(open: boolean): void {
  try {
    localStorage.setItem(PANEL_KEY, open ? '1' : '0');
  } catch {
    // Quota ou mode privé : le repli reste un confort, jamais un blocage.
  }
}

// ── Modèles enregistrés ───────────────────────────────────────────────────────

export const SAVED_MODELS_KEY = 'kse:data-model-saved';
/** Au-delà, la liste cesse d'être un raccourci et devient elle-même à parcourir. */
export const MAX_SAVED_MODELS = 20;
const SAVED_ENVELOPE_VERSION = 1;

export interface SavedModel {
  name: string;
  topics: string[];
  /**
   * Le budget sous lequel la sélection devait être lue. `null` sur une entrée écrite avant que
   * le budget ne soit réglable — elle se relit, elle reprend simplement le défaut.
   */
  maxTopics: number | null;
  /** Quand elle a été enregistrée — affichée, jamais utilisée pour périmer l'entrée. */
  at: number;
}

interface SavedEnvelope { v: number; models: SavedModel[] }

/**
 * Les sélections nommées, gardées par le navigateur.
 *
 * L'URL rend une sélection partageable mais anonyme : rien ne dit qu'elle est *le* modèle de
 * commande, et la retrouver suppose d'avoir gardé le lien. Même forme que `flowChains` — une
 * enveloppe versionnée effacée plutôt que devinée, un nombre borné d'entrées, une écriture au
 * mieux — avec **une différence assumée : pas de péremption**. Un brouillon est un accident de
 * navigation, qu'il est juste d'oublier au bout d'une semaine ; un enregistrement nommé est un
 * geste délibéré, et l'effacer sans que personne l'ait demandé serait une perte de données.
 */
export function readSavedModels(): SavedModel[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(SAVED_MODELS_KEY);
  } catch {
    return [];
  }
  if (!raw) return [];

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearSavedModels();
    return [];
  }

  const envelope = parsed as SavedEnvelope;
  if (!envelope || typeof envelope !== 'object' || envelope.v !== SAVED_ENVELOPE_VERSION
      || !Array.isArray(envelope.models)) {
    clearSavedModels();
    return [];
  }

  return envelope.models
    .filter((m): m is SavedModel => !!m && typeof m === 'object'
      && typeof m.name === 'string' && m.name !== '' && Array.isArray(m.topics))
    .map(m => ({
      name: m.name,
      topics: m.topics.filter((t): t is string => typeof t === 'string' && t !== ''),
      maxTopics: typeof m.maxTopics === 'number' && Number.isFinite(m.maxTopics) && m.maxTopics >= 1
        ? Math.floor(m.maxTopics)
        : null,
      at: typeof m.at === 'number' ? m.at : 0,
    }))
    .filter(m => m.topics.length > 0)
    .slice(0, MAX_SAVED_MODELS);
}

function writeSavedModels(models: SavedModel[]): void {
  try {
    localStorage.setItem(SAVED_MODELS_KEY,
      JSON.stringify({ v: SAVED_ENVELOPE_VERSION, models } satisfies SavedEnvelope));
  } catch {
    // Quota plein, mode privé : enregistrer est un confort, jamais un blocage.
  }
}

export function clearSavedModels(): void {
  try {
    localStorage.removeItem(SAVED_MODELS_KEY);
  } catch { /* idem */ }
}

/**
 * Enregistre une sélection sous un nom. Un nom déjà pris est **remplacé, en place** : c'est ce
 * que « enregistrer sous le même nom » veut dire partout ailleurs, et empiler deux entrées
 * homonymes rendrait la liste inutilisable.
 */
export function saveModel(name: string, topics: string[], maxTopics: number,
                          now: number = Date.now()): SavedModel[] {
  const trimmed = name.trim();
  if (trimmed === '' || topics.length === 0) return readSavedModels();
  const existing = readSavedModels().filter(m => m.name !== trimmed);
  const models = [{ name: trimmed, topics: [...topics], maxTopics, at: now }, ...existing]
    .slice(0, MAX_SAVED_MODELS);
  writeSavedModels(models);
  return models;
}

export function deleteSavedModel(name: string): SavedModel[] {
  const models = readSavedModels().filter(m => m.name !== name);
  writeSavedModels(models);
  return models;
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
  // Le cas binaire du général : deux façons de nommer les mêmes tables finiraient par diverger,
  // et c'est un défaut que le test de ce module a déjà attrapé une fois.
  const [first, second] = joinAliasesFor([fromTopic, toTopic]);
  return [first, second];
}

/** Un identifiant SQL sans guillemets, ou la chaîne vide s'il n'en reste rien d'exploitable. */
function sqlAlias(value: string): string {
  const cleaned = value.replace(/[^A-Za-z0-9_]/g, '_');
  // Un alias ne peut pas commencer par un chiffre : sans ça la requête meurt au parseur.
  return /^[A-Za-z_]/.test(cleaned) ? cleaned : '';
}

/**
 * Les alias de N topics — même arbitrage que `joinAliases`, généralisé : le segment distinctif
 * d'abord, la queue ensuite, et à défaut un suffixe numéroté. Le suffixe est per-topic ici plutôt
 * que dérivé du premier, sinon trois tables se retrouveraient nommées d'après une seule.
 */
export function joinAliasesFor(topics: string[]): string[] {
  const domains = topicDomains(topics);
  const domain = (topic: string) => sqlAlias(domains.get(topic) ?? '');
  const tail = (topic: string) => sqlAlias(
    topic.split(/[._-]+/).filter(s => s && !/^\d+$/.test(s)).pop() ?? '');

  for (const pick of [domain, tail]) {
    const names = topics.map(pick);
    if (names.every(n => n !== '') && new Set(names).size === names.length) return names;
  }

  // Rien de distinct : on garde le meilleur nom disponible et on numérote les homonymes.
  const bases = topics.map(topic => tail(topic) || domain(topic) || 'a');
  const totals = new Map<string, number>();
  bases.forEach(base => totals.set(base, (totals.get(base) ?? 0) + 1));
  const seen = new Map<string, number>();
  return bases.map(base => {
    if (totals.get(base) === 1) return base;
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    return `${base}_${n}`;
  });
}

export interface MultiJoinResult {
  /** `null` quand aucune requête ne peut être écrite — `problem` dit alors pourquoi. */
  sql: string | null;
  caveats: string[];
  problem: string | null;
}

/**
 * La requête qui joint **plusieurs** entités le long des relations déduites entre elles.
 *
 * `buildJoinSql` répond à « ouvre cette relation » ; celle-ci répond à « ouvre ce sous-graphe »,
 * qui est la vraie forme de la question sur un schéma en étoile — recoller trois tables à la main
 * en enchaînant deux requêtes à deux tables est précisément le travail que ce diagramme connaît
 * déjà.
 *
 * Elle **refuse plutôt que d'inventer un prédicat**, la même règle que la version binaire, et
 * elle le dit : un ensemble que les relations déduites ne relient pas n'a pas de jointure, et en
 * fabriquer une reviendrait à poser une égalité que rien n'étaye. L'arbre couvrant est construit
 * en largeur depuis la première entité, donc chaque table jointe l'est à une table déjà présente
 * — un `JOIN` dont le prédicat cite une table pas encore introduite ne s'analyse pas.
 */
export function buildMultiJoinSql(
  entityIds: string[],
  entities: DataModelEntity[],
  relations: DataModelRelation[],
): MultiJoinResult {
  const selected = entityIds
    .map(id => entities.find(e => e.id === id))
    .filter((e): e is DataModelEntity => e !== undefined);

  if (selected.length < 2) {
    return { sql: null, caveats: [], problem: 'Pick at least two entities to join.' };
  }

  const caveats: string[] = [];
  const inSet = new Set(selected.map(e => e.id));
  const byId = new Map(selected.map(e => [e.id, e]));

  /** Le prédicat qu'une relation décrit, ou `null` si elle n'en décrit aucun. */
  const predicateOf = (relation: DataModelRelation): string | null => {
    const to = byId.get(relation.to);
    if (!to) return null;
    if (relation.toColumn !== null) return relation.toColumn;
    if (to.primaryKey === null) return null;
    return to.primaryKey;
  };

  // Arêtes utilisables : les deux extrémités dans l'ensemble, et un prédicat résoluble.
  const usable = relations.filter(r => inSet.has(r.from) && inSet.has(r.to)
    && predicateOf(r) !== null);

  const adjacency = new Map<string, DataModelRelation[]>();
  for (const relation of usable) {
    (adjacency.get(relation.from) ?? adjacency.set(relation.from, []).get(relation.from)!).push(relation);
    (adjacency.get(relation.to) ?? adjacency.set(relation.to, []).get(relation.to)!).push(relation);
  }

  // Parcours en largeur : chaque table entre en se joignant à une table déjà introduite.
  const root = selected[0];
  const joined = new Set<string>([root.id]);
  const order: { entity: DataModelEntity; relation: DataModelRelation }[] = [];
  const queue = [root.id];
  while (queue.length > 0) {
    const current = queue.shift()!;
    for (const relation of adjacency.get(current) ?? []) {
      const nextId = relation.from === current ? relation.to : relation.from;
      if (joined.has(nextId)) continue;
      joined.add(nextId);
      order.push({ entity: byId.get(nextId)!, relation });
      queue.push(nextId);
    }
  }

  const unreached = selected.filter(e => !joined.has(e.id));
  if (unreached.length > 0) {
    return {
      sql: null,
      caveats: [],
      problem: `No deduced relation connects ${unreached.map(e => e.id).join(', ')} to the rest `
        + 'of the selection — a join needs a predicate for every table, and inventing one would '
        + 'assert an equality nothing supports.',
    };
  }

  const topics = [root, ...order.map(o => o.entity)].map(e => e.topic);
  const aliasList = joinAliasesFor(topics);
  const aliasOf = new Map<string, string>();
  aliasOf.set(root.id, aliasList[0]);
  order.forEach((step, i) => aliasOf.set(step.entity.id, aliasList[i + 1]));

  const joinColumns = new Map<string, Set<string>>();
  const noteColumn = (id: string, column: string) => {
    const set = joinColumns.get(id) ?? new Set<string>();
    set.add(column);
    joinColumns.set(id, set);
  };
  for (const step of order) {
    const target = predicateOf(step.relation)!;
    noteColumn(step.relation.from, step.relation.fromColumn);
    noteColumn(step.relation.to, target);
    if (step.relation.fromColumn.includes('.') || target.includes('.')) {
      caveats.push('A join column is a nested path — check how it resolves before running.');
    }
  }

  const projected = (entity: DataModelEntity): string[] => {
    const alias = aliasOf.get(entity.id)!;
    const keys = joinColumns.get(entity.id) ?? new Set<string>();
    const flat = entity.columns.filter(c => !c.name.includes('.'));
    if (flat.length < entity.columns.length) {
      caveats.push(`${entity.columns.length - flat.length} nested field(s) of ${entity.id} are `
        + 'left out of the projection — a nested path behind a table alias does not mean the '
        + 'same thing in Flink SQL as it does on the diagram.');
    }
    const ordered = [
      ...flat.filter(c => keys.has(c.name) || c.primaryKey),
      ...flat.filter(c => !keys.has(c.name) && !c.primaryKey),
    ];
    return ordered.slice(0, JOIN_MAX_COLUMNS).map(c => `    ${alias}.${c.name}`);
  };

  const columns = [root, ...order.map(o => o.entity)].flatMap(projected);

  const joinLines = order.map(step => {
    const target = predicateOf(step.relation)!;
    const fromAlias = aliasOf.get(step.relation.from)!;
    const toAlias = aliasOf.get(step.relation.to)!;
    return `JOIN ${step.entity.id} AS ${aliasOf.get(step.entity.id)}\n`
      + `  ON ${fromAlias}.${step.relation.fromColumn} = ${toAlias}.${target}`;
  });

  const grades = [...new Set(order.map(o => o.relation.confidence.toLowerCase()))].join(', ');
  const sql = [
    `-- ${selected.length} entities joined along ${order.length} deduced relation(s) (${grades}).`,
    ...order.map(o => `-- ${o.relation.reason}`),
    ...[...new Set(caveats)].map(c => `-- ${c}`),
    '-- Regular join: Flink keeps every side in state — fine here, not on large topics.',
    'SELECT',
    columns.join(',\n'),
    `FROM ${root.id} AS ${aliasOf.get(root.id)}`,
    ...joinLines,
    'LIMIT 50',
  ].join('\n');

  return { sql, caveats: [...new Set(caveats)], problem: null };
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

// ── Construction en cours ─────────────────────────────────────────────────────

/**
 * Ce qu'on peut honnêtement dire pendant qu'un modèle se construit.
 *
 * `POST /api/data-model` est **une seule requête** : le serveur lit les topics en parallèle et ne
 * répond qu'une fois tout inféré. Le navigateur ne sait donc pas quel topic est en cours, et il
 * n'y a **aucun pourcentage à afficher** — en inventer un (« 40 % », dérivé du temps écoulé) serait
 * une mesure fabriquée, exactement ce que cette page refuse partout ailleurs. Ce qui est vrai et
 * utile : combien de topics sont lus, et depuis combien de temps.
 *
 * Le budget par topic (20 s côté serveur) n'est délibérément pas cité : c'est une constante du
 * serveur, et une UI qui la recopie ment le jour où elle change.
 */
/**
 * Le plancher d'attente du navigateur. C'était la valeur *unique* — `timeout: 120_000`, écrite
 * en dur aux deux points d'appel — ce qui allait tant que le plafond valait 30 topics et
 * n'allait plus à 100 : une génération dont les topics répondent lentement dépasse deux minutes,
 * axios abandonne, l'opérateur voit une erreur de délai *pendant que le serveur travaille
 * encore*, et la tentative suivante repart de zéro. Ça reste le plancher, pour qu'une petite
 * génération se comporte exactement comme avant.
 */
export const MIN_REQUEST_TIMEOUT_MS = 120_000;

/** Marge au-dessus du pire cas serveur : le réseau et la sérialisation de la réponse. */
const REQUEST_TIMEOUT_SLACK_MS = 15_000;

/**
 * Combien de temps attendre une génération de `topics` topics.
 *
 * Le pire cas côté serveur est `ceil(topics / threads) × budget par topic` : les topics partent
 * tous en même temps sur un pool borné, et chacun a son propre délai. Les deux nombres viennent
 * de `GET /api/data-model/limits` — **jamais recopiés ici**, c'est exactement la règle qui
 * interdit à cette page de citer le budget par topic dans son overlay : un constante serveur
 * recopiée dans l'UI ment le jour où elle change.
 *
 * Sans les bornes, on garde le plancher : attendre moins que ce que le serveur peut mettre est
 * un défaut, attendre plus n'en est pas un — l'utilisateur garde le bouton Stop.
 */
export function requestTimeoutMs(topics: number, limits: DataModelLimits | null): number {
  if (!limits || limits.inferenceThreads < 1 || limits.perTopicTimeoutMs < 1) {
    return MIN_REQUEST_TIMEOUT_MS;
  }
  const waves = Math.ceil(Math.max(1, topics) / limits.inferenceThreads);
  const worstCase = waves * limits.perTopicTimeoutMs + REQUEST_TIMEOUT_SLACK_MS;
  return Math.max(MIN_REQUEST_TIMEOUT_MS, worstCase);
}

/**
 * La borne haute de l'attente, dite à l'opérateur — et seulement quand elle dépasse le plancher,
 * sinon c'est du bruit sur une génération qui prendra quelques secondes.
 *
 * C'est une **borne**, pas une prédiction : la phrase le dit, parce qu'une durée annoncée sans
 * cette précision se lit comme une estimation, et une estimation fabriquée est précisément ce
 * que cette page refuse (cf. `describeBuildProgress`, qui n'invente aucun pourcentage).
 */
export function describeBuildBudget(topics: number, limits: DataModelLimits | null): string | null {
  const budget = requestTimeoutMs(topics, limits);
  if (budget <= MIN_REQUEST_TIMEOUT_MS) return null;
  const minutes = Math.ceil(budget / 60_000);
  return `Up to ${minutes} min if every topic is slow to answer.`;
}

export function describeBuildProgress(topics: number, elapsedMs: number): string {
  const scope = `Reading ${topics} ${topics === 1 ? 'topic' : 'topics'}`;
  const seconds = Math.floor(elapsedMs / 1000);
  if (seconds < 1) return `${scope}…`;
  if (seconds < 60) return `${scope}… ${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${scope}… ${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
}

/**
 * La phrase qui accompagne un graphe encore à l'écran pendant qu'un autre se construit. Sans
 * elle, l'ancien modèle passe pour le nouveau — et c'est le cas le plus trompeur, puisque rien
 * ne bouge à l'écran.
 */
export function describeStaleGraphDuringBuild(hasPreviousModel: boolean): string | null {
  return hasPreviousModel ? 'The diagram behind is the previous model, until this one answers.' : null;
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
  return `${relation.from}\0${relation.fromColumn}\0${relation.to}\0${relation.toColumn ?? ''}`;
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
