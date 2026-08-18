// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useCatalog } from '../catalogStore';
import { Button, EmptyState, ErrorPanel, Badge } from '../components/ui';
import { describeApiError } from './queryError';
import type { QueryErrorInfo } from './queryError';
import type { DataModelEntity, DataModelResponse } from '../api/types';
import {
  MAX_TOPICS, NODE_W, HEADER_H, ROW_H,
  filterTopics, toggleTopic, selectAll, topicsFromQuery, buildQuery,
  displayedColumns, entityHeight, computeLayout, computeEdgeGeometry, splitByConnectivity,
  crowFootPath, oneBarPath, graphBounds, fitTransform, topicDomains, domainColors,
  formatCount, describeRelation, matchingColumns, describeColumnMatches,
  CONFIDENCE_STYLE, describeModel,
} from './dataModel';
import type { DataModelRelation } from '../api/types';

/**
 * La page Modèle de données : une sélection de topics → les entités (topics lus comme des
 * tables, colonnes inférées) et les relations déduites, rendues en diagramme entité-relation.
 * Toute la logique décidable vit dans `dataModel.ts` ; ici il n'y a que le rendu et le câblage.
 */

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
  onClick: () => void;
}> = ({ entity, x, y, selected, dimmed, tint, highlighted, onClick }) => {
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
        const marker = column.primaryKey ? '🔑' : column.references ? '→' : '';
        const name = column.name.length > 22 ? column.name.slice(0, 21) + '…' : column.name;
        const lit = highlighted?.has(column.name) ?? false;
        return (
          <g key={column.name}>
            {lit && (
              <rect x={x + 4} y={rowY - ROW_H + 6} width={NODE_W - 8} height={ROW_H} rx={3}
                fill="#f5c264" fillOpacity={0.18} />
            )}
            <text x={x + 10} y={rowY} fontSize={10} fontFamily="JetBrains Mono, monospace"
              fontWeight={lit ? 'bold' : 'normal'}
              fill={lit ? '#f5c264' : column.primaryKey ? '#7ee2a8' : column.references ? '#f5c264' : '#c5cad6'}>
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
  const location = useLocation();
  const navigate = useNavigate();

  const [selection, setSelection] = useState<string[]>([]);
  const [filter, setFilter] = useState('');
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

  const svgRef = useRef<SVGSVGElement>(null);
  const isPanning = useRef(false);
  const lastPos = useRef({ x: 0, y: 0 });
  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  /** L'URL que la page vient d'écrire elle-même — la relire ne doit pas relancer le modèle. */
  const selfWrittenSearch = useRef<string | null>(null);
  /** Vrai entre une génération réussie et le cadrage du graphe fraîchement monté. */
  const pendingFit = useRef(false);

  const generate = useCallback(async (topics: string[]) => {
    if (topics.length === 0) return;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const seq = ++requestSeq.current;

    setLoading(true);
    setError(null);
    try {
      const res = await axios.post<DataModelResponse>('/api/data-model', { topics },
        { signal: controller.signal, timeout: 120_000 });
      if (seq !== requestSeq.current) return;
      setModel(res.data);
      setRanTopics(topics);
      setSelectedId(null);
      // Cadré au viewport une fois le nouveau graphe rendu — un reset vers scale(1) fixe
      // laissait un grand modèle déborder hors écran.
      pendingFit.current = true;
      const search = buildQuery(topics);
      selfWrittenSearch.current = search;
      navigate({ search }, { replace: true });
    } catch (err) {
      if (axios.isCancel(err) || seq !== requestSeq.current) return;
      setError(describeApiError(err, 'Failed to build the data model'));
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [navigate]);

  // Une URL portant `?topics=` se rejoue à l'ouverture — c'est ce qui rend un modèle partageable.
  useEffect(() => {
    if (location.search === selfWrittenSearch.current) return;
    const fromUrl = topicsFromQuery(location.search);
    if (fromUrl.length === 0) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- rejeu d'une URL partagée
    setSelection(fromUrl);
    generate(fromUrl);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- au montage et sur navigation seulement
  }, [location.search]);

  useEffect(() => () => abortRef.current?.abort(), []);

  // ── Dérivés ─────────────────────────────────────────────────────────────────

  const visibleTopics = useMemo(
    () => filterTopics(catalogTopics, filter),
    [catalogTopics, filter]);

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
  /** Géométrie des arêtes : ancrées sur les lignes de colonnes, écartées quand elles se partagent une ancre. */
  const edgeGeometry = useMemo(
    () => computeEdgeGeometry(relations, graphEntities, positions),
    [relations, graphEntities, positions]);
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
    relations.forEach(r => {
      if (r.from === selectedId || r.to === selectedId) { ids.add(r.from); ids.add(r.to); }
    });
    return ids;
  }, [selectedId, relations, positions]);

  const selectedRelations = useMemo(
    () => (selectedId ? relations.filter(r => r.from === selectedId || r.to === selectedId) : []),
    [selectedId, relations]);

  /** Le formulaire a-t-il bougé depuis le modèle affiché ? Un graphe périmé doit le dire. */
  const stale = model !== null && (
    selection.length !== ranTopics.length || selection.some(t => !ranTopics.includes(t)));

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

  /** Cadre le graphe entier dans le viewport — le geste de reset, et celui d'après-génération. */
  const fitToViewport = useCallback(() => {
    const rect = svgRef.current?.getBoundingClientRect();
    const bounds = graphBounds(graphEntities, positions);
    if (!rect || !bounds || rect.width === 0) return;
    setTransform(fitTransform(bounds, rect.width, rect.height));
  }, [graphEntities, positions]);

  // Après une génération, le SVG du nouveau modèle est monté à ce moment-là seulement.
  useEffect(() => {
    if (!pendingFit.current) return;
    pendingFit.current = false;
    fitToViewport();
  }, [fitToViewport]);

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

      {/* ── Panneau de sélection ── */}
      <aside className="w-64 border-r border-outline-variant/60 bg-background-dark flex flex-col shrink-0">
        <div className="p-4 space-y-3 border-b border-outline-variant/60">
          <h2 className="text-xs font-bold text-on-surface-variant uppercase tracking-widest">
            Topics ({selection.length}/{MAX_TOPICS})
          </h2>
          <div className="flex items-center gap-2 bg-surface-container-low border border-outline-variant rounded-md px-2.5 py-1.5 focus-within:border-primary/40 transition-colors">
            <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-base shrink-0">search</span>
            <input
              value={filter}
              onChange={e => setFilter(e.target.value)}
              placeholder="Filter topics…"
              aria-label="Filter topics"
              className="bg-transparent outline-none text-xs text-on-surface w-full placeholder:text-outline"
            />
          </div>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" className="flex-1"
              onClick={() => setSelection(sel => selectAll(sel, visibleTopics))}
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
            const full = !checked && selection.length >= MAX_TOPICS;
            return (
              <label key={topic}
                className={`flex items-center gap-2 px-2 py-1.5 rounded-md text-xs font-mono cursor-pointer transition-colors ${
                  checked ? 'bg-primary/10 text-on-surface' : 'text-on-surface-variant hover:bg-surface-container-high'
                } ${full ? 'opacity-40 cursor-not-allowed' : ''}`}>
                <input type="checkbox" checked={checked} disabled={full}
                  onChange={() => setSelection(sel => toggleTopic(sel, topic))}
                  className="accent-[#a3adff] shrink-0" />
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
                    <input type="checkbox" checked={showUnrelated}
                      onChange={() => {
                        // Le graphe change de taille : il faut le recadrer, sinon les nœuds
                        // qui apparaissent le font hors de l'écran.
                        pendingFit.current = true;
                        setShowUnrelated(v => !v);
                      }}
                      className="accent-[#a3adff] shrink-0" />
                    Draw them in the diagram
                  </label>
                </div>
              )}
            </div>
          )}
          {/* Légende des confiances : une arête déduite doit dire sur quoi elle repose. */}
          {model && relations.length > 0 && (
            <div className="space-y-1">
              <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">Relation confidence</p>
              {(Object.entries(CONFIDENCE_STYLE) as [keyof typeof CONFIDENCE_STYLE, typeof CONFIDENCE_STYLE.HIGH][]).map(([grade, style]) => (
                <div key={grade} className="flex items-center gap-2 text-[10px] text-on-surface-variant">
                  <svg width="24" height="6" aria-hidden="true">
                    <line x1="0" y1="3" x2="24" y2="3" stroke={style.color} strokeWidth="2"
                      strokeDasharray={style.dash} />
                  </svg>
                  <span>{grade.toLowerCase()} — {style.label}</span>
                </div>
              ))}
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
        </div>
      </aside>

      {/* ── Canevas ── */}
      <main className="flex-1 relative overflow-hidden graph-bg bg-background-dark">

        {/* Bandeau : couverture + avertissements */}
        <div className="absolute top-4 left-4 right-4 z-10 flex flex-col items-start gap-2 pointer-events-none">
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

        {error && (
          <div className="absolute inset-x-0 top-16 z-10 flex justify-center px-6">
            <ErrorPanel error={error} onRetry={() => generate(selection)}
              onDismiss={() => setError(null)} className="max-w-xl w-full" />
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
            <g transform={`translate(${transform.x}, ${transform.y}) scale(${transform.scale})`}>

              {/* Arêtes : ancrées sur la ligne de leur colonne, cardinalité en patte-d'oie —
                  la patte-d'oie côté référent (N), la barre côté référencé (1). Le style de
                  trait reste réservé à la confiance de la déduction. */}
              {relations.map((relation, i) => {
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
        <aside className="border-l border-outline-variant/60 bg-background-dark flex flex-col shrink-0 overflow-hidden" style={{ width: 320 }}>
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
                    {/* La preuve, en toutes lettres : une arête déduite sans son évidence est une
                        supposition dessinée comme un fait. */}
                    <p className="text-on-surface-variant leading-snug">{relation.reason}</p>
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
