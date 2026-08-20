// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import axios from 'axios';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useCatalog } from '../catalogStore';
import { useIsDesktop } from '../breakpoints';
import { addTopicEntries, describeTopicEntry, suggestBroaderPattern } from '../topicSelection';
import { Button, Checkbox, EmptyState, ErrorPanel, Badge, TopicInput, Combobox, NumberInput, Spinner } from '../components/ui';
import { useToast } from '../components/Toast';
import { describeApiError } from './queryError';
import type { QueryErrorInfo } from './queryError';
import type { DataModelEntity, DataModelLimits, DataModelResponse } from '../api/types';
import {
  DEFAULT_MAX_TOPICS, NODE_W, HEADER_H, ROW_H,
  filterTopics, toggleTopic, selectAll, topicsFromQuery, buildQuery, capTopics,
  displayedColumns, entityHeight, computeLayout, computeEdgeGeometry, splitByConnectivity,
  crowFootPath, oneBarPath, graphBounds, fitTransform, centerOnEntity, topicDomains, domainColors,
  formatCount, describeRelation, matchingColumns, describeColumnMatches,
  buildExportSvg, exportNotes, toMermaidEr, buildJoinSql,
  diffModels, describeDiff, diffIsEmpty,
  filterRelations, describeRelationFilter, orphanKeyColumns, describeOrphanKey,
  shortenColumnName, readSelectionDraft, saveSelectionDraft, maxTopicsFromQuery,
  minimapLayout, visibleGraphRect, graphFullyVisible, centerOnGraphPoint,
  describeBuildProgress, describeStaleGraphDuringBuild, clampMaxTopics,
  requestTimeoutMs, describeBuildBudget,
  readSavedModels, saveModel, deleteSavedModel, buildMultiJoinSql,
  CONFIDENCE_STYLE, describeModel,
} from './dataModel';
import type { OrphanKey, SavedModel } from './dataModel';
import type { DataModelRelation, RelationConfidence } from '../api/types';

/**
 * La page Modèle de données : une sélection de topics → les entités (topics lus comme des
 * tables, colonnes inférées) et les relations déduites, rendues en diagramme entité-relation.
 * Toute la logique décidable vit dans `dataModel.ts` ; ici il n'y a que le rendu et le câblage.
 */

/** Boîte de la minicarte, en pixels — assez grande pour situer, assez petite pour ne pas gêner. */
const MINIMAP_W = 168;
const MINIMAP_H = 120;

// ── Nœud-table ────────────────────────────────────────────────────────────────

const EntityNode: React.FC<{
  entity: DataModelEntity;
  x: number; y: number;
  selected: boolean;
  dimmed: boolean;
  /** Teinte du domaine du topic — c'est elle qui fait apparaître les sous-systèmes. */
  tint: { header: string; accent: string };
  /** Colonnes que la recherche de champ désigne dans cette entité. */
  highlighted: Set<string> | undefined;
  /** Colonnes qui se lisent comme une clé étrangère sans qu'aucune relation en soit sortie. */
  orphanKeys: Set<string> | undefined;
  onClick: () => void;
}> = ({ entity, x, y, selected, dimmed, tint, highlighted, orphanKeys, onClick }) => {
  const { columns, hidden } = displayedColumns(entity);
  const height = entityHeight(entity);
  const stroke = selected ? '#ffffff' : tint.accent;
  const exactCount = entity.messageCount !== null
    ? `${entity.messageCount.toLocaleString()} messages`
    : 'message count unavailable';

  return (
    <g
      data-node="true"
      tabIndex={0}
      role="button"
      aria-pressed={selected}
      aria-label={`${entity.id}, ${entity.columns.length} columns, ${exactCount}`}
      className="cursor-pointer focus:outline-none"
      style={{ opacity: dimmed ? 0.15 : 1, transition: 'opacity 0.15s' }}
      onClick={onClick}
      onKeyDown={e => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault();
        e.stopPropagation();
        onClick();
      }}
    >
      {/* Le compte de l'en-tête est abrégé faute de place ; l'exact voyage ici — un nombre
          compacté sans son original est une information qu'on ne peut plus vérifier. */}
      <title>{`${entity.topic} · ${exactCount}`}</title>
      {selected && (
        <rect x={x - 4} y={y - 4} width={NODE_W + 8} height={height + 8} rx={10}
          fill={tint.accent} fillOpacity={0.08} stroke={tint.accent} strokeWidth={1} strokeOpacity={0.3} />
      )}
      <rect x={x} y={y} width={NODE_W} height={height} rx={8}
        fill="#12151a" stroke={stroke} strokeWidth={selected ? 2 : 1.2} strokeOpacity={selected ? 1 : 0.7} />
      {/* En-tête : nom de table + topic d'origine, teinté par domaine */}
      <rect x={x} y={y} width={NODE_W} height={HEADER_H} rx={8} fill={tint.header} />
      <rect x={x} y={y + HEADER_H - 8} width={NODE_W} height={8} fill={tint.header} />
      <text x={x + 10} y={y + 18} fill="white" fontSize={12} fontWeight="bold"
        fontFamily="JetBrains Mono, monospace">
        {entity.id.length > 26 ? entity.id.slice(0, 25) + '…' : entity.id}
      </text>
      <text x={x + 10} y={y + 33} fill="#79839a" fontSize={9} fontFamily="Inter, sans-serif">
        {entity.topic.length > 32 ? entity.topic.slice(0, 31) + '…' : entity.topic}
        {entity.format ? ` · ${entity.format}` : ''}
        {entity.messageCount !== null ? ` · ${formatCount(entity.messageCount)} msg` : ''}
      </text>
      <line x1={x} y1={y + HEADER_H} x2={x + NODE_W} y2={y + HEADER_H}
        stroke={tint.accent} strokeOpacity={0.35} />
      {/* Colonnes */}
      {columns.map((column, i) => {
        const rowY = y + HEADER_H + (i + 1) * ROW_H - 6;
        // `?` : se lit comme une clé étrangère, mais rien n'en est sorti. Sans marqueur elle est
        // indiscernable d'une colonne ordinaire, et le diagramme paraît incomplet sans dire
        // pourquoi. Le détail — et la seule moitié vérifiable de la cause — est dans l'inspecteur.
        const orphan = orphanKeys?.has(column.name) ?? false;
        const marker = column.primaryKey ? '🔑' : column.references ? '→' : orphan ? '?' : '';
        const name = shortenColumnName(column.name, 22);
        const lit = highlighted?.has(column.name) ?? false;
        return (
          <g key={column.name}>
            {lit && (
              <rect x={x + 4} y={rowY - ROW_H + 6} width={NODE_W - 8} height={ROW_H} rx={3}
                fill="#f5c264" fillOpacity={0.18} />
            )}
            <text x={x + 10} y={rowY} fontSize={10} fontFamily="JetBrains Mono, monospace"
              fontWeight={lit ? 'bold' : 'normal'}
              fill={lit ? '#f5c264' : column.primaryKey ? '#7ee2a8'
                : column.references ? '#f5c264' : orphan ? '#8d8577' : '#c5cad6'}>
              {/* Un chemin raccourci garde son original atteignable — même règle que le compte
                  compacté de l'en-tête : une valeur abrégée sans son original ne se vérifie plus. */}
              {name !== column.name && <title>{column.name}</title>}
              {marker ? `${marker} ` : ''}{name}
            </text>
            <text x={x + NODE_W - 10} y={rowY} textAnchor="end" fontSize={9}
              fontFamily="JetBrains Mono, monospace" fill="#79839a">
              {column.type}
            </text>
          </g>
        );
      })}
      {hidden > 0 && (
        <text x={x + 10} y={y + HEADER_H + (columns.length + 1) * ROW_H - 6}
          fontSize={9} fontFamily="Inter, sans-serif" fill="#79839a" fontStyle="italic">
          +{hidden} more column{hidden > 1 ? 's' : ''}
        </text>
      )}
    </g>
  );
};

// ── Page ──────────────────────────────────────────────────────────────────────

const DataModel: React.FC = () => {
  const { topics: catalogTopics } = useCatalog();
  const { toast } = useToast();
  /**
   * Sous le seuil desktop, les deux panneaux latéraux deviennent des tiroirs superposés. Fixes,
   * ils coûtaient 576 px de chrome sur une largeur qui n'en a pas 1024 : le canevas — la seule
   * chose que cette page existe pour montrer — tombait à quelques dizaines de pixels, exactement
   * le défaut que `MOBILE-LAYOUT-SCOPE.md` documente pour l'éditeur SQL.
   */
  const isDesktop = useIsDesktop();
  const [topicsDrawerOpen, setTopicsDrawerOpen] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();

  /**
   * La sélection s'initialise depuis l'URL — un modèle se partage tel quel. À défaut d'URL qui
   * en décrive une, la sélection non lancée de la visite précédente reprend sa place ; l'URL
   * l'emporte toujours, sinon un lien partagé écraserait le formulaire de son destinataire.
   */
  // Lu une fois, au premier rendu : la sélection et le budget en sortent tous les deux, et un
  // état paresseux (plutôt qu'une ref) est ce que les deux `useState` suivants peuvent lire.
  const [initialDraft] = useState(
    () => (topicsFromQuery(location.search).length > 0 ? null : readSelectionDraft(DEFAULT_MAX_TOPICS)));
  const [selection, setSelection] = useState<string[]>(() => initialDraft?.topics ?? []);
  /**
   * Combien de topics cette génération analyse. C'était une constante de 30 recopiée du serveur ;
   * c'est maintenant un choix, borné par le plafond que le serveur annonce.
   */
  const [maxTopics, setMaxTopics] = useState(
    () => maxTopicsFromQuery(location.search)
      ?? initialDraft?.maxTopics
      ?? DEFAULT_MAX_TOPICS);
  /** Les bornes du serveur. `null` tant qu'elles ne sont pas connues — on n'en invente pas. */
  const [limits, setLimits] = useState<DataModelLimits | null>(null);
  /**
   * Les bornes ont-elles fini d'arriver — obtenues **ou** échouées ? Une URL partagée se rejoue
   * au montage, et elle partait avant la réponse de `/limits` : la génération se dimensionnait
   * donc toujours sur le plancher d'attente, précisément dans le cas — un grand modèle reçu par
   * lien — où l'attente dérivée est ce qui manque. Un GET est infiniment moins cher que la
   * génération qu'il précède ; un échec fait passer quand même, sur le plancher.
   */
  const [limitsSettled, setLimitsSettled] = useState(false);
  const [filter, setFilter] = useState('');
  /** Saisie libre : un nom, une liste collée, ou un motif `demo.orders.*` — comme Stream Flow. */
  const [topicDraft, setTopicDraft] = useState('');
  const [model, setModel] = useState<DataModelResponse | null>(null);
  const [ranTopics, setRanTopics] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<QueryErrorInfo | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [transform, setTransform] = useState({ x: 0, y: 0, scale: 1 });
  /** Recherche d'un champ à travers les entités — « qui d'autre transporte cette clé ? ». */
  const [columnQuery, setColumnQuery] = useState('');
  /** Les entités sans relation encombrent le diagramme ; repliées par défaut, jamais cachées. */
  const [showUnrelated, setShowUnrelated] = useState(false);
  const [unrelatedOpen, setUnrelatedOpen] = useState(false);
  /** Infobulle d'arête : la preuve de la déduction, au survol comme au focus clavier. */
  const [edgeTip, setEdgeTip] = useState<{ relation: DataModelRelation; x: number; y: number } | null>(null);
  /** Recherche d'une entité pour la centrer — retrouver une table par son nom dans un grand modèle. */
  const [jumpQuery, setJumpQuery] = useState('');
  /**
   * Grades de confiance dessinés. Une commande de lecture : elle masque des traits, elle ne
   * change ni la réponse du serveur, ni la disposition, ni ce que l'inspecteur montre.
   */
  const [shownConfidences, setShownConfidences] = useState<Set<RelationConfidence>>(
    () => new Set<RelationConfidence>(['HIGH', 'MEDIUM', 'LOW']));
  /** Sélections nommées, gardées par le navigateur — l'URL est partageable mais anonyme. */
  const [savedModels, setSavedModels] = useState<SavedModel[]>(() => readSavedModels());
  const [saveName, setSaveName] = useState('');
  /** Les entités à joindre en une requête — un sous-graphe, pas une relation isolée. */
  const [joinSet, setJoinSet] = useState<string[]>([]);
  /** Taille réelle du canevas, pour la minicarte et le cadrage. */
  const [viewportSize, setViewportSize] = useState({ width: 0, height: 0 });
  /** Depuis quand la génération courante tourne — la seule mesure honnête pendant l'attente. */
  const [buildElapsedMs, setBuildElapsedMs] = useState(0);
  /** Comparaison avec une seconde sélection : pas d'historique côté serveur, donc deux appels
      indépendants et un diff calculé côté client — voir `diffModels`. */
  const [compareOpen, setCompareOpen] = useState(false);
  const [compareSelection, setCompareSelection] = useState<string[]>([]);
  const [compareDraft, setCompareDraft] = useState('');
  const [compareModel, setCompareModel] = useState<DataModelResponse | null>(null);
  const [comparing, setComparing] = useState(false);
  const [compareError, setCompareError] = useState<QueryErrorInfo | null>(null);
  const compareAbortRef = useRef<AbortController | null>(null);

  const svgRef = useRef<SVGSVGElement>(null);
  /** Le groupe qui porte le pan/zoom : l'export sérialise ses enfants, pas sa transformation. */
  const graphGroupRef = useRef<SVGGElement>(null);
  /** Le bandeau (couverture + avertissements) : mesuré pour que le cadrage lui réserve la place. */
  const bannerRef = useRef<HTMLDivElement>(null);
  const isPanning = useRef(false);
  const lastPos = useRef({ x: 0, y: 0 });
  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  /** L'URL que la page vient d'écrire elle-même — la relire ne doit pas relancer le modèle. */
  const selfWrittenSearch = useRef<string | null>(null);
  /** Vrai entre une génération réussie et le cadrage du graphe fraîchement monté. */
  const pendingFit = useRef(false);

  /**
   * Ajoute la saisie à la sélection. Elle peut valoir plusieurs topics — une liste collée depuis
   * un runbook, ou un motif étendu sur le catalogue déjà chargé. Ce qui n'est pas entré est dit :
   * un motif sans correspondance et un plafond atteint sont deux façons de repartir avec moins
   * que ce qu'on a demandé.
   */
  const addTopics = useCallback(() => {
    const entry = addTopicEntries(selection, topicDraft, catalogTopics, maxTopics);
    setSelection(entry.selection);
    setTopicDraft('');
    const note = describeTopicEntry(entry);
    if (note) {
      toast(note, entry.unmatched.length > 0 || entry.overflow.length > 0 ? 'error' : 'success');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast est stable
  }, [selection, topicDraft, catalogTopics, maxTopics]);

  const generate = useCallback(async (topics: string[], budget: number = maxTopics) => {
    if (topics.length === 0) return;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const seq = ++requestSeq.current;

    setLoading(true);
    setBuildElapsedMs(0);
    setError(null);
    try {
      // L'attente est dérivée de ce que le serveur dit de son propre pire cas, jamais d'une
      // constante recopiée : deux minutes fixes suffisaient à 30 topics et plus à 100.
      const res = await axios.post<DataModelResponse>('/api/data-model',
        { topics, maxTopics: budget },
        { signal: controller.signal, timeout: requestTimeoutMs(topics.length, limits) });
      if (seq !== requestSeq.current) return;
      setModel(res.data);
      setRanTopics(topics);
      setSelectedId(null);
      // Le tiroir a rempli tout l'écran pour choisir ; le résultat est derrière lui.
      setTopicsDrawerOpen(false);
      // Cadré au viewport une fois le nouveau graphe rendu — un reset vers scale(1) fixe
      // laissait un grand modèle déborder hors écran.
      pendingFit.current = true;
      const search = buildQuery(topics, budget);
      selfWrittenSearch.current = search;
      navigate({ search }, { replace: true });
    } catch (err) {
      if (axios.isCancel(err) || seq !== requestSeq.current) return;
      setError(describeApiError(err, 'Failed to build the data model'));
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [navigate, maxTopics, limits]);

  /**
   * Ajoute la saisie à la sélection *de comparaison* — même geste que `addTopics`, sur son
   * propre état : les deux sélections doivent pouvoir diverger librement.
   */
  const addCompareTopics = useCallback(() => {
    const entry = addTopicEntries(compareSelection, compareDraft, catalogTopics, maxTopics);
    setCompareSelection(entry.selection);
    setCompareDraft('');
    const note = describeTopicEntry(entry);
    if (note) {
      toast(note, entry.unmatched.length > 0 || entry.overflow.length > 0 ? 'error' : 'success');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast est stable
  }, [compareSelection, compareDraft, catalogTopics, maxTopics]);

  /**
   * Le second modèle de la comparaison. Indépendant du modèle affiché : ni son état de
   * chargement, ni son erreur, ni son résultat ne touchent l'URL ou le graphe principal — la
   * comparaison est une question posée à côté, pas une deuxième génération.
   */
  const runCompare = useCallback(async () => {
    if (compareSelection.length === 0) return;
    compareAbortRef.current?.abort();
    const controller = new AbortController();
    compareAbortRef.current = controller;
    setComparing(true);
    setCompareError(null);
    try {
      const res = await axios.post<DataModelResponse>('/api/data-model',
        { topics: compareSelection, maxTopics },
        { signal: controller.signal, timeout: requestTimeoutMs(compareSelection.length, limits) });
      setCompareModel(res.data);
    } catch (err) {
      if (axios.isCancel(err)) return;
      setCompareError(describeApiError(err, 'Failed to build the comparison model'));
    } finally {
      setComparing(false);
    }
  }, [compareSelection, maxTopics, limits]);

  useEffect(() => () => compareAbortRef.current?.abort(), []);

  const diff = useMemo(
    () => (model && compareModel ? diffModels(model, compareModel) : null),
    [model, compareModel]);

  // Une URL portant `?topics=` se rejoue à l'ouverture — c'est ce qui rend un modèle partageable.
  // `capTopics` s'applique ici : le plafond ne passait que par la saisie manuelle, donc une URL
  // composée avant qu'il ne baisse — ou collée à la main — l'ignorait entièrement.
  useEffect(() => {
    if (location.search === selfWrittenSearch.current) return;
    // Attendre les bornes : elles décident combien de temps cette génération sera attendue.
    if (!limitsSettled) return;
    const fromUrl = topicsFromQuery(location.search);
    if (fromUrl.length === 0) return;
    // Le budget voyage avec la sélection : un lien décrivant une génération de cinquante topics
    // s'ouvrait sinon à 30, en perdait vingt, et annonçait qu'ils étaient « laissés de côté »
    // d'un modèle qui avait pourtant été construit sur les cinquante.
    const budget = maxTopicsFromQuery(location.search) ?? maxTopics;
    const { kept, overflow } = capTopics(fromUrl, budget);
    // eslint-disable-next-line react-hooks/set-state-in-effect -- rejeu d'une URL partagée
    setSelection(kept);
    setMaxTopics(budget);
    if (overflow.length > 0) {
      toast(`${overflow.length} left out — this run is capped at ${budget} topics`, 'error');
    }
    generate(kept, budget);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- au montage et sur navigation seulement
  }, [location.search, limitsSettled]);

  /**
   * Les bornes viennent du serveur, elles ne sont pas recopiées ici : c'est précisément le miroir
   * qui divergeait le jour où `explorer.data-model-max-topics` bougeait. Un échec laisse le champ
   * au défaut plutôt que d'inventer un plafond plus haut — une génération refusée après plusieurs
   * minutes d'inférence est le pire des deux résultats.
   */
  useEffect(() => {
    let alive = true;
    axios.get<DataModelLimits>('/api/data-model/limits')
      .then(res => {
        if (!alive) return;
        setLimits(res.data);
        setMaxTopics(current => clampMaxTopics(current, res.data));
      })
      .catch(() => { /* le défaut tient lieu de réponse */ })
      .finally(() => { if (alive) setLimitsSettled(true); });
    return () => { alive = false; };
  }, []);

  // Le brouillon suit la sélection, et s'efface de lui-même quand elle revient à vide.
  useEffect(() => { saveSelectionDraft(selection, maxTopics); }, [selection, maxTopics]);

  /**
   * Le temps écoulé, pendant la génération seulement. Une seconde de granularité : c'est une
   * attente de plusieurs minutes sur une grande sélection, et un compteur plus fin n'apprendrait
   * rien de plus tout en faisant rendre la page dix fois plus souvent.
   */
  useEffect(() => {
    if (!loading) return;
    const startedAt = Date.now();
    const id = setInterval(() => setBuildElapsedMs(Date.now() - startedAt), 1000);
    return () => clearInterval(id);
  }, [loading]);

  useEffect(() => () => abortRef.current?.abort(), []);

  // ── Dérivés ─────────────────────────────────────────────────────────────────

  const visibleTopics = useMemo(
    () => filterTopics(catalogTopics, filter),
    [catalogTopics, filter]);
  /**
   * Un motif ancré qui ne montre rien alors qu'une forme élargie montrerait quelque chose. Une
   * liste vide se lit comme « aucun topic » ; c'est ici « motif ancré », et les deux appellent des
   * gestes différents.
   */
  const filterHint = useMemo(
    () => (visibleTopics.length === 0 ? suggestBroaderPattern(filter.trim(), catalogTopics) : null),
    [visibleTopics.length, filter, catalogTopics]);

  const entities = useMemo(() => model?.entities ?? [], [model]);
  const relations = useMemo(() => model?.relations ?? [], [model]);
  /** Toutes les entités restent inspectables ; seul le graphe se restreint. */
  const entityById = useMemo(() => new Map(entities.map(e => [e.id, e])), [entities]);
  const { connected, isolated } = useMemo(
    () => splitByConnectivity(entities, relations), [entities, relations]);
  /** Ce que le graphe dessine : sans les entités isolées, elles diluent le signal — les relations. */
  const graphEntities = useMemo(
    () => (showUnrelated || isolated.length === entities.length ? entities : connected),
    [showUnrelated, entities, connected, isolated.length]);
  const positions = useMemo(
    () => computeLayout(graphEntities, relations), [graphEntities, relations]);
  /**
   * Les arêtes réellement dessinées. La disposition ci-dessus reste calculée sur **toutes** les
   * relations : basculer un grade masque des traits, il ne réarrange pas le diagramme sous les
   * yeux de l'opérateur.
   */
  const visibleRelations = useMemo(
    () => filterRelations(relations, shownConfidences), [relations, shownConfidences]);
  const filterNote = useMemo(
    () => describeRelationFilter(relations.length, visibleRelations.length),
    [relations.length, visibleRelations.length]);
  /** Géométrie des arêtes : ancrées sur les lignes de colonnes, écartées quand elles se partagent une ancre. */
  const edgeGeometry = useMemo(
    () => computeEdgeGeometry(visibleRelations, graphEntities, positions),
    [visibleRelations, graphEntities, positions]);
  /**
   * Centre le viewport sur une entité choisie dans la recherche — sans passer par un cadrage
   * de tout le graphe, qui dézoomerait sur trente tables pour n'en montrer qu'une. Restreinte
   * aux entités *dessinées* : une entité repliée dans « No deduced relation » n'a pas de
   * position à centrer sur, et la liste des sans-relation existe déjà pour la sélectionner.
   */
  const jumpToEntity = useCallback((id: string) => {
    const entity = entityById.get(id);
    const pos = positions[id];
    const rect = svgRef.current?.getBoundingClientRect();
    if (!entity || !pos || !rect) return;
    setSelectedId(id);
    setTransform(t => centerOnEntity(entity, pos, rect.width, rect.height, t.scale));
    setJumpQuery('');
  }, [entityById, positions]);

  /**
   * La minicarte : bornes du graphe, disposition dans sa boîte, et rectangle de ce qui est
   * visible. Elle n'apparaît que lorsque le graphe **déborde** — tout à l'écran, elle ne
   * montrerait qu'un cadre couvrant sa propre boîte, un ornement qui n'apprend rien.
   */
  const bounds = useMemo(
    () => graphBounds(graphEntities, positions), [graphEntities, positions]);
  const minimap = useMemo(() => {
    if (!bounds || viewportSize.width === 0) return null;
    if (graphFullyVisible(bounds, transform, viewportSize.width, viewportSize.height)) return null;
    return {
      layout: minimapLayout(bounds, MINIMAP_W, MINIMAP_H),
      visible: visibleGraphRect(transform, viewportSize.width, viewportSize.height),
      bounds,
    };
  }, [bounds, transform, viewportSize]);

  /** Le sous-graphe à joindre, restreint aux entités encore présentes dans le modèle. */
  const joinEntities = useMemo(
    () => joinSet.filter(id => entityById.has(id)), [joinSet, entityById]);
  const multiJoin = useMemo(
    () => (joinEntities.length >= 2
      ? buildMultiJoinSql(joinEntities, entities, relations)
      : null),
    [joinEntities, entities, relations]);

  const columnMatches = useMemo(
    () => matchingColumns(entities, columnQuery), [entities, columnQuery]);
  const columnMatchNote = useMemo(
    () => describeColumnMatches(columnMatches, columnQuery), [columnMatches, columnQuery]);
  const domains = useMemo(() => topicDomains(entities.map(e => e.topic)), [entities]);
  const tints = useMemo(() => domainColors(domains), [domains]);
  const tintOf = useCallback((topic: string) =>
    tints.get(domains.get(topic) ?? '') ?? { header: '#1d2333', accent: '#a3adff' },
    [tints, domains]);
  const domainLegend = useMemo(
    () => [...tints.entries()].filter(() => tints.size > 1),
    [tints]);

  /**
   * Le voisinage à mettre en avant. `null` quand la sélection n'est pas *dans* le graphe — une
   * entité choisie dans la liste des sans-relation n'a aucun voisin à souligner, et estomper
   * tout le diagramme pour l'annoncer serait pire que ne rien faire.
   */
  const neighborIds = useMemo<Set<string> | null>(() => {
    if (!selectedId || !positions[selectedId]) return null;
    const ids = new Set<string>([selectedId]);
    // Sur les relations *visibles* : la mise en avant décrit ce qui est dessiné, et un voisin
    // éclairé sans trait qui le relie ne s'explique pas.
    visibleRelations.forEach(r => {
      if (r.from === selectedId || r.to === selectedId) { ids.add(r.from); ids.add(r.to); }
    });
    return ids;
  }, [selectedId, visibleRelations, positions]);

  /** L'inspecteur montre **toutes** les relations de l'entité : c'est le panneau de preuve, pas
      le dessin. Celles que le filtre masque sont marquées plutôt qu'omises. */
  const selectedRelations = useMemo(
    () => (selectedId ? relations.filter(r => r.from === selectedId || r.to === selectedId) : []),
    [selectedId, relations]);

  /**
   * Les colonnes qui se lisent comme une clé sans porter de relation, par entité. Calculé une
   * fois pour tout le modèle : la résolution consulte toutes les entités, donc la faire par nœud
   * au rendu serait quadratique à chaque frame de pan.
   */
  const orphansByEntity = useMemo(() => {
    const map = new Map<string, OrphanKey[]>();
    for (const entity of entities) {
      const orphans = orphanKeyColumns(entity);
      if (orphans.length > 0) map.set(entity.id, orphans);
    }
    return map;
  }, [entities]);

  const selectedOrphans = useMemo(
    () => (selectedId ? orphansByEntity.get(selectedId) ?? [] : []),
    [selectedId, orphansByEntity]);

  /** La même chose en ensembles de noms : ce dont un nœud a besoin pour marquer ses lignes. */
  const orphanKeySets = useMemo(() => {
    const map = new Map<string, Set<string>>();
    orphansByEntity.forEach((orphans, id) => map.set(id, new Set(orphans.map(o => o.column))));
    return map;
  }, [orphansByEntity]);

  /** Le formulaire a-t-il bougé depuis le modèle affiché ? Un graphe périmé doit le dire. */
  const stale = model !== null && (
    selection.length !== ranTopics.length || selection.some(t => !ranTopics.includes(t)));

  /**
   * La taille du canevas, suivie plutôt que lue à la demande : la minicarte doit savoir ce qui
   * est visible à chaque rendu, et `getBoundingClientRect` dans un `useMemo` ne se réévalue pas
   * quand la fenêtre change de taille.
   */
  useEffect(() => {
    const element = svgRef.current;
    if (!element) return;
    const measure = () => {
      const rect = element.getBoundingClientRect();
      setViewportSize(prev => (prev.width === rect.width && prev.height === rect.height
        ? prev : { width: rect.width, height: rect.height }));
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [model, graphEntities.length]);

  // ── Zoom / pan / clavier (mêmes gestes que Lineage et Stream Flow) ──────────

  const zoomAround = useCallback((factor: number, px: number, py: number) => {
    setTransform(t => {
      const scale = Math.max(0.1, Math.min(3, t.scale * factor));
      const k = scale / t.scale;
      return { scale, x: px - (px - t.x) * k, y: py - (py - t.y) * k };
    });
  }, []);

  const zoomFromCenter = useCallback((factor: number) => {
    const rect = svgRef.current?.getBoundingClientRect();
    zoomAround(factor, (rect?.width ?? 0) / 2, (rect?.height ?? 0) / 2);
  }, [zoomAround]);

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      zoomAround(e.deltaY > 0 ? 0.9 : 1.1, e.clientX - rect.left, e.clientY - rect.top);
    };
    el.addEventListener('wheel', handler, { passive: false });
    return () => el.removeEventListener('wheel', handler);
  }, [zoomAround, model]);

  const onPointerDown = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    if ((e.target as Element).closest('[data-node]')) return;
    isPanning.current = true;
    lastPos.current = { x: e.clientX, y: e.clientY };
    e.currentTarget.style.cursor = 'grabbing';
  }, []);
  const onPointerMove = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    if (!isPanning.current) return;
    const dx = e.clientX - lastPos.current.x;
    const dy = e.clientY - lastPos.current.y;
    lastPos.current = { x: e.clientX, y: e.clientY };
    setTransform(t => ({ ...t, x: t.x + dx, y: t.y + dy }));
    setEdgeTip(null);
  }, []);
  const onPointerUp = useCallback((e: React.PointerEvent<SVGSVGElement>) => {
    isPanning.current = false;
    e.currentTarget.style.cursor = 'grab';
  }, []);

  /**
   * Cadre le graphe entier dans le viewport — le geste de reset, et celui d'après-génération.
   *
   * Le bandeau (couverture + avertissements) se dessine par-dessus le SVG sans réduire sa
   * taille : sans réserve, un graphe contraint par sa hauteur — un hub très référencé, une forme
   * ordinaire — se centre à quelques pixels du haut du viewport, pile sous le bandeau, et un
   * avertissement présent rend cette situation courante plutôt qu'exceptionnelle. Sa hauteur
   * réelle est mesurée plutôt que supposée : elle varie avec le nombre d'avertissements, leur
   * longueur (un texte qui passe sur deux lignes), et la présence du bouton de tiroir mobile.
   */
  const fitToViewport = useCallback(() => {
    const rect = svgRef.current?.getBoundingClientRect();
    const bounds = graphBounds(graphEntities, positions);
    if (!rect || !bounds || rect.width === 0) return;
    const bannerRect = bannerRef.current?.getBoundingClientRect();
    const topPadding = bannerRect ? Math.max(40, bannerRect.bottom - rect.top + 16) : 40;
    setTransform(fitTransform(bounds, rect.width, rect.height, 40, topPadding));
  }, [graphEntities, positions]);

  // Après une génération, le SVG du nouveau modèle est monté à ce moment-là seulement.
  useEffect(() => {
    if (!pendingFit.current) return;
    pendingFit.current = false;
    fitToViewport();
  }, [fitToViewport]);

  // Franchir le seuil change la largeur du canevas de plusieurs centaines de pixels : le cadrage
  // d'avant décrit un viewport qui n'existe plus.
  useEffect(() => {
    pendingFit.current = true;
  }, [isDesktop]);

  // ── Export ──────────────────────────────────────────────────────────────────

  const [exporting, setExporting] = useState(false);

  /**
   * Le diagramme en fichier autonome. Le balisage vient du DOM réellement rendu — un second
   * moteur de rendu finirait par diverger de l'écran — et l'enrobage y ajoute ce qu'une image
   * détachée doit porter : la couverture et les légendes.
   */
  const exportDiagram = useCallback(async (format: 'svg' | 'png' | 'mermaid') => {
    if (!model) return;
    // La sélection estompe le reste du diagramme : exportée, elle donnerait une image aux
    // trois quarts effacée. `flushSync` pour que le DOM soit à jour avant la sérialisation.
    if (selectedId !== null) flushSync(() => setSelectedId(null));

    const group = graphGroupRef.current;
    const bounds = graphBounds(graphEntities, positions);
    if (!group || !bounds) return;

    const serializer = new XMLSerializer();
    const inner = Array.from(group.childNodes)
      .map(node => serializer.serializeToString(node))
      .join('');

    const caption = {
      title: 'Kafka data model',
      coverage: describeModel(model),
      notes: exportNotes(model, entities.length - graphEntities.length,
        relations.length - visibleRelations.length),
    };

    const svg = buildExportSvg(
      inner,
      bounds,
      caption,
      // Légende restreinte aux grades réellement dessinés : une ligne décrivant un style de
      // trait absent de l'image apprend une convention que l'image ne montre pas.
      visibleRelations.length > 0
        ? (Object.entries(CONFIDENCE_STYLE) as [keyof typeof CONFIDENCE_STYLE, typeof CONFIDENCE_STYLE.HIGH][])
            .filter(([grade]) => shownConfidences.has(grade))
            .map(([grade, style]) => ({
              color: style.color, dash: style.dash, label: `${grade.toLowerCase()} — ${style.label}`,
            }))
        : [],
      domainLegend.map(([domain, tint]) => ({ color: tint.accent, label: domain })),
    );

    const save = (blob: Blob, extension: string) => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `data-model.${extension}`;
      link.click();
      URL.revokeObjectURL(url);
    };

    if (format === 'svg') {
      save(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), 'svg');
      return;
    }

    if (format === 'mermaid') {
      // La seule forme qui se relit et se différencie : un PNG dans une revue ne dit pas ce
      // qui a changé depuis la version d'avant.
      // Le SVG sérialise le DOM, donc il est déjà filtré ; Mermaid réémet depuis la réponse, et
      // deux exports du même écran qui ne disent pas la même chose seraient pires que le filtre.
      save(new Blob([toMermaidEr({ ...model, relations: visibleRelations }, caption)],
        { type: 'text/plain;charset=utf-8' }), 'mmd');
      return;
    }

    // PNG : c'est le format qui se colle dans un ticket. Rendu à 2× pour rester lisible.
    setExporting(true);
    try {
      const blob = await new Promise<Blob>((resolve, reject) => {
        const source = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }));
        const image = new Image();
        image.onload = () => {
          URL.revokeObjectURL(source);
          const canvas = document.createElement('canvas');
          canvas.width = image.width * 2;
          canvas.height = image.height * 2;
          const context = canvas.getContext('2d');
          if (!context) { reject(new Error('This browser refused a 2D canvas.')); return; }
          context.scale(2, 2);
          context.drawImage(image, 0, 0);
          canvas.toBlob(result => {
            if (result) resolve(result);
            else reject(new Error('The browser produced no image.'));
          }, 'image/png');
        };
        image.onerror = () => {
          URL.revokeObjectURL(source);
          reject(new Error('The diagram could not be rasterised.'));
        };
        image.src = source;
      });
      save(blob, 'png');
    } catch (err) {
      // Un échec de rasterisation doit dire quoi faire, pas disparaître : le SVG, lui, marche.
      setError({
        title: 'PNG export failed',
        hint: 'Export as SVG instead — it carries the same diagram and opens in any browser.',
        raw: err instanceof Error ? err.message : String(err),
      });
    } finally {
      setExporting(false);
    }
  }, [model, selectedId, graphEntities, positions, entities.length, relations.length,
      visibleRelations, shownConfidences, domainLegend]);

  const onGraphKeyDown = useCallback((e: React.KeyboardEvent<SVGSVGElement>) => {
    const step = e.shiftKey ? 160 : 48;
    switch (e.key) {
      case 'ArrowLeft':  setTransform(t => ({ ...t, x: t.x + step })); break;
      case 'ArrowRight': setTransform(t => ({ ...t, x: t.x - step })); break;
      case 'ArrowUp':    setTransform(t => ({ ...t, y: t.y + step })); break;
      case 'ArrowDown':  setTransform(t => ({ ...t, y: t.y - step })); break;
      case '+': case '=': zoomFromCenter(1.25); break;
      case '-': case '_': zoomFromCenter(0.8); break;
      case '0': fitToViewport(); break;
      case 'Escape': setSelectedId(null); setEdgeTip(null); break;
      default: return;
    }
    e.preventDefault();
  }, [fitToViewport, zoomFromCenter]);

  // ── Rendu ───────────────────────────────────────────────────────────────────

  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', overflow: 'hidden' }}>

      {/* ── Infobulle d'arête : la preuve de la déduction, au survol comme au focus clavier ──
          Une relation est une affirmation ; sa justification ne doit pas n'être atteignable
          qu'en ouvrant l'inspecteur du nœud. */}
      {edgeTip && (
        <div
          style={{ position: 'fixed', left: edgeTip.x + 14, top: edgeTip.y - 70, zIndex: 100, pointerEvents: 'none' }}
          className="bg-background-dark/95 border border-outline-variant rounded-lg px-3 py-2 shadow-2xl text-xs max-w-[280px]"
          role="tooltip"
        >
          <p className="font-mono text-on-surface break-all leading-snug">
            {edgeTip.relation.from}.{edgeTip.relation.fromColumn}
            {' → '}
            {edgeTip.relation.to}{edgeTip.relation.toColumn ? `.${edgeTip.relation.toColumn}` : ''}
          </p>
          <p className="text-[10px] font-bold uppercase mt-1"
            style={{ color: CONFIDENCE_STYLE[edgeTip.relation.confidence].color }}>
            {edgeTip.relation.confidence} confidence
          </p>
          <p className="text-on-surface-variant text-[11px] mt-1 leading-snug">
            {edgeTip.relation.reason}
          </p>
        </div>
      )}

      {/* Fond du tiroir, sous le seuil seulement : cliquer à côté referme, comme le tiroir de
          navigation du shell. */}
      {!isDesktop && topicsDrawerOpen && (
        <div className="absolute inset-0 bg-black/60 z-30" aria-hidden="true"
          onClick={() => setTopicsDrawerOpen(false)} />
      )}

      {/* ── Panneau de sélection ── */}
      <aside className={isDesktop
        ? 'w-64 border-r border-outline-variant/60 bg-background-dark flex flex-col shrink-0'
        : `absolute inset-y-0 left-0 z-40 w-[min(18rem,85vw)] border-r border-outline-variant/60 bg-background-dark flex flex-col shadow-2xl transition-transform ${topicsDrawerOpen ? 'translate-x-0' : '-translate-x-full'}`}
        aria-hidden={!isDesktop && !topicsDrawerOpen}>
        <div className="p-4 space-y-3 border-b border-outline-variant/60">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest">
              Topics ({selection.length}/{maxTopics})
            </h2>
            {!isDesktop && (
              <button onClick={() => setTopicsDrawerOpen(false)} aria-label="Close the topic selector"
                className="text-on-surface-variant hover:text-on-surface transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">close</span>
              </button>
            )}
          </div>
          {/* Saisie libre, comme Stream Flow : un pipeline se connaît par listes, et cocher
              trente cases une par une n'est pas le geste d'un opérateur qui sait ce qu'il cherche. */}
          <TopicInput
            aria-label="Add topics by name, list or pattern"
            value={topicDraft}
            onChange={setTopicDraft}
            onEnter={addTopics}
            placeholder="Topic, demo.orders.*, or a pasted list…"
          />

          <div className="flex items-center gap-2 bg-surface-container-low border border-outline-variant rounded-md px-2.5 py-1.5 focus-within:border-primary/40 transition-colors">
            <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-base shrink-0">search</span>
            <input
              value={filter}
              onChange={e => setFilter(e.target.value)}
              placeholder="Filter the list below — demo.orders.* works too"
              aria-label="Filter topics"
              className="bg-transparent outline-none text-xs text-on-surface w-full placeholder:text-outline"
            />
          </div>
          {filterHint && (
            <p className="text-[10px] text-warning leading-snug" role="status">
              Nothing matches — patterns are anchored. Try <span className="font-mono">{filterHint}</span>.
            </p>
          )}

          {/* Combien de topics cette génération analyse. C'était 30 en dur des deux côtés ; le
              plafond vit maintenant dans `explorer.data-model-max-topics` et la page le lit,
              plutôt que d'en garder une copie qui se périme. Monter le nombre coûte du temps
              d'inférence (une vingtaine de secondes par topic au pire), d'où le libellé. */}
          <div className="flex items-center gap-2">
            <label htmlFor="dm-max-topics"
              className="text-[11px] text-on-surface-variant whitespace-nowrap">
              Max topics
            </label>
            <NumberInput
              id="dm-max-topics"
              className="w-20"
              value={maxTopics}
              onChange={next => setMaxTopics(clampMaxTopics(next, limits))}
              fallback={limits?.defaultMaxTopics ?? DEFAULT_MAX_TOPICS}
              min={1}
              max={limits?.maxTopics ?? DEFAULT_MAX_TOPICS}
            />
            <span className="text-[10px] text-outline leading-tight">
              up to {limits?.maxTopics ?? DEFAULT_MAX_TOPICS} — each one is read and inferred
            </span>
          </div>

          <div className="flex gap-2">
            <Button variant="ghost" size="sm" className="flex-1"
              onClick={() => setSelection(sel => selectAll(sel, visibleTopics, maxTopics))}
              disabled={visibleTopics.length === 0}>
              Select shown
            </Button>
            <Button variant="ghost" size="sm" className="flex-1"
              onClick={() => setSelection([])} disabled={selection.length === 0}>
              Clear
            </Button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-2">
          {catalogTopics.length === 0 && (
            <p className="text-xs text-outline px-2 py-4">
              No topics known yet — the catalog fills from the dashboard poll within ~30s.
            </p>
          )}
          {visibleTopics.map(topic => {
            const checked = selection.includes(topic);
            const full = !checked && selection.length >= maxTopics;
            return (
              <label key={topic}
                className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs font-mono cursor-pointer transition-colors ${
                  checked ? 'bg-primary/10 text-on-surface' : 'text-on-surface-variant hover:bg-surface-container-high'
                } ${full ? 'opacity-40 cursor-not-allowed' : ''}`}>
                <Checkbox checked={checked} disabled={full}
                  onChange={() => setSelection(sel => toggleTopic(sel, topic))} />
                <span className="truncate" title={topic}>{topic}</span>
              </label>
            );
          })}
        </div>

        <div className="p-4 border-t border-outline-variant/60 space-y-3">
          <Button className="w-full" icon={loading ? undefined : 'schema'} loading={loading}
            onClick={() => generate(selection)}
            disabled={loading || selection.length === 0}>
            {loading ? 'Analyzing topics…' : 'Generate model'}
          </Button>
          {stale && !loading && (
            <p className="text-[10px] text-warning leading-snug" role="status">
              The selection changed since this model was built — regenerate to match it.
            </p>
          )}

          {/* Export : un modèle de données finit dans un wiki ou un ticket. Le fichier
              embarque la couverture et les légendes — détaché de l'application, un diagramme
              qui ne dit pas ce qu'il couvre se lit comme un modèle complet. */}
          {model && graphEntities.length > 0 && (
            <div className="flex gap-2">
              <Button variant="outline" size="sm" className="flex-1"
                onClick={() => exportDiagram('svg')} title="Vector diagram, opens in any browser">
                SVG
              </Button>
              <Button variant="outline" size="sm" className="flex-1"
                loading={exporting}
                onClick={() => exportDiagram('png')} disabled={exporting}
                title="Raster image, for pasting into a ticket">
                PNG
              </Button>
              <Button variant="outline" size="sm" className="flex-1"
                onClick={() => exportDiagram('mermaid')}
                title="Mermaid erDiagram — the text form, which diffs between two runs">
                MMD
              </Button>
            </div>
          )}

          {/* Retrouver une table par son nom : trente cartes sur un canevas ne se parcourent
              pas bien à la souris, et c'est le premier geste devant un grand modèle. Restreint
              aux entités dessinées — voir `jumpToEntity`. */}
          {model && graphEntities.length > 1 && (
            <Combobox
              value={jumpQuery}
              onChange={v => { setJumpQuery(v); if (entityById.has(v)) jumpToEntity(v); }}
              options={graphEntities.map(e => e.id)}
              placeholder="Jump to an entity…"
              aria-label="Jump to an entity"
              onEnter={() => {
                if (entityById.has(jumpQuery)) jumpToEntity(jumpQuery);
              }}
            />
          )}

          {/* Recherche d'un champ à travers les entités : « qui d'autre transporte cette
              clé ? » est la question qu'on se pose devant ce diagramme, et la réponse est déjà
              côté navigateur — aucune requête. */}
          {model && entities.length > 0 && (
            <div className="space-y-1.5">
              <div className="flex items-center gap-2 bg-surface-container-low border border-outline-variant rounded-md px-2.5 py-1.5 focus-within:border-primary/40 transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-base shrink-0">manage_search</span>
                <input
                  value={columnQuery}
                  onChange={e => setColumnQuery(e.target.value)}
                  placeholder="Highlight a field…"
                  aria-label="Highlight a field across entities"
                  className="bg-transparent outline-none text-xs text-on-surface w-full placeholder:text-outline"
                />
                {columnQuery && (
                  <button onClick={() => setColumnQuery('')} aria-label="Clear field highlight"
                    className="text-outline hover:text-on-surface shrink-0">
                    <span aria-hidden="true" className="material-symbols-outlined text-sm">close</span>
                  </button>
                )}
              </div>
              {columnMatchNote && (
                <p className={`text-[10px] leading-snug ${columnMatches.size === 0 ? 'text-outline' : 'text-[#f5c264]'}`}
                  role="status">
                  {columnMatchNote}
                </p>
              )}
            </div>
          )}

          {/* Entités qu'aucune relation ne touche : elles diluent le signal du diagramme, qui
              est justement les relations. Rangées à part — comptées, ouvrables, inspectables —
              plutôt que cachées ou étalées sous le graphe. */}
          {model && isolated.length > 0 && connected.length > 0 && (
            <div className="space-y-1">
              <button
                onClick={() => setUnrelatedOpen(open => !open)}
                aria-expanded={unrelatedOpen}
                className="w-full flex items-center gap-1.5 text-[10px] font-bold text-on-surface-variant uppercase tracking-wider hover:text-on-surface transition-colors"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
                  {unrelatedOpen ? 'expand_more' : 'chevron_right'}
                </span>
                No deduced relation ({isolated.length})
              </button>
              {unrelatedOpen && (
                <div className="space-y-1 pl-1">
                  {isolated.map(entity => (
                    <button
                      key={entity.id}
                      onClick={() => setSelectedId(prev => (prev === entity.id ? null : entity.id))}
                      className={`w-full text-left text-[11px] font-mono truncate px-1.5 py-1 rounded transition-colors ${
                        selectedId === entity.id
                          ? 'bg-primary/15 text-on-surface'
                          : 'text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                      title={entity.topic}
                    >
                      {entity.id}
                    </button>
                  ))}
                  <label className="flex items-center gap-2 text-[10px] text-on-surface-variant pt-1 cursor-pointer">
                    <Checkbox checked={showUnrelated}
                      onChange={() => {
                        // Le graphe change de taille : il faut le recadrer, sinon les nœuds
                        // qui apparaissent le font hors de l'écran.
                        pendingFit.current = true;
                        setShowUnrelated(v => !v);
                      }} />
                    Draw them in the diagram
                  </label>
                </div>
              )}
            </div>
          )}
          {/* Légende des confiances — et le filtre, sur les mêmes lignes. Une arête déduite doit
              dire sur quoi elle repose ; un modèle riche en concordances de noms seules noie les
              arêtes que la clé de la cible étaye vraiment, d'où la case à cocher par grade. Une
              seule liste : deux listes des trois mêmes grades, l'une légende et l'autre filtre,
              se contrediraient à la première divergence. */}
          {model && relations.length > 0 && (
            <div className="space-y-1">
              <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">
                Relation confidence
              </p>
              {(Object.entries(CONFIDENCE_STYLE) as [keyof typeof CONFIDENCE_STYLE, typeof CONFIDENCE_STYLE.HIGH][]).map(([grade, style]) => {
                const count = relations.filter(r => r.confidence === grade).length;
                return (
                  <label key={grade}
                    className={`flex items-center gap-2 text-[10px] cursor-pointer transition-colors ${
                      shownConfidences.has(grade) ? 'text-on-surface-variant' : 'text-outline'
                    }`}>
                    <Checkbox
                      checked={shownConfidences.has(grade)}
                      aria-label={`Draw ${grade.toLowerCase()}-confidence relations`}
                      onChange={() => setShownConfidences(prev => {
                        const next = new Set(prev);
                        if (!next.delete(grade)) next.add(grade);
                        return next;
                      })} />
                    <svg width="24" height="6" aria-hidden="true" className="shrink-0">
                      <line x1="0" y1="3" x2="24" y2="3" stroke={style.color} strokeWidth="2"
                        strokeDasharray={style.dash}
                        strokeOpacity={shownConfidences.has(grade) ? 1 : 0.3} />
                    </svg>
                    <span>{grade.toLowerCase()} — {style.label}</span>
                    <span className="ml-auto shrink-0 tabular-nums text-outline">{count}</span>
                  </label>
                );
              })}
              {/* Ce que le filtre retire, dit une fois — et seulement quand il retire. */}
              {filterNote && (
                <p className="text-[10px] text-warning leading-snug pt-0.5" role="status">
                  {filterNote}
                </p>
              )}
              <p className="text-[10px] text-outline leading-snug pt-0.5">
                Crow's foot marks the referencing (many) side, the bar the referenced (one) side.
              </p>
            </div>
          )}
          {/* Légende des domaines : les en-têtes sont teintés par famille de topics. */}
          {model && domainLegend.length > 0 && (
            <div className="space-y-1">
              <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">Topic domains</p>
              {domainLegend.map(([domain, tint]) => (
                <div key={domain} className="flex items-center gap-2 text-[10px] text-on-surface-variant">
                  <span aria-hidden="true" className="w-3 h-3 rounded-sm shrink-0 border"
                    style={{ backgroundColor: tint.header, borderColor: tint.accent }} />
                  <span className="font-mono truncate">{domain}</span>
                </div>
              ))}
            </div>
          )}

          {/* Le sous-graphe à joindre. Il refuse plutôt que d'inventer un prédicat : un ensemble
              que les relations déduites ne relient pas n'a pas de jointure, et `problem` le dit
              au lieu de rendre un bouton grisé et muet. */}
          {model && joinEntities.length > 0 && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">
                  Join ({joinEntities.length})
                </p>
                <button onClick={() => setJoinSet([])} aria-label="Clear the join selection"
                  className="text-outline hover:text-on-surface">
                  <span aria-hidden="true" className="material-symbols-outlined text-sm">close</span>
                </button>
              </div>
              <div className="flex flex-wrap gap-1">
                {joinEntities.map(id => (
                  <span key={id}
                    className="inline-flex items-center gap-1 bg-surface-container-high rounded-full pl-2 pr-1 py-0.5 text-[10px] font-mono text-on-surface-variant max-w-full">
                    <span className="truncate" title={id}>{id}</span>
                    <button onClick={() => setJoinSet(set => set.filter(x => x !== id))}
                      aria-label={`Remove ${id} from the join`} className="hover:text-on-surface shrink-0">
                      <span aria-hidden="true" className="material-symbols-outlined text-[12px]">close</span>
                    </button>
                  </span>
                ))}
              </div>
              {multiJoin?.problem && (
                <p className="text-[10px] text-warning leading-snug">{multiJoin.problem}</p>
              )}
              {multiJoin?.sql && (
                <Link
                  to={`/query?sql=${encodeURIComponent(multiJoin.sql)}`}
                  className="inline-flex items-center gap-1 text-[11px] text-primary hover:underline"
                  title={multiJoin.caveats.length > 0
                    ? `Opens in the SQL editor. ${multiJoin.caveats.join(' ')}`
                    : 'Opens the join this subgraph describes in the SQL editor.'}
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[14px]">terminal</span>
                  Open {joinEntities.length}-table join as SQL
                  {multiJoin.caveats.length > 0 && (
                    <span className="text-[9px] text-warning">
                      ({multiJoin.caveats.length} caveat{multiJoin.caveats.length > 1 ? 's' : ''})
                    </span>
                  )}
                </Link>
              )}
              {joinEntities.length === 1 && (
                <p className="text-[10px] text-outline leading-snug">
                  Add a second entity — a join needs two tables.
                </p>
              )}
            </div>
          )}

          {/* Sélections nommées : l'URL est partageable mais anonyme, et rien ne dit qu'elle
              décrit *le* modèle de commande. Local à ce navigateur, et la phrase le dit. */}
          <div className="space-y-1.5">
            <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">
              Saved selections
            </p>
            <form
              className="flex gap-1"
              onSubmit={e => {
                e.preventDefault();
                if (saveName.trim() === '' || selection.length === 0) return;
                setSavedModels(saveModel(saveName, selection, maxTopics));
                setSaveName('');
                toast(`Saved “${saveName.trim()}” on this browser`, 'success');
              }}
            >
              <input
                value={saveName}
                onChange={e => setSaveName(e.target.value)}
                placeholder="Name this selection…"
                aria-label="Name this selection"
                className="flex-1 min-w-0 bg-surface-container-low border border-outline-variant rounded-md px-2 py-1 text-xs text-on-surface outline-none focus:border-primary/40 placeholder:text-outline"
              />
              <Button type="submit" variant="ghost" size="sm"
                disabled={saveName.trim() === '' || selection.length === 0}>
                Save
              </Button>
            </form>
            {savedModels.length === 0 ? (
              <p className="text-[10px] text-outline leading-snug">
                None yet. A saved selection lives on this browser only — the URL is what you share.
              </p>
            ) : savedModels.map(saved => (
              <div key={saved.name} className="flex items-center gap-1">
                <button
                  onClick={() => {
                    const budget = saved.maxTopics ?? DEFAULT_MAX_TOPICS;
                    setSelection(saved.topics);
                    setMaxTopics(budget);
                    generate(saved.topics, budget);
                  }}
                  className="flex-1 min-w-0 text-left text-[11px] px-1.5 py-1 rounded text-on-surface-variant hover:bg-surface-container-high transition-colors"
                  title={saved.topics.join(', ')}
                >
                  <span className="truncate block">{saved.name}</span>
                  <span className="text-[9px] text-outline">
                    {saved.topics.length} topic{saved.topics.length > 1 ? 's' : ''}
                  </span>
                </button>
                <button
                  onClick={() => setSavedModels(deleteSavedModel(saved.name))}
                  aria-label={`Delete the saved selection ${saved.name}`}
                  className="text-outline hover:text-error shrink-0"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-sm">delete</span>
                </button>
              </div>
            ))}
          </div>

          {/* Comparaison avec une seconde sélection : un modèle n'a pas d'historique côté
              serveur (contrairement à l'audit), donc deux sélections rejouées en direct et un
              diff calculé côté client — voir `diffModels`. */}
          {model && (
            <div className="space-y-1.5 pt-2 border-t border-outline-variant/40">
              <button
                onClick={() => setCompareOpen(open => !open)}
                aria-expanded={compareOpen}
                className="w-full flex items-center gap-1.5 text-[10px] font-bold text-on-surface-variant uppercase tracking-wider hover:text-on-surface transition-colors"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
                  {compareOpen ? 'expand_more' : 'chevron_right'}
                </span>
                Compare with another selection
              </button>
              {compareOpen && (
                <div className="space-y-2 pl-1">
                  <TopicInput
                    aria-label="Add comparison topics by name, list or pattern"
                    value={compareDraft}
                    onChange={setCompareDraft}
                    onEnter={addCompareTopics}
                    placeholder="Topic, demo.orders.*, or a pasted list…"
                  />
                  {compareSelection.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {compareSelection.map(t => (
                        <span key={t}
                          className="inline-flex items-center gap-1 bg-surface-container-high rounded-full pl-2 pr-1 py-0.5 text-[10px] font-mono text-on-surface-variant max-w-full">
                          <span className="truncate" title={t}>{t}</span>
                          <button onClick={() => setCompareSelection(sel => sel.filter(x => x !== t))}
                            aria-label={`Remove ${t} from the comparison`} className="hover:text-on-surface shrink-0">
                            <span aria-hidden="true" className="material-symbols-outlined text-[12px]">close</span>
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                  <Button variant="outline" size="sm" className="w-full" loading={comparing}
                    onClick={runCompare} disabled={comparing || compareSelection.length === 0}>
                    {comparing ? 'Comparing…' : 'Compare'}
                  </Button>
                  {compareError && (
                    <p className="text-[10px] text-error leading-snug">
                      {compareError.title}{compareError.hint ? ` — ${compareError.hint}` : ''}
                    </p>
                  )}
                  {/* Le diff : ce que la seconde sélection donne en plus, en moins, ou en
                      confiance différente — jamais deux graphes superposés, illisibles au-delà
                      de quelques entités. */}
                  {diff && (
                    <div className="space-y-1.5 bg-surface-container-low rounded-lg p-2">
                      <div className="flex items-start justify-between gap-2">
                        <p className="text-[10px] text-on-surface font-semibold leading-snug">
                          {describeDiff(diff)}
                        </p>
                        <button onClick={() => setCompareModel(null)} aria-label="Clear comparison"
                          className="text-outline hover:text-on-surface shrink-0">
                          <span aria-hidden="true" className="material-symbols-outlined text-sm">close</span>
                        </button>
                      </div>
                      {!diffIsEmpty(diff) && (
                        <div className="space-y-0.5 text-[10px] leading-snug max-h-48 overflow-y-auto custom-scrollbar">
                          {diff.addedEntities.map(e => (
                            <p key={`ae-${e.id}`} className="text-[#7ee2a8] font-mono truncate" title={e.topic}>+ {e.id}</p>
                          ))}
                          {diff.removedEntities.map(e => (
                            <p key={`re-${e.id}`} className="text-error font-mono truncate line-through" title={e.topic}>- {e.id}</p>
                          ))}
                          {diff.addedRelations.map((r, i) => (
                            <p key={`ar-${i}`} className="text-[#7ee2a8] font-mono truncate">
                              + {r.from}.{r.fromColumn} → {r.to}
                            </p>
                          ))}
                          {diff.removedRelations.map((r, i) => (
                            <p key={`rr-${i}`} className="text-error font-mono truncate line-through">
                              - {r.from}.{r.fromColumn} → {r.to}
                            </p>
                          ))}
                          {diff.changedRelations.map((c, i) => (
                            <p key={`cr-${i}`} className="text-[#f5c264] font-mono truncate">
                              ~ {c.relation.from}.{c.relation.fromColumn} → {c.relation.to}
                              {' '}({c.from.toLowerCase()} → {c.to.toLowerCase()})
                            </p>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </aside>

      {/* ── Canevas ── */}
      <main className="flex-1 relative overflow-hidden graph-bg bg-background-dark">

        {/* Bandeau : couverture + avertissements */}
        <div ref={bannerRef} className="absolute top-4 left-4 right-4 z-10 flex flex-col items-start gap-2 pointer-events-none">
          {/* Sous le seuil, c'est la seule porte vers la sélection : elle est donc toujours là,
              y compris sur l'état vide, où c'est précisément le geste suivant. */}
          {!isDesktop && (
            <button
              onClick={() => setTopicsDrawerOpen(true)}
              className="pointer-events-auto flex items-center gap-1.5 bg-surface-container border border-outline-variant px-3 py-1.5 rounded-full text-xs text-on-surface hover:bg-surface-container-high transition-colors"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[16px]">tune</span>
              Topics ({selection.length})
            </button>
          )}
          <div className="flex items-center gap-2 bg-surface-container/90 border border-outline-variant px-3 py-1.5 rounded-full text-xs">
            <span className="text-on-surface-variant">Data Model</span>
            {model && (
              <>
                <span className="text-primary/40">/</span>
                <span className="text-primary font-semibold">{describeModel(model)}</span>
                {/* Une entité absente du diagramme doit se dire ici : le compte ci-dessus est
                    celui du modèle, pas celui de ce qui est dessiné. */}
                {graphEntities.length < entities.length && (
                  <span className="px-1.5 py-0.5 rounded bg-warning/20 text-warning text-[9px] font-bold uppercase">
                    {entities.length - graphEntities.length} not drawn
                  </span>
                )}
              </>
            )}
          </div>
          {(model?.warnings ?? []).map(warning => (
            <div key={warning} role="status"
              className="max-w-lg flex items-start gap-2 bg-warning/10 border border-warning/30 text-warning px-3 py-1.5 rounded-lg text-[11px] leading-relaxed pointer-events-auto">
              <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5 shrink-0">info</span>
              <span>{warning}</span>
            </div>
          ))}
        </div>

        {/* Zoom */}
        {model && graphEntities.length > 0 && (
          <div className="absolute bottom-6 left-4 z-10 flex flex-col bg-surface-container border border-outline-variant rounded-xl overflow-hidden shadow-xl">
            <button onClick={() => zoomFromCenter(1.25)} aria-label="Zoom in"
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
              <span aria-hidden="true" className="material-symbols-outlined text-lg">add</span>
            </button>
            <button onClick={() => zoomFromCenter(0.8)} aria-label="Zoom out"
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface border-b border-outline-variant/60 transition-colors">
              <span aria-hidden="true" className="material-symbols-outlined text-lg">remove</span>
            </button>
            <button onClick={fitToViewport} aria-label="Fit graph to view" title="Fit to view"
              className="p-2 hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors">
              <span aria-hidden="true" className="material-symbols-outlined text-lg">center_focus_weak</span>
            </button>
          </div>
        )}

        {/* Minicarte : uniquement quand le graphe déborde du viewport — voir `graphFullyVisible`.
            Un clic y déplace la vue, ce qui est le seul geste qu'une vue d'ensemble doit offrir :
            elle situe, et permet d'aller là où l'on regarde. */}
        {minimap && (
          <div className="absolute bottom-6 right-4 z-10 bg-surface-container/95 border border-outline-variant rounded-lg shadow-xl overflow-hidden">
            <svg
              width={MINIMAP_W} height={MINIMAP_H}
              role="button" tabIndex={0}
              aria-label="Graph overview — click to move the view"
              className="block cursor-pointer focus:outline-none focus-visible:ring-1 focus-visible:ring-primary/60"
              onClick={e => {
                const box = e.currentTarget.getBoundingClientRect();
                const { layout } = minimap;
                const point = {
                  x: (e.clientX - box.left - layout.offsetX) / layout.scale,
                  y: (e.clientY - box.top - layout.offsetY) / layout.scale,
                };
                setTransform(t => centerOnGraphPoint(
                  point, viewportSize.width, viewportSize.height, t.scale));
              }}
            >
              {graphEntities.map(entity => {
                const pos = positions[entity.id];
                if (!pos) return null;
                const { layout } = minimap;
                return (
                  <rect
                    key={entity.id}
                    x={pos.x * layout.scale + layout.offsetX}
                    y={pos.y * layout.scale + layout.offsetY}
                    width={Math.max(1, NODE_W * layout.scale)}
                    height={Math.max(1, entityHeight(entity) * layout.scale)}
                    rx={1}
                    fill={selectedId === entity.id ? '#a3adff' : tintOf(entity.topic).accent}
                    fillOpacity={selectedId === entity.id ? 0.9 : 0.45}
                  />
                );
              })}
              {/* Ce qui est à l'écran, en clair sur le reste. */}
              <rect
                x={minimap.visible.minX * minimap.layout.scale + minimap.layout.offsetX}
                y={minimap.visible.minY * minimap.layout.scale + minimap.layout.offsetY}
                width={Math.max(2, (minimap.visible.maxX - minimap.visible.minX) * minimap.layout.scale)}
                height={Math.max(2, (minimap.visible.maxY - minimap.visible.minY) * minimap.layout.scale)}
                fill="#ffffff" fillOpacity={0.1}
                stroke="#ffffff" strokeOpacity={0.7} strokeWidth={1}
              />
            </svg>
          </div>
        )}

        {error && (
          <div className="absolute inset-x-0 top-16 z-10 flex justify-center px-6">
            <ErrorPanel error={error} onRetry={() => generate(selection)}
              onDismiss={() => setError(null)} className="max-w-xl w-full" />
          </div>
        )}

        {/* Pendant la construction. Sans ça, le canevas est soit vide pendant plusieurs minutes
            (première génération), soit occupé par le modèle précédent sans rien qui le dise —
            le cas le plus trompeur des deux, puisque rien ne bouge à l'écran.
            Indéterminé assumé : une seule requête, donc aucun pourcentage à afficher. */}
        {loading && (
          <div className="absolute inset-0 z-20 flex items-center justify-center bg-background-dark/70 backdrop-blur-[1px]">
            <div className="flex flex-col items-center gap-3 px-6 text-center" role="status">
              <Spinner />
              <p className="text-sm text-on-surface font-medium tabular-nums">
                {describeBuildProgress(selection.length, buildElapsedMs)}
              </p>
              <p className="max-w-xs text-[11px] text-on-surface-variant leading-snug">
                Each topic is sampled and its schema inferred. The server answers once, so there is
                no per-topic progress to show.
              </p>
              {describeBuildBudget(selection.length, limits) && (
                <p className="max-w-xs text-[11px] text-on-surface-variant leading-snug tabular-nums">
                  {describeBuildBudget(selection.length, limits)}
                </p>
              )}
              {describeStaleGraphDuringBuild(model !== null) && (
                <p className="max-w-xs text-[11px] text-warning leading-snug">
                  {describeStaleGraphDuringBuild(model !== null)}
                </p>
              )}
            </div>
          </div>
        )}

        {!model && !loading && !error && (
          <div className="absolute inset-0 flex items-center justify-center">
            <EmptyState
              icon="schema"
              title="No model yet"
              description="Select topics on the left and generate: each topic becomes a table with its inferred columns, and relations are deduced from key-column names. Every deduced edge states the evidence it rests on."
            />
          </div>
        )}

        {model && entities.length === 0 && !loading && (
          <div className="absolute inset-0 flex items-center justify-center">
            <EmptyState icon="schema" title="Nothing to draw"
              description="None of the selected topics yielded a schema — the warnings above say why, topic by topic." />
          </div>
        )}

        {model && graphEntities.length > 0 && (
          <svg
            ref={svgRef}
            className="w-full h-full select-none focus:outline-none focus-visible:ring-1 focus-visible:ring-primary/60"
            style={{ cursor: 'grab', touchAction: 'none' }}
            role="application"
            tabIndex={0}
            aria-label={`Data model, ${entities.length} entities and ${relations.length} relations. Arrow keys pan, plus and minus zoom, 0 resets.`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerLeave={onPointerUp}
            onKeyDown={onGraphKeyDown}
            onClick={e => { if (e.target === e.currentTarget) setSelectedId(null); }}
          >
            <g ref={graphGroupRef} transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

              {/* Arêtes : ancrées sur la ligne de leur colonne, cardinalité en patte-d'oie —
                  la patte-d'oie côté référent (N), la barre côté référencé (1). Le style de
                  trait reste réservé à la confiance de la déduction. */}
              {visibleRelations.map((relation, i) => {
                const geometry = edgeGeometry[i];
                if (!geometry) return null;

                const hi = selectedId !== null
                  && (relation.from === selectedId || relation.to === selectedId);
                const dim = neighborIds !== null && !hi;
                const style = CONFIDENCE_STYLE[relation.confidence];
                const { x1, y1, x2, y2, d1, d2 } = geometry;
                const mx = (x1 + x2) / 2;
                const opacity = dim ? 0.06 : hi ? 0.95 : 0.55;

                const curve = `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`;
                const showTip = (clientX: number, clientY: number) =>
                  setEdgeTip({ relation, x: clientX, y: clientY });

                return (
                  <g
                    key={i}
                    opacity={opacity}
                    data-node="true"
                    tabIndex={dim ? -1 : 0}
                    role="button"
                    aria-label={describeRelation(relation)}
                    className="focus:outline-none"
                    onMouseEnter={e => { if (!isPanning.current) showTip(e.clientX, e.clientY); }}
                    onMouseLeave={() => setEdgeTip(null)}
                    onFocus={e => {
                      const box = (e.currentTarget as SVGGElement).getBoundingClientRect();
                      showTip(box.left + box.width / 2, box.top);
                    }}
                    onBlur={() => setEdgeTip(null)}
                  >
                    {/* Cible de survol : un trait de 1,5 px ne s'attrape pas à la souris. */}
                    <path d={curve} fill="none" stroke="transparent" strokeWidth={14}
                      pointerEvents={dim ? 'none' : 'stroke'} />
                    <path
                      d={curve}
                      fill="none"
                      stroke={style.color}
                      strokeWidth={hi ? 2.2 : 1.5}
                      strokeDasharray={style.dash}
                      pointerEvents="none"
                    />
                    <path d={crowFootPath(x1, y1, d1)} fill="none" stroke={style.color}
                      strokeWidth={hi ? 1.8 : 1.3} pointerEvents="none" />
                    <path d={oneBarPath(x2, y2, d2)} fill="none" stroke={style.color}
                      strokeWidth={hi ? 1.8 : 1.3} pointerEvents="none" />
                    {!dim && (
                      <text x={mx} y={(y1 + y2) / 2 - 6} textAnchor="middle" fill={style.color}
                        fontSize={9} fontFamily="JetBrains Mono, monospace"
                        opacity={hi ? 1 : 0.7} pointerEvents="none">
                        {relation.fromColumn}
                      </text>
                    )}
                  </g>
                );
              })}

              {/* Nœuds */}
              {graphEntities.map(entity => {
                const pos = positions[entity.id];
                if (!pos) return null;
                return (
                  <EntityNode
                    key={entity.id}
                    entity={entity}
                    x={pos.x} y={pos.y}
                    selected={selectedId === entity.id}
                    dimmed={neighborIds !== null && !neighborIds.has(entity.id)}
                    tint={tintOf(entity.topic)}
                    highlighted={columnMatches.get(entity.id)}
                    orphanKeys={orphanKeySets.get(entity.id)}
                    onClick={() => setSelectedId(prev => (prev === entity.id ? null : entity.id))}
                  />
                );
              })}
            </g>
          </svg>
        )}
      </main>

      {/* ── Inspecteur : l'évidence derrière la sélection ── */}
      {selectedId && entityById.get(selectedId) && (
        <aside className={isDesktop
          ? 'border-l border-outline-variant/60 bg-background-dark flex flex-col shrink-0 overflow-hidden w-80'
          : 'absolute inset-y-0 right-0 z-40 w-[min(20rem,85vw)] border-l border-outline-variant/60 bg-background-dark flex flex-col overflow-hidden shadow-2xl'}>
          <div className="p-4 border-b border-outline-variant/60">
            <div className="flex items-center justify-between mb-2">
              <Badge tone="primary">entity</Badge>
              <button onClick={() => setSelectedId(null)} aria-label="Close details"
                className="text-on-surface-variant hover:text-on-surface transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-lg">close</span>
              </button>
            </div>
            <h2 className="text-base font-bold font-mono text-on-surface break-all">{selectedId}</h2>
            <p className="text-[11px] text-on-surface-variant font-mono mt-1 break-all">
              {entityById.get(selectedId)!.topic}
            </p>
            <Link
              to={`/topic/${encodeURIComponent(entityById.get(selectedId)!.topic)}`}
              className="inline-flex items-center gap-1 text-[11px] text-primary hover:underline mt-2"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[14px]">open_in_new</span>
              Open in Topic Explorer
            </Link>
            {/* Ajouter au sous-graphe à joindre : « ouvre cette relation » existe déjà par
                relation, mais recoller trois tables à la main est le vrai geste sur un schéma
                en étoile. La requête se construit dans le panneau de gauche. */}
            <button
              onClick={() => setJoinSet(set => (set.includes(selectedId)
                ? set.filter(id => id !== selectedId)
                : [...set, selectedId]))}
              className="mt-2 flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
                {joinSet.includes(selectedId) ? 'playlist_remove' : 'playlist_add'}
              </span>
              {joinSet.includes(selectedId) ? 'Remove from the join' : 'Add to the join'}
            </button>
          </div>

          <div className="flex-1 overflow-y-auto custom-scrollbar p-4 space-y-4">
            <section>
              <h3 className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mb-2">
                Columns ({entityById.get(selectedId)!.columns.length})
              </h3>
              <div className="space-y-0.5">
                {entityById.get(selectedId)!.columns.map(column => (
                  <div key={column.name} className="flex items-baseline gap-2 text-[11px] font-mono">
                    <span className={column.primaryKey ? 'text-[#7ee2a8]' : column.references ? 'text-[#f5c264]' : 'text-on-surface'}>
                      {column.primaryKey ? '🔑 ' : column.references ? '→ ' : ''}{column.name}
                    </span>
                    <span className="text-outline ml-auto shrink-0">{column.type}</span>
                  </div>
                ))}
              </div>
              {entityById.get(selectedId)!.primaryKey === null && (
                <p className="text-[10px] text-outline mt-2 leading-snug">
                  No key column was detected — none of the fields reads as an identifier.
                </p>
              )}
              {/* Une colonne qui se lit comme une clé étrangère et dont aucune relation n'est
                  sortie était indiscernable d'une colonne ordinaire : le diagramme paraissait
                  incomplet sans dire pourquoi. Rien n'est deviné sur la cause — seule la moitié
                  vérifiable est affirmée, à savoir si un topic sélectionné porte ce nom. */}
              {selectedOrphans.length > 0 && (
                <div className="mt-3 space-y-1.5 border-t border-outline-variant/40 pt-2">
                  <p className="text-[10px] font-bold text-[#f5c264] uppercase tracking-wider">
                    Key-like, no relation ({selectedOrphans.length})
                  </p>
                  {selectedOrphans.map(orphan => (
                    <div key={orphan.column} className="text-[10px] leading-snug">
                      <span className="font-mono text-on-surface">{orphan.column}</span>
                      <span className="text-outline"> — {describeOrphanKey(orphan)}</span>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section>
              <h3 className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest mb-2">
                Relations ({selectedRelations.length})
              </h3>
              {selectedRelations.length === 0 && (
                <p className="text-xs text-outline">No deduced relation touches this entity.</p>
              )}
              <div className="space-y-2">
                {selectedRelations.map((relation, i) => (
                  <div key={i} className="bg-surface-container-high rounded-lg px-3 py-2 text-[11px] space-y-1">
                    <div className="flex items-center gap-1.5 font-mono text-on-surface">
                      <span className={relation.from === selectedId ? 'text-primary' : ''}>{relation.from}</span>
                      <span aria-hidden="true" className="material-symbols-outlined text-[13px] text-on-surface-variant">arrow_forward</span>
                      <span className={relation.to === selectedId ? 'text-primary' : ''}>{relation.to}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-on-surface-variant">
                        {relation.fromColumn}{relation.toColumn ? ` → ${relation.toColumn}` : ''}
                      </span>
                      <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded ml-auto"
                        style={{ color: CONFIDENCE_STYLE[relation.confidence].color, backgroundColor: `${CONFIDENCE_STYLE[relation.confidence].color}20` }}>
                        {relation.confidence}
                      </span>
                    </div>
                    {/* L'inspecteur est le panneau de preuve : il montre tout, y compris ce que
                        le filtre retire du dessin — mais alors il le dit, sinon la relation
                        listée ici sans trait à l'écran ne s'explique pas. */}
                    {!shownConfidences.has(relation.confidence) && (
                      <p className="text-[10px] text-warning leading-snug">
                        Hidden from the diagram by the confidence filter.
                      </p>
                    )}
                    {/* La preuve, en toutes lettres : une arête déduite sans son évidence est une
                        supposition dessinée comme un fait. */}
                    <p className="text-on-surface-variant leading-snug">{relation.reason}</p>
                    {/* Une relation HIGH *est* un prédicat de jointure, et c'est le seul endroit
                        de l'application où il est déjà connu : le diagramme devient un point de
                        départ pour une requête au lieu d'une image qu'on regarde. */}
                    {(() => {
                      const join = buildJoinSql(relation, entities);
                      if (!join) {
                        return (
                          <p className="text-outline text-[10px] leading-snug">
                            No join query: the relation names no column on {relation.to}, and no key
                            was detected there to fall back on.
                          </p>
                        );
                      }
                      return (
                        <Link
                          to={`/query?sql=${encodeURIComponent(join.sql)}`}
                          className="inline-flex items-center gap-1 text-primary hover:underline"
                          title={join.caveats.length > 0
                            ? `Opens in the SQL editor. ${join.caveats.join(' ')}`
                            : 'Opens the join this relation describes in the SQL editor.'}
                        >
                          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">terminal</span>
                          Open as SQL
                          {join.caveats.length > 0 && (
                            <span className="text-[9px] text-warning">
                              ({join.caveats.length} caveat{join.caveats.length > 1 ? 's' : ''})
                            </span>
                          )}
                        </Link>
                      );
                    })()}
                  </div>
                ))}
              </div>
            </section>
          </div>
        </aside>
      )}
    </div>
  );
};

export default DataModel;
