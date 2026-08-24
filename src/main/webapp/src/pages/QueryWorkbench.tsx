// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import Editor, { useMonaco } from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import '../monaco-setup';
import axios from 'axios';
import { useToast } from '../components/Toast';
import {
  Button, Badge, Select, EmptyState, Tooltip, useConfirm, cn,
  useVirtualRows,
} from '../components/ui';
import {
  describeQueryError, describeApiError, offsetLocation,
  type QueryErrorInfo, type QueryErrorLocation,
} from './queryError';
import { resolveScope, resolveScopeAsTables, toTableName } from './sqlScope';
import { toCsv, toJson } from './resultExport';
import {
  sortRows, nextActiveTabId, isResultStale,
  writeStored, readStored, removeStored,
  readLayout, clamp, LAYOUT_STORAGE_KEY, DEFAULT_LAYOUT,
  SPLIT_MIN, SPLIT_MAX, SIDEBAR_MIN, SIDEBAR_MAX, type WorkbenchLayout,
  starterQueries, starterJobQueries, pushHistory, describeHistoryEntry, formatDuration, type HistoryEntry,
  splitStatements, statementIndexAt, offsetAt, resolveOrigin,
  planRun, detectStatementType, previewStatement,
  forgetOldestResults, summariseBatch, describeStatementRun, MAX_RETAINED_BATCH_ROWS,
  buildCompletionEntries, type CompletionEntry,
  type PlannedStatement, type StatementRun,
  readSqlParam, buildQueryLink,
  sidebarSqlFor, sidebarActionLabel, sinkNameRange, pickSinkTable, type ExecutionMode,
} from './queryWorkbench';
import { ResultsGrid } from '../components/query/ResultsGrid';
import { WindowAssistant } from '../components/query/WindowAssistant';
import { SchemaBrowser, type SchemaInfo, type SavedQuery } from '../components/query/SchemaBrowser';
import { DdlPreviewModal } from '../components/query/DdlPreviewModal';
import { NarrowWindowNotice } from '../components/query/NarrowWindowNotice';
import { RowDetail } from '../components/query/RowDetail';
import { randomId } from '../randomId';
import { copyText } from '../clipboard';
import { useCatalog } from '../catalogStore';
import type {
  QueryResult,
  FlinkJobSummary,
  DdlPreviewResponse,
  SqlValidationResponse,
  QueryCancelResponse,
} from '../api/types';

/** Contrôle segmenté compact (mode d'exécution, offset). */
function Segmented<T extends string>({ value, onChange, options, ariaLabel }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: string }[]; ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="inline-flex bg-surface-container-low border border-outline-variant rounded-md p-0.5">
      {options.map(o => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            'px-3 h-7 text-[12px] font-medium rounded transition-colors',
            value === o.value ? 'bg-surface-container-highest text-on-surface' : 'text-on-surface-variant hover:text-on-surface',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}


interface Tab { id: string; name: string; sql: string; }

/**
 * Ce que `POST /api/query/jobs` rend. Le nom local est celui du geste (une soumission) ; la forme
 * est celle du record Java, importée plutôt que recopiée — la page en tenait sa propre déclaration,
 * huit champs écrits à la main au point d'appel, alors que `api/types.ts` portait déjà la même
 * interface sous son marqueur `@java`. C'est exactement la dérive que `check-api-types.py` existe
 * pour attraper, et le motif qui a tué la page Compare : les deux copies s'accordaient, et rien ne
 * l'exigeait.
 */
type FlinkJobSubmission = FlinkJobSummary;

/**
 * Ce qu'une instruction a donné. `executeStatement` le rend, la boucle d'un lot le range dans son
 * entrée : un booléen suffisait pour « faut-il continuer », pas pour « qu'a fait celle-ci ».
 */
interface StatementOutcome {
  status: 'ok' | 'failed' | 'cancelled';
  ms: number;
  rows?: number;
  engine?: string;
  /** Plafond de lignes sous lequel elle a tourné, pour juger sa troncature plus tard. */
  limit?: number;
  result: QueryResult | null;
  job: FlinkJobSubmission | null;
  error: QueryErrorInfo | null;
}

/** Une entrée de lot, avec la soumission de job que seule cette page connaît. */
type Run = StatementRun<FlinkJobSubmission>;

/** L'icône de chaque issue d'instruction dans la liste d'un lot. */
const STATEMENT_ICON: Record<string, string> = {
  pending: 'schedule',
  running: 'sync',
  ok: 'check_circle',
  failed: 'error',
  cancelled: 'stop_circle',
  skipped: 'remove',
};

/** Choix de plafond de lignes. La valeur part au backend en `maxRows` — voir runQuery. */
const ROW_LIMITS = [50, 100, 500, 1000, 5000] as const;
const DEFAULT_LIMIT = 50;
/** Profondeur du retour arrière sur les onglets fermés — un filet, pas un historique. */
const CLOSED_TAB_CAP = 5;
const TABS_STORAGE_KEY = 'kse:tabs';
const HISTORY_STORAGE_KEY = 'kse:query-history';
const SAVED_STORAGE_KEY = 'kse:saved-queries';
/**
 * Délai d'écriture des onglets. Ils étaient sérialisés à *chaque frappe* : un `JSON.stringify` de
 * tous les onglets suivi d'une écriture `localStorage` synchrone sur le thread principal, à chaque
 * caractère. Un demi-mégaoctet de requêtes sauvegardées, et la frappe se met à traîner. Ce délai
 * ne change rien à ce qui est conservé — la dernière frappe est écrite, comme avant.
 */
const TABS_PERSIST_DEBOUNCE_MS = 400;
/**
 * L'onglet initial part **vide**.
 *
 * Il était semé d'une requête écrite en dur qui ne pouvait pas s'exécuter : `WINDOW TUMBLING
 * (SIZE 5 MINUTES) … EMIT CHANGES` est de la syntaxe ksqlDB, pas du Flink SQL, et `orders_stream`
 * n'existe dans aucun jeu de données de ce dépôt. Le premier geste évident — ouvrir l'éditeur,
 * appuyer sur Run — échouait donc, sur une erreur de parser depuis que les erreurs utilisateur ne
 * retombent plus sur le lecteur direct. Les propositions de départ sont désormais bâties sur le
 * catalogue réellement chargé (`starterQueries`) et affichées dans le panneau de résultats, où le
 * regard va déjà : elles ne peuvent pas citer une table absente, et il n'y en a aucune quand le
 * catalogue est vide.
 */
const DEFAULT_SQL = '';
// Au-delà de ce nombre de lignes, la grille passe en rendu virtualisé (seules
// les lignes visibles sont montées). En-deçà, on garde le rendu classique —
// aucun changement d'apparence ni de comportement pour le cas courant.
const VIRTUALIZE_THRESHOLD = 200;

/**
 * Plafond client d'une exécution, sans lequel le bouton Run tourne indéfiniment.
 *
 * `axios` n'a pas de timeout par défaut : si le serveur ne répond jamais — runtime Flink bloqué,
 * backend mort, connexion coupée sans FIN — la promesse ne se résout pas, `executing` reste vrai,
 * et l'écran affiche « Running… » pour toujours, sans résultat ni erreur. Volontairement bien
 * au-dessus du budget serveur (le temps de requête plus les attentes d'entrée dans le runtime) :
 * il n'est pas là pour arbitrer une requête lente, seulement pour qu'une requête qui ne reviendra
 * jamais se termine en disant pourquoi.
 */
const REQUEST_TIMEOUT_MS = 120_000;
// Hauteur d'une ligne virtualisée (px-4 py-2.5 text-[12px], forcée sur une
// seule ligne) — mesurée sur la première ligne montée, cette valeur sert de
// point de départ.
const EST_ROW_HEIGHT = 37;
let tabCounter = 1;
const newTab = (sql = ''): Tab => ({ id: String(++tabCounter), name: `Query ${tabCounter}`, sql });

/**
 * Restaure les onglets du dernier passage. Les requêtes sauvegardées et l'historique
 * survivaient déjà à un rechargement, pas les onglets : le travail en cours était perdu,
 * et un garde-fou `beforeunload` se contentait d'en avertir. On les persiste, ce qui rend
 * cet avertissement inutile.
 *
 * Un `?sql=` dans l'URL ouvre un onglet supplémentaire au lieu d'écraser le premier —
 * arriver depuis TopicExplorer ne doit pas effacer ce qui était en cours. Le paramètre est ensuite
 * retiré de l'URL par l'appelant (`consumedUrlSql`) : les onglets étant persistés, le laisser en
 * place faisait rouvrir un onglet identique **à chaque rechargement**, indéfiniment.
 *
 * Le SQL arrive **déjà décodé** (`readSqlParam`). Il était redécodé une seconde fois ici, ce qui
 * levait `URIError` sur tout `%` — donc sur n'importe quel `LIKE '%foo%'` — et faisait échouer le
 * montage de la page, l'appel ayant lieu dans un initialiseur `useState`.
 */
function restoreTabs(urlSql: string | null): { tabs: Tab[]; activeTabId: string; consumedUrlSql: boolean } {
  let tabs: Tab[] = [];
  let activeTabId = '';
  const parsed = readStored<{ tabs?: unknown; activeTabId?: unknown } | null>(TABS_STORAGE_KEY, null);
  if (Array.isArray(parsed?.tabs)) {
    tabs = parsed.tabs.filter((t): t is Tab =>
      !!t && typeof t.id === 'string' && typeof t.name === 'string' && typeof t.sql === 'string');
    activeTabId = typeof parsed.activeTabId === 'string' ? parsed.activeTabId : '';
  }

  if (!tabs.length) {
    tabs = [{ id: '1', name: 'Query 1', sql: urlSql ?? DEFAULT_SQL }];
    return { tabs, activeTabId: '1', consumedUrlSql: !!urlSql };
  }

  // Les ids restaurés viennent d'un compteur d'une session précédente : on le repositionne
  // au-dessus, sinon le prochain « + » recréerait un id déjà pris.
  tabCounter = Math.max(tabCounter, ...tabs.map(t => Number(t.id) || 0));

  if (urlSql) {
    const incoming = urlSql;
    // Le même SQL déjà ouvert ne mérite pas un onglet de plus : on active celui qui l'a.
    const existing = tabs.find(t => t.sql.trim() === incoming.trim());
    if (existing) return { tabs, activeTabId: existing.id, consumedUrlSql: true };
    const fromUrl = newTab(incoming);
    return { tabs: [...tabs, fromUrl], activeTabId: fromUrl.id, consumedUrlSql: true };
  }
  return {
    tabs,
    activeTabId: tabs.some(t => t.id === activeTabId) ? activeTabId : tabs[0].id,
    consumedUrlSql: false,
  };
}


const QueryWorkbench: React.FC = () => {
  const { toast } = useToast();
  const confirm = useConfirm();
  const location = useLocation();
  const navigate = useNavigate();

  // ── Schema state ──────────────────────────────────────────────────────────────
  const [schema, setSchema] = useState<SchemaInfo | null>(null);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [expandedTables, setExpandedTables] = useState<Record<string, boolean>>({});
  const [tableSchemas, setTableSchemas] = useState<Record<string, Record<string, string>>>({});
  const schemaRef = useRef<SchemaInfo | null>(null);
  const tableSchemasRef = useRef<Record<string, Record<string, string>>>({});
  useEffect(() => { schemaRef.current = schema; }, [schema]);
  useEffect(() => { tableSchemasRef.current = tableSchemas; }, [tableSchemas]);

  /*
   * Le préfixe des topics internes vient du catalogue partagé, que `Layout` alimente depuis son
   * sondage de `/api/dashboard` — donc aucune requête de plus, et la même valeur que celle dont
   * le modèle de données se sert. Le catalogue de cette page vient de `/api/query/init`, qui ne
   * le porte pas : deux réponses porteraient deux copies d'un même réglage, ce qui finit par
   * diverger. Un `ref` en plus, parce que `pickSinkTable` est appelé depuis un gestionnaire.
   */
  const { internalPrefix } = useCatalog();
  const internalPrefixRef = useRef(internalPrefix);
  useEffect(() => { internalPrefixRef.current = internalPrefix; }, [internalPrefix]);

  // ── Tabs ──────────────────────────────────────────────────────────────────────
  // Une seule restauration, au premier rendu : relire le stockage à chaque rendu
  // ressusciterait les onglets fermés.
  const [restored] = useState(() => restoreTabs(readSqlParam(location.search)));
  const [tabs, setTabs] = useState<Tab[]>(restored.tabs);
  const [activeTabId, setActiveTabId] = useState(restored.activeTabId);
  const [renamingTabId, setRenamingTabId] = useState<string | null>(null);
  const [renamingName, setRenamingName] = useState('');

  // Écriture différée : voir TABS_PERSIST_DEBOUNCE_MS. Un `localStorage` saturé n'invalide pas
  // l'édition en cours, mais il faut le dire une fois — sinon la reprise est perdue en silence.
  const storageWarned = useRef(false);
  useEffect(() => {
    const handle = setTimeout(() => {
      if (writeStored(TABS_STORAGE_KEY, { tabs, activeTabId }) || storageWarned.current) return;
      storageWarned.current = true;
      toast('Browser storage is full — open tabs will not be restored next time.', 'error');
    }, TABS_PERSIST_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [tabs, activeTabId, toast]);

  // Le `?sql=` est consommé une fois, puis retiré de l'URL : les onglets étant persistés, le
  // laisser en place rouvrait le même onglet à chaque F5.
  useEffect(() => {
    if (!restored.consumedUrlSql) return;
    navigate(`${location.pathname}${location.hash}`, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- une seule fois, au montage
  }, []);

  const activeTab = tabs.find(t => t.id === activeTabId) ?? tabs[0];
  const sql = activeTab.sql;
  const setSql = useCallback((newSql: string) => {
    setTabs(prev => prev.map(t => t.id === activeTabId ? { ...t, sql: newSql } : t));
  }, [activeTabId]);

  const addTab = useCallback((sqlText = '', name?: string) => {
    const t = newTab(sqlText);
    if (name) t.name = name;
    setTabs(prev => [...prev, t]);
    setActiveTabId(t.id);
    return t;
  }, []);

  /**
   * Ferme un onglet. Le SQL qu'il portait n'est plus nulle part — d'où la confirmation dès qu'il
   * y a quelque chose à perdre, comme partout ailleurs dans l'application (`useConfirm`). Le
   * calcul de l'onglet suivant est sorti de l'updater : celui-ci doit être pur.
   */
  /**
   * Les derniers onglets fermés, le plus récent en tête.
   *
   * Fermer un onglet demandait confirmation quand il portait du SQL — la moitié bon marché du
   * problème — mais restait sans retour possible, et ce texte n'existe nulle part ailleurs. Le
   * `Toast` partagé ne porte qu'un message et un ton, pas d'action : offrir l'annulation par là
   * aurait été une modification du système de composants pour un seul écran. La barre d'onglets,
   * elle, est exactement l'endroit où l'on cherche un onglet disparu.
   *
   * En mémoire seulement, et borné : c'est un filet pour le geste qu'on vient de faire, pas un
   * historique. Un rechargement de page le vide, ce que la persistance des onglets rend acceptable
   * — ce qui est perdu là est ce qu'on a explicitement confirmé vouloir fermer.
   */
  const [closedTabs, setClosedTabs] = useState<Tab[]>([]);

  const closeTab = useCallback(async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const tab = tabs.find(t => t.id === id);
    if (tabs.length === 1 || !tab) return;
    if (tab.sql.trim()) {
      const ok = await confirm({
        title: 'Close this tab?',
        description: <>“{tab.name}” holds SQL that is not saved anywhere else. Save it first if you want to keep it.</>,
        confirmLabel: 'Close',
        tone: 'danger',
      });
      if (!ok) return;
    }
    setActiveTabId(prev => nextActiveTabId(tabs, id, prev));
    setTabs(prev => prev.filter(t => t.id !== id));
    setClosedTabs(prev => [tab, ...prev].slice(0, CLOSED_TAB_CAP));
  }, [tabs, confirm]);

  /** Rouvre le dernier onglet fermé, tel qu'il était, et l'active. */
  const reopenClosedTab = useCallback(() => {
    setClosedTabs(prev => {
      const [restored, ...rest] = prev;
      if (!restored) return prev;
      setTabs(current => (current.some(t => t.id === restored.id) ? current : [...current, restored]));
      setActiveTabId(restored.id);
      return rest;
    });
  }, []);

  const startRename = (tab: Tab, e: React.MouseEvent) => {
    e.stopPropagation();
    setRenamingTabId(tab.id);
    setRenamingName(tab.name);
  };

  const commitRename = () => {
    if (renamingTabId && renamingName.trim()) {
      setTabs(prev => prev.map(t => t.id === renamingTabId ? { ...t, name: renamingName.trim() } : t));
    }
    setRenamingTabId(null);
  };

  // ── Named saved queries ───────────────────────────────────────────────────────
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>(
    () => readStored<SavedQuery[]>(SAVED_STORAGE_KEY, []),
  );
  const [saveInputVisible, setSaveInputVisible] = useState(false);
  const [saveInputName, setSaveInputName] = useState('');

  // Retourne ce que l'écriture a donné : une sauvegarde qui n'a pas été écrite ne doit pas
  // s'annoncer « Saved » — elle disparaîtra au rechargement.
  const persistSaved = (next: SavedQuery[]): boolean => {
    setSavedQueries(next);
    return writeStored(SAVED_STORAGE_KEY, next);
  };

  const saveQuery = async () => {
    const name = saveInputName.trim() || activeTab.name;
    if (!sql.trim()) { toast('Nothing to save — the tab is empty', 'info'); return; }
    // Enregistrer deux fois sous le même nom laissait deux entrées indiscernables dans la liste.
    const existing = savedQueries.find(q => q.name === name);
    if (existing && existing.sql !== sql) {
      const ok = await confirm({
        title: `Replace “${name}”?`,
        description: <>A saved query already goes by that name. Its SQL will be overwritten.</>,
        confirmLabel: 'Replace',
      });
      if (!ok) return;
    }
    const entry: SavedQuery = { id: existing?.id ?? randomId(), name, sql, savedAt: Date.now() };
    const written = persistSaved([entry, ...savedQueries.filter(q => q.id !== entry.id)]);
    setSaveInputVisible(false);
    setSaveInputName('');
    toast(
      written ? `Saved "${name}"` : `"${name}" is listed but could not be stored — browser storage is full`,
      written ? 'success' : 'error',
    );
  };

  const deleteSavedQuery = async (id: string) => {
    const q = savedQueries.find(s => s.id === id);
    const ok = await confirm({
      title: 'Delete saved query?',
      description: q ? <>“{q.name}” will be removed from your saved queries.</> : undefined,
      confirmLabel: 'Delete',
      tone: 'danger',
      icon: 'delete',
    });
    if (ok) persistSaved(savedQueries.filter(s => s.id !== id));
  };

  const loadSavedQuery = (q: SavedQuery) => {
    addTab(q.sql, q.name);
    toast(`Loaded "${q.name}" in new tab`, 'success');
  };

  // ── Monaco: editor ref + keybindings + providers ──────────────────────────────
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const monaco = useMonaco();
  const runQueryRef = useRef<() => void>(() => {});
  const runAllRef = useRef<() => void>(() => {});

  /**
   * Le raccourci ⌘↵ était enregistré dans un effet dépendant du seul `monaco`, qui abandonnait si
   * `editorRef.current` était encore nul. Monaco étant désormais empaqueté localement
   * (`monaco-setup`), `useMonaco()` résout très tôt — souvent avant que `<Editor>` ait monté — et
   * l'effet ne se rejouait jamais ensuite : le raccourci documenté sur la barre d'outils ne
   * s'installait pas. On l'enregistre au montage de l'éditeur, seul instant où l'instance existe
   * à coup sûr.
   */
  const registerEditorCommands = useCallback((ed: editor.IStandaloneCodeEditor, m: typeof monaco) => {
    if (!m) return;
    ed.addCommand(m.KeyMod.CtrlCmd | m.KeyCode.Enter, () => runQueryRef.current());
    // Le pendant clavier de « Run all ». Le bouton n'apparaît qu'au-delà d'une instruction ; le
    // raccourci se contente d'exécuter ce qu'il y a, quel qu'en soit le nombre.
    ed.addCommand(m.KeyMod.CtrlCmd | m.KeyMod.Shift | m.KeyCode.Enter, () => runAllRef.current());
    ed.addCommand(m.KeyMod.CtrlCmd | m.KeyMod.Shift | m.KeyCode.KeyF, () => {
      void ed.getAction('editor.action.formatDocument')?.run();
    });
  }, []);

  // Le fournisseur de formatage SQL vit dans `monaco-setup` : il est global à l'instance Monaco,
  // et l'enregistrer depuis cette page le retirait des trois autres éditeurs SQL de l'application.

  /** Portée mémoïsée de l'autocomplétion — voir son usage dans le provider ci-dessous. */
  const scopeCacheRef = useRef<{ version: number; catalogKey: string; scope: string[] }>(
    { version: -1, catalogKey: '', scope: [] },
  );

  /**
   * Propositions mémoïsées, clefées sur les identités du catalogue, des schémas chargés et de la
   * portée : reconstruire quelques centaines d'objets par caractère tapé pour un résultat
   * identique était le coût principal de la complétion.
   */
  const completionCacheRef = useRef<{
    catalogue: SchemaInfo | null;
    loaded: Record<string, Record<string, string>> | null;
    scope: readonly string[] | null;
    entries: CompletionEntry[];
  }>({ catalogue: null, loaded: null, scope: null, entries: [] });

  // Auto-completion provider
  useEffect(() => {
    if (!monaco) return;
    const KINDS = {
      table: monaco.languages.CompletionItemKind.Class,
      column: monaco.languages.CompletionItemKind.Field,
      keyword: monaco.languages.CompletionItemKind.Keyword,
    } as const;
    const disp = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: [' ', '\n', '.'],
      provideCompletionItems: (_model, position) => {
        const word = _model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
        const catalogue = schemaRef.current;
        const loaded = tableSchemasRef.current;
        /*
         * `resolveScope` fait deux passes de regex sur tout le document, et il était rappelé à
         * chaque frappe : le provider est invoqué sur chaque caractère (`quickSuggestions`). La
         * portée ne peut changer qu'avec le texte ou avec le catalogue chargé, donc on la garde
         * jusqu'à ce que l'un des deux bouge. `getVersionId()` s'incrémente à chaque édition du
         * modèle, ce qui en fait exactement la clé voulue.
         */
        const version = _model.getVersionId();
        const catalogKey = Object.keys(loaded).join('|');
        const cache = scopeCacheRef.current;
        if (cache.version !== version || cache.catalogKey !== catalogKey) {
          cache.version = version;
          cache.catalogKey = catalogKey;
          cache.scope = resolveScope(_model.getValue(), Object.keys(loaded));
        }

        /*
         * Les propositions elles-mêmes ne dépendent que du catalogue, des schémas chargés et de la
         * portée : elles étaient pourtant refabriquées à chaque caractère — un objet par table, un
         * `toTableName()` et un `sortText` interpolé par topic, un `Object.entries` par table. La
         * clé de ce cache est faite d'**identités d'objets**, pas de chaînes : `cache.scope` n'est
         * remplacé que quand la portée est recalculée ci-dessus, si bien qu'entre deux frappes la
         * comparaison est celle de trois références.
         */
        const entryCache = completionCacheRef.current;
        if (entryCache.catalogue !== catalogue || entryCache.loaded !== loaded || entryCache.scope !== cache.scope) {
          entryCache.catalogue = catalogue;
          entryCache.loaded = loaded;
          entryCache.scope = cache.scope;
          entryCache.entries = buildCompletionEntries(catalogue, loaded, cache.scope);
        }

        // Seule la plage change d'un appel à l'autre : elle suit le mot sous le curseur.
        return {
          suggestions: entryCache.entries.map(entry => ({
            label: entry.label,
            kind: KINDS[entry.kind],
            insertText: entry.insertText,
            detail: entry.detail,
            documentation: entry.documentation,
            sortText: entry.sortText,
            range,
          })),
        };
      },
    });
    return () => disp.dispose();
  }, [monaco]);

  // Hover provider: schema tooltip on table names
  useEffect(() => {
    if (!monaco) return;
    const disp = monaco.languages.registerHoverProvider('sql', {
      provideHover: (_model, position) => {
        const word = _model.getWordAtPosition(position);
        if (!word) return null;
        // Le survol ne reconnaissait que les tables Flink, comme la complétion : survoler un nom
        // écrit par la barre latérale elle-même n'affichait rien.
        const catalogue = schemaRef.current;
        const isTable = (catalogue?.tables ?? []).includes(word.word);
        const topic = (catalogue?.topics ?? []).find(t => toTableName(t) === word.word);
        if (!isTable && !topic) return null;
        const cols = tableSchemasRef.current[word.word];
        const heading = isTable
          ? `**Flink Table** \`${word.word}\``
          : `**Kafka topic** \`${topic}\`\n\n_Registered as \`${word.word}\` on first use_`;
        const body = cols
          ? `${heading}\n\n| Column | Type |\n|--------|------|\n${Object.entries(cols).map(([c, t]) => `| \`${c}\` | \`${t}\` |`).join('\n')}`
          : `${heading}\n\n_Expand in sidebar to load schema_`;
        return {
          range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
          contents: [{ value: body }],
        };
      },
    });
    return () => disp.dispose();
  }, [monaco]);

  // ── Query state ───────────────────────────────────────────────────────────────
  const [results, setResults] = useState<QueryResult | null>(null);
  const [submittedJob, setSubmittedJob] = useState<FlinkJobSubmission | null>(null);
  const [executing, setExecuting] = useState(false);
  // Miroir synchrone de `executing` : un état React n'est pas encore à jour quand deux ⌘↵ se
  // suivent dans le même tick, et c'est précisément ce qu'il faut refuser.
  const executingRef = useRef(false);
  const [executionMs, setExecutionMs] = useState<number | null>(null);
  /** SQL de la requête qui a produit `results` — sert à marquer l'affichage périmé. */
  const [ranSql, setRanSql] = useState<string | null>(null);
  /**
   * Le lot en cours ou terminé, une entrée par instruction, ou `null` quand la dernière exécution
   * n'en portait qu'une. C'est lui qui garde ce que chaque instruction a donné — voir `runBatch`.
   */
  const [batch, setBatch] = useState<Run[] | null>(null);
  /** L'entrée du lot que la grille affiche. */
  const [selectedRun, setSelectedRun] = useState<number | null>(null);
  /**
   * Un lot est en cours. Distinct de `executingRef`, qui retombe entre deux instructions : c'est
   * dans cet intervalle qu'un ⌘↵ lançait une requête concurrente de la boucle.
   */
  const batchRef = useRef(false);
  /** Stop a été demandé pendant un lot — relu par la boucle, qui s'arrête à l'instruction suivante. */
  const batchCancelRef = useRef(false);
  /**
   * Le lot décrit les instructions d'un onglet : changer d'onglet le vide, ses numéros ne
   * désignant plus rien dans le texte affiché. Ajusté pendant le rendu — le motif
   * documenté pour un état dérivé, et le même que `errorSource` plus bas — plutôt qu'un effet,
   * qui laisserait le lot de l'onglet précédent visible le temps d'un rendu.
   */
  const [batchTabId, setBatchTabId] = useState(activeTabId);
  if (batchTabId !== activeTabId) {
    setBatchTabId(activeTabId);
    setBatch(null);
    setSelectedRun(null);
  }
  /** Dernier SQL que le validateur backend a accepté, pour ne pas le lui redemander à l'identique. */
  const validatedSqlRef = useRef<string | null>(null);
  // Plafond de lignes réellement envoyé au backend. Il était figé à 50 côté serveur
  // (`explorer.default-max-rows`) et deviné côté UI par une constante du même nom.
  const [maxRows, setMaxRows] = useState<number>(DEFAULT_LIMIT);
  // Le plafond de la requête qui a produit `results` — c'est lui qui dit si le résultat
  // est tronqué, pas la valeur courante du sélecteur (que l'utilisateur a pu changer depuis).
  const [resultLimit, setResultLimit] = useState<number>(DEFAULT_LIMIT);
  // Annulation : le run en cours et son AbortController. L'id est généré par le client,
  // sinon on ne le connaîtrait qu'une fois la requête terminée — trop tard pour l'annuler.
  const abortRef = useRef<AbortController | null>(null);
  const runningQueryIdRef = useRef<string | null>(null);
  /*
   * L'origine du fragment exécuté est **dérivée du texte courant** (`resolveOrigin`), non retenue
   * au moment du run. Figée, elle devenait fausse dès la première édition : ajouter une ligne
   * au-dessus décalait le « Jump to line » d'autant, et `updateSql` la remettait à `null` — ce qui
   * ramenait la position d'une instruction quelconque au haut du document, puisque `results.error`,
   * lui, survit à l'édition. Dérivée, elle suit le texte, et vaut `null` quand ce qui a été exécuté
   * n'y est plus : la position est alors retirée plutôt que devinée.
   */
  // Reflète la présence d'une sélection non vide, pour libeller le bouton en conséquence.
  const [hasSelection, setHasSelection] = useState(false);
  /**
   * Décalage du curseur dans le document, pour désigner l'instruction à exécuter. Écrit à chaque
   * mouvement du curseur : React abandonne le rendu quand la valeur ne change pas, et ce qui en
   * dépend (l'index d'instruction) ne change qu'en franchissant un `;`.
   */
  const [cursorOffset, setCursorOffset] = useState(0);
  const statements = useMemo(() => splitStatements(sql), [sql]);
  const cursorStatement = useMemo(
    () => statementIndexAt(statements, cursorOffset),
    [statements, cursorOffset],
  );
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('SYNC_READ');
  const [offsetMode, setOffsetMode] = useState<'EARLIEST' | 'LATEST'>('EARLIEST');
  const [panelError, setPanelError] = useState<QueryErrorInfo | null>(null);
  const [sortCol, setSortCol] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  /**
   * Ligne ouverte dans le panneau de détail, par index dans `sortedRows`. Un index, et non la
   * ligne elle-même : deux lignes identiques existent, et c'est la position affichée qui doit
   * répondre aux flèches. Il est remis à zéro dès que le tri ou le résultat change, l'index
   * n'ayant plus le même sens.
   */
  const [detailIndex, setDetailIndex] = useState<number | null>(null);
  const [detailColumn, setDetailColumn] = useState<string | null>(null);
  const [showErrorDetails, setShowErrorDetails] = useState(false);
  // Une nouvelle erreur (ou un nouveau résultat) referme le détail : ajusté pendant le rendu,
  // le motif documenté pour un état dérivé, plutôt qu'un effet qui laissait le détail de
  // l'erreur précédente ouvert le temps d'un rendu.
  const [errorSource, setErrorSource] = useState<unknown>(null);
  if (errorSource !== (panelError ?? results)) {
    setErrorSource(panelError ?? results);
    setShowErrorDetails(false);
  }

  const sortedRows = useMemo(
    () => (results?.rows ? sortRows(results.rows, sortCol, sortDir) : []),
    [results, sortCol, sortDir],
  );

  const runOrigin = useMemo(() => resolveOrigin(sql, ranSql), [sql, ranSql]);

  // Erreur classée (titre lisible + piste + position) — voir queryError.ts.
  // Une requête rejetée avant exécution (mode, validateur backend) passe par le même
  // panneau que l'échec d'exécution : même titre lisible, même piste, même marqueur
  // Monaco. Un rejet pré-vol l'emporte, puisqu'il n'y a alors aucun résultat.
  const queryError = useMemo(() => {
    const info = panelError ?? (results?.error ? describeQueryError(results.error) : null);
    if (!info) return null;
    // Ramène la position dans le repère du document quand seule la sélection a été exécutée.
    // Plus d'origine résoluble : le texte mesuré a disparu de l'onglet, et pointer une ligne
    // quand même désignerait autre chose que ce dont le moteur parle. Le message brut garde sa
    // ligne et sa colonne, relatives à ce qui avait été envoyé.
    if (!runOrigin) return { ...info, location: undefined };
    return { ...info, location: offsetLocation(info.location, runOrigin) };
  }, [panelError, results, runOrigin]);

  // Le résultat bute sur son propre plafond → il est probablement incomplet.
  const truncated = !!results && !results.error && results.rows.length >= resultLimit;

  /** L'instruction du lot en cours d'exécution, ou -1. Dérivé : deux sources se contrediraient. */
  const runningStatement = useMemo(
    () => (batch ? batch.findIndex(r => r.status === 'running') : -1),
    [batch],
  );
  /** L'entrée du lot que la grille montre — c'est elle qui dit si ses lignes existent encore. */
  const shownRun = batch && selectedRun !== null ? batch[selectedRun] ?? null : null;

  /**
   * Les lignes affichées ne répondent plus au SQL en cours d'édition. La grille gardait le
   * résultat précédent sans rien dire pendant qu'on écrivait la requête suivante — on lit alors
   * un tableau en croyant qu'il répond au texte sous les yeux. Stream Flow marque son graphe
   * exactement de la même façon.
   *
   * Les instructions du document entrent dans ce jugement : sans elles, un résultat issu d'un
   * onglet multi-instructions est *toujours* périmé, le texte exécuté n'étant jamais égal au
   * contenu de l'onglet — et un avertissement permanent ne prévient plus de rien.
   */
  const staleResults = !executing && !!results && isResultStale(ranSql, sql, statements);

  // Place le curseur sur la position fautive et la révèle dans l'éditeur.
  const jumpToError = useCallback((loc: QueryErrorLocation) => {
    const ed = editorRef.current;
    if (!ed) return;
    ed.focus();
    ed.setPosition({ lineNumber: loc.line, column: loc.column });
    ed.revealPositionInCenter({ lineNumber: loc.line, column: loc.column });
  }, []);

  // Souligne la position fautive dans Monaco (marqueur d'erreur natif) quand le
  // moteur a renvoyé une ligne/colonne. Effacé dès qu'il n'y a plus d'erreur.
  useEffect(() => {
    if (!monaco || !editorRef.current) return;
    const model = editorRef.current.getModel();
    if (!model) return;
    const loc = queryError?.location;
    monaco.editor.setModelMarkers(model, 'kse-sql-error', loc ? [{
      severity: monaco.MarkerSeverity.Error,
      message: queryError?.hint ? `${queryError.title}\n${queryError.hint}` : (queryError?.title ?? 'SQL error'),
      startLineNumber: loc.line,
      startColumn: loc.column,
      endLineNumber: loc.line,
      endColumn: loc.column + 1,
    }] : []);
  }, [monaco, queryError]);

  /**
   * Toute modification du SQL passe par ici. Le marqueur d'erreur et le rejet pré-vol désignent
   * une position et une requête qui n'existent déjà plus à la première frappe : ils s'effacent
   * donc *avec* l'édition, au lieu d'être rattrapés par un effet au rendu suivant.
   */
  const updateSql = useCallback((next: string) => {
    setSql(next);
    setPanelError(null);
    const model = editorRef.current?.getModel();
    if (monaco && model) monaco.editor.setModelMarkers(model, 'kse-sql-error', []);
  }, [monaco, setSql]);

  // ── Virtualisation de la grille de résultats ────────────────────────────────
  const resultsScrollRef = useRef<HTMLDivElement>(null);
  const [rowHeight, setRowHeight] = useState(EST_ROW_HEIGHT);
  const virtualized = sortedRows.length > VIRTUALIZE_THRESHOLD;
  const vwin = useVirtualRows(resultsScrollRef, virtualized ? sortedRows.length : 0, rowHeight);
  // Mesure la hauteur réelle de la première ligne montée (métriques de police
  // variables selon la plateforme) et réaligne la virtualisation dessus.
  const measureRow = useCallback((tr: HTMLTableRowElement | null) => {
    if (!tr) return;
    const h = tr.getBoundingClientRect().height;
    if (h > 0) setRowHeight(prev => (Math.abs(prev - h) > 0.5 ? h : prev));
  }, []);
  // Mémoïsés sur leurs valeurs primitives : `useVirtualRows` rend un objet neuf à chaque rendu et
  // `slice` un tableau neuf, ce qui ferait tomber le `React.memo` de la grille à chaque frappe.
  const visibleRows = useMemo(
    () => (virtualized ? sortedRows.slice(vwin.start, vwin.end) : sortedRows),
    [virtualized, sortedRows, vwin.start, vwin.end],
  );
  const gridWindow = useMemo(
    () => ({ start: vwin.start, padTop: vwin.padTop, padBottom: vwin.padBottom }),
    [vwin.start, vwin.padTop, vwin.padBottom],
  );

  // ── DDL preview ───────────────────────────────────────────────────────────────
  const [ddlPreviewTopic, setDdlPreviewTopic] = useState<string | null>(null);
  const [ddlPreview, setDdlPreview] = useState<string | null>(null);
  const [ddlPreviewError, setDdlPreviewError] = useState<QueryErrorInfo | null>(null);
  const [ddlPreviewLoading, setDdlPreviewLoading] = useState(false);

  /**
   * Le modal DDL était une boîte maison sans échappement, sans rendu du focus et sans piège à
   * focus : ouvert au clavier, il laissait le focus derrière lui dans la page, et `Escape` — le
   * geste réflexe pour fermer une boîte — ne faisait rien. Le reste de l'application ferme sur
   * `Escape` (`ConfirmDialog`, le modal de Metrics, la palette de commandes).
   */
  const ddlOpenerRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!ddlPreviewTopic) return;
    // `Escape` est géré par le modal lui-même ; le retour du focus reste ici, la page étant la
    // seule à savoir quel élément l'avait ouvert.
    return () => {
      ddlOpenerRef.current?.focus();
      ddlOpenerRef.current = null;
    };
  }, [ddlPreviewTopic]);

  const fetchDdlPreview = async (topicName: string) => {
    ddlOpenerRef.current = document.activeElement as HTMLElement | null;
    setDdlPreviewTopic(topicName);
    setDdlPreview(null);
    setDdlPreviewError(null);
    setDdlPreviewLoading(true);
    try {
      const res = await axios.get<DdlPreviewResponse>(`/api/query/ddl-preview?topic=${encodeURIComponent(topicName)}`);
      setDdlPreview(res.data.ddl ?? null);
      // Le motif s'affiche *dans* la boîte, pas dans un toast qui s'efface derrière elle en trois
      // secondes en emportant la seule chose utile — pourquoi l'inférence n'a rien donné.
      if (res.data.error) setDdlPreviewError(describeQueryError(res.data.error));
    } catch (e) { setDdlPreviewError(describeApiError(e, 'Failed to generate DDL preview')); }
    finally { setDdlPreviewLoading(false); }
  };

  // ── History ───────────────────────────────────────────────────────────────────
  const [showHistory, setShowHistory] = useState(false);
  const historyRef = useRef<HTMLDivElement>(null);
  const [history, setHistory] = useState<HistoryEntry[]>(
    () => readStored<HistoryEntry[]>(HISTORY_STORAGE_KEY, []),
  );
  /**
   * Une entrée porte maintenant ce que la requête a donné — durée, lignes, moteur, succès. Elle ne
   * portait que le SQL et l'heure, et **les échecs n'y entraient pas** : retrouver « celle qui
   * avait marché » demandait de relire vingt requêtes, et celle qu'on voulait rouvrir pour la
   * corriger n'y était même pas.
   */
  const saveToHistory = (entry: HistoryEntry) => {
    const next = pushHistory(history, entry);
    setHistory(next);
    writeStored(HISTORY_STORAGE_KEY, next);
  };

  /**
   * Un menu déroulant se ferme au clic à côté et à l'échappement — il restait ouvert par-dessus la
   * grille de résultats jusqu'à ce qu'on repense à recliquer son bouton.
   */
  useEffect(() => {
    if (!showHistory) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!historyRef.current?.contains(e.target as Node)) setShowHistory(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setShowHistory(false); };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [showHistory]);

  // ── Window assistant ──────────────────────────────────────────────────────────
  // L'état du formulaire de fenêtrage vit dans `WindowAssistant` : rien d'autre ne le lit.

  // ── Resize: split pane (vertical) + sidebar (horizontal) ─────────────────────
  /**
   * Trois défauts corrigés d'un coup ici.
   *
   * 1. **La largeur de la sidebar était lue en coordonnées écran** : `setSidebarWidth(e.clientX)`
   *    suppose que ce panneau commence au bord gauche de la fenêtre. Il n'y a jamais commencé — la
   *    navigation globale de `Layout` occupe 68 px repliée, 256 px dépliée — donc saisir la
   *    poignée faisait sauter la largeur de cet écart, dans un sens ou dans l'autre selon l'état
   *    de la navigation. On mesure désormais depuis le bord réel du panneau.
   * 2. **`mousedown`/`mousemove` n'existent pas au doigt** : sur une tablette, aucune des deux
   *    poignées ne bougeait. Les événements *pointer* couvrent souris, stylet et doigt — c'est ce
   *    que Stream Flow et Lineage utilisent déjà, pour la même raison.
   * 3. **Ni clavier, ni sémantique** : ce sont des séparateurs, donc des `role="separator"`
   *    focusables que les flèches déplacent, comme le split de Stream Flow.
   */
  const containerRef = useRef<HTMLDivElement>(null);
  const asideRef = useRef<HTMLElement>(null);
  const dragging = useRef<'split' | 'sidebar' | null>(null);
  const [layout, setLayout] = useState<WorkbenchLayout>(readLayout);
  const { splitPercent, sidebarWidth, assistantOpen } = layout;
  const toggleAssistant = useCallback(
    () => setLayout(prev => ({ ...prev, assistantOpen: !prev.assistantOpen })),
    [],
  );

  const setSplitPercent = useCallback((next: number | ((p: number) => number)) => {
    setLayout(prev => ({
      ...prev,
      splitPercent: clamp(typeof next === 'function' ? next(prev.splitPercent) : next, SPLIT_MIN, SPLIT_MAX),
    }));
  }, []);
  const setSidebarWidth = useCallback((next: number | ((p: number) => number)) => {
    setLayout(prev => ({
      ...prev,
      sidebarWidth: clamp(typeof next === 'function' ? next(prev.sidebarWidth) : next, SIDEBAR_MIN, SIDEBAR_MAX),
    }));
  }, []);

  useEffect(() => {
    const handle = setTimeout(() => writeStored(LAYOUT_STORAGE_KEY, layout), 300);
    return () => clearTimeout(handle);
  }, [layout]);

  useEffect(() => {
    const onPointerMove = (e: PointerEvent) => {
      if (dragging.current === 'split' && containerRef.current) {
        const rect = containerRef.current.getBoundingClientRect();
        setSplitPercent(((e.clientY - rect.top) / rect.height) * 100);
      }
      if (dragging.current === 'sidebar' && asideRef.current) {
        setSidebarWidth(e.clientX - asideRef.current.getBoundingClientRect().left);
      }
    };
    const onPointerUp = () => {
      dragging.current = null;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.addEventListener('pointermove', onPointerMove);
    document.addEventListener('pointerup', onPointerUp);
    document.addEventListener('pointercancel', onPointerUp);
    return () => {
      document.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('pointerup', onPointerUp);
      document.removeEventListener('pointercancel', onPointerUp);
    };
  }, [setSplitPercent, setSidebarWidth]);

  const startDrag = useCallback((which: 'split' | 'sidebar', cursor: string, e: React.PointerEvent) => {
    dragging.current = which;
    document.body.style.cursor = cursor;
    // Sans cela, le glissement sélectionne le texte des deux panneaux qu'il traverse.
    document.body.style.userSelect = 'none';
    e.preventDefault();
  }, []);

  // Plus de garde-fou `beforeunload` : les onglets sont persistés à chaque frappe, donc
  // un rechargement ne perd rien et la boîte « quitter le site ? » n'avait plus de raison
  // d'être.

  // ── Actions ───────────────────────────────────────────────────────────────────
  const fetchSchema = async () => {
    setSchemaLoading(true);
    try {
      const r = await axios.get<SchemaInfo>('/api/query/init');
      setSchema(r.data);
      // Le catalogue a changé (CREATE TABLE, auto-enregistrement) : une table dont le schéma
      // avait échoué faute d'existence mérite une nouvelle tentative.
      schemaFetchAttempted.current.clear();
    }
    catch (e) {
      // L'endpoint ne tombe plus sur un échec de Kafka ou de Flink — il les rapporte. S'il tombe
      // quand même, c'est le transport, et le motif vaut mieux qu'un « Failed to load schema »
      // qui n'apprend rien : on le range là où l'écran regarde déjà.
      const info = describeApiError(e, 'Failed to load the catalogue');
      setSchema(prev => ({
        topics: prev?.topics ?? [],
        tables: prev?.tables ?? [],
        health: false,
        kafkaError: info.title,
        flinkError: null,
      }));
    }
    finally { setSchemaLoading(false); }
  };

  /**
   * Charge le catalogue à l'arrivée sur la page.
   *
   * Il ne l'était nulle part : `fetchSchema` n'était appelé que par le bouton de
   * rafraîchissement de la sidebar et après un `CREATE TABLE`. Ouvrir l'éditeur affichait donc
   * « Engine offline · 0 tables · 0 topics » — et une sidebar vide — sur un moteur parfaitement
   * sain, jusqu'à ce que l'on pense à cliquer une icône qui ne s'annonce pas comme le seul
   * moyen de peupler l'écran. L'autocomplétion des colonnes en dépend aussi : elle ne demande
   * le schéma que des tables que le catalogue connaît.
   */
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- chargement initial du catalogue
    void fetchSchema();
    // Au montage seulement : `fetchSchema` est recréé à chaque rendu, le mettre en dépendance
    // relancerait la requête en boucle.
  }, []);

  /**
   * Charge le schéma des tables citées par la requête en cours, pour que l'autocomplétion
   * connaisse leurs colonnes sans que l'utilisateur ait à déplier la sidebar.
   *
   * Borné volontairement : une tentative au plus par nom et par visite (jusqu'au prochain
   * rafraîchissement du catalogue), et seulement pour un nom que le catalogue connaît — sinon
   * chaque frappe dans le FROM déclencherait une requête sur un nom incomplet.
   */
  /** Plage à sélectionner au prochain rendu — voir `openSelectFor`. */
  const pendingSinkSelectionRef = useRef<{ start: number; end: number } | null>(null);
  const schemaFetchAttempted = useRef<Set<string>>(new Set());
  useEffect(() => {
    const handle = setTimeout(() => {
      const catalog = schemaRef.current;
      if (!catalog) return;
      const known = [...catalog.tables, ...catalog.topics];
      for (const cited of resolveScope(sql, known)) {
        const table = toTableName(cited);
        if (tableSchemasRef.current[table] || schemaFetchAttempted.current.has(table)) continue;
        if (!known.some(k => toTableName(k) === table)) continue;
        schemaFetchAttempted.current.add(table);
        axios.get(`/api/query/schema/${encodeURIComponent(table)}`)
          .then(r => setTableSchemas(prev => ({ ...prev, [table]: r.data })))
          .catch(() => { /* pas encore enregistrée côté Flink — l'autocomplétion s'en passe */ });
      }
    }, 500);
    return () => clearTimeout(handle);
  }, [sql]);

  const toggleTable = async (tableName: string) => {
    const isExpanded = !!expandedTables[tableName];
    setExpandedTables(prev => ({ ...prev, [tableName]: !isExpanded }));
    if (!isExpanded && !tableSchemas[tableName]) {
      // `encodeURIComponent`, comme l'autre appel au même endpoint : un nom de table qui n'est pas
      // sûr dans un chemin d'URL partait tel quel depuis ici seulement.
      try { const r = await axios.get(`/api/query/schema/${encodeURIComponent(tableName)}`); setTableSchemas(prev => ({ ...prev, [tableName]: r.data })); }
      catch { toast(`Failed to load schema for ${tableName}`, 'error'); }
    }
  };

  /**
   * Supprime une table du catalogue Flink, et cesse de la conserver.
   *
   * Les `CREATE TABLE` écrits ici sont rejoués au démarrage : redémarrer n'est donc plus le moyen
   * de vider le catalogue, et il faut rendre cette issue autrement. On demande confirmation — la
   * définition n'existe nulle part ailleurs après ça — et on dit ce que le serveur répond plutôt
   * que d'annoncer une suppression sur la foi d'un 200 : « il n'y avait rien de ce nom » est une
   * réponse possible à une requête parfaitement formée.
   */
  const dropTable = async (tableName: string) => {
    const ok = await confirm({
      title: `Drop ${tableName}?`,
      description:
        'The table is removed from Flink and will not be restored at startup. A table that was '
        + 'auto-registered from a Kafka topic comes back on the next query that names it; a '
        + 'definition written here does not — the statement exists nowhere else.',
      confirmLabel: 'Drop table',
      tone: 'danger',
      icon: 'warning',
    });
    if (!ok) return;
    try {
      const res = await axios.delete<{ dropped: boolean; message: string }>(
        `/api/query/table/${encodeURIComponent(tableName)}`);
      toast(res.data.message, res.data.dropped ? 'success' : 'info');
      setTableSchemas(prev => {
        const next = { ...prev };
        delete next[tableName];
        return next;
      });
      void fetchSchema();
    } catch (err) {
      toast(describeApiError(err).title, 'error');
    }
  };

  /**
   * Ce qu'un geste d'exécution va envoyer, décidé par `planRun`.
   *
   * La sélection n'est consultée que pour la portée `cursor` : « Run all » dit « toutes les
   * instructions de cet onglet », et le raccourci doit faire ce que le bouton annonce.
   */
  const planFor = (scope: 'cursor' | 'all'): PlannedStatement[] => {
    const ed = editorRef.current;
    const selection = scope === 'cursor' ? ed?.getSelection() : undefined;
    const selected = selection && !selection.isEmpty()
      ? ed?.getModel()?.getValueInRange(selection) ?? ''
      : '';
    const range = selection && selected.trim()
      ? { start: offsetAt(sql, selection.startLineNumber, selection.startColumn), text: selected }
      : null;
    return planRun(sql, cursorOffset, range, scope);
  };

  /**
   * Exécute ce que le curseur ou la sélection désigne.
   *
   * Que faut-il exécuter ?
   *
   * 1. La sélection quand il y en a une — habitude universelle des éditeurs SQL, et le moyen de
   *    forcer n'importe quel fragment. Quand elle enjambe plusieurs instructions, elle en produit
   *    plusieurs : sélectionner deux requêtes et les lancer est un geste ordinaire, et cela partait
   *    auparavant en une seule requête que le planner rejetait sur le `;`.
   * 2. Sinon **l'instruction sous le curseur**. Un onglet peut en contenir plusieurs, séparées par
   *    `;` : le formateur les met en forme et l'éditeur les accepte, mais Run envoyait le texte
   *    entier et le backend classait sur le premier mot.
   * 3. Sur un document d'une seule instruction — le cas courant — les deux reviennent au même.
   *
   * L'onglet garde son contenu entier dans tous les cas ; seule la portion envoyée change, et
   * `runOrigin` reporte les positions d'erreur du moteur dans le repère du document.
   */
  const runQuery = () => runBatch(planFor('cursor'));

  /**
   * Exécute **une** instruction : gardes de mode, pré-vol, envoi, résultat.
   *
   * Sortie de `runQuery` pour que « Run all » la réutilise telle quelle — un exécuteur séquentiel
   * qui recopierait ces gardes finirait par en diverger, et c'est précisément sur elles que se joue
   * ce qui part au moteur. Elle rend ce que l'instruction a donné, et non un booléen : un lot doit
   * pouvoir distinguer un échec d'un arrêt demandé, et garder de chaque instruction autre chose que
   * le fait qu'elle a abouti.
   */
  const executeStatement = async (sqlToRun: string): Promise<StatementOutcome> => {
    const statementType = detectStatementType(sqlToRun);

    /** Refus avant exécution : rien n'est parti au moteur, et le panneau porte la raison. */
    const refuse = (error: QueryErrorInfo): StatementOutcome => {
      setResults(null); setSubmittedJob(null); setPanelError(error);
      return { status: 'failed', ms: 0, result: null, job: null, error };
    };

    if (executionMode === 'SYNC_READ' && statementType === 'INSERT') {
      return refuse({
        title: 'INSERT INTO cannot run in Read mode',
        hint: 'Switch the execution mode to Flink Job — Read mode returns rows, so it only accepts SELECT, EXPLAIN and CREATE TABLE.',
        raw: 'INSERT INTO must be submitted in Flink Job mode.',
      });
    }
    if (executionMode === 'ASYNC_JOB' && statementType !== 'INSERT') {
      return refuse({
        title: 'Flink Job mode only accepts INSERT INTO',
        hint: `This statement is ${statementType || 'not an INSERT'}. Switch back to Read mode to run it and see the rows.`,
        raw: 'Flink Job mode only accepts INSERT INTO statements.',
      });
    }

    /*
     * Pré-vol : `/api/query/validate` refuse une faute de syntaxe avant que la requête n'ouvre
     * le moindre consommateur Kafka. Ce contrôle n'est pas gratuit — il exécute `explainSql`,
     * donc une passe complète du planner Flink sous le verrou de lecture du runtime, avant
     * l'exécution qui en fera une seconde. On ne le repaie donc pas pour un SQL déjà validé tel
     * quel : relancer la même requête après avoir changé le plafond de lignes ou l'offset est le
     * geste le plus courant de cet écran, et il n'a aucune syntaxe nouvelle à vérifier.
     */
    if (validatedSqlRef.current !== sqlToRun) {
      try {
        const vRes = await axios.post<SqlValidationResponse>('/api/query/validate', { sql: sqlToRun });
        if (!vRes.data.valid) {
          // Rejet avant exécution : le backend renvoie déjà le texte du parser (avec sa
          // ligne/colonne quand il en a une), on le classe comme n'importe quelle erreur.
          validatedSqlRef.current = null;
          return refuse(describeQueryError(vRes.data.error ?? 'SQL validation failed'));
        }
        validatedSqlRef.current = sqlToRun;
      } catch { /* let execution handle it */ }
    }

    // L'identifiant et le contrôleur sont créés *avant* de passer l'écran en « exécution ».
    // Dans l'autre ordre, une exception ici laissait `executing` à true sans jamais
    // atteindre le `finally` : la requête n'aboutissait pas et Stop n'avait ni contrôleur
    // ni id à utiliser. C'est exactement ce que faisait `crypto.randomUUID()` sur une
    // origine non sécurisée (http://hôte), où la méthode n'existe pas.
    const start = Date.now();
    const queryId = randomId();
    const controller = new AbortController();
    runningQueryIdRef.current = queryId;
    abortRef.current = controller;
    executingRef.current = true;
    setExecuting(true); setResults(null); setSubmittedJob(null); setSortCol(null); setDetailIndex(null);
    // Ce qui a réellement été exécuté — c'est lui, et non le contenu courant de l'onglet, qui dit
    // si les lignes affichées répondent encore au texte sous les yeux.
    setRanSql(sqlToRun);
    try {
      if (executionMode === 'ASYNC_JOB') {
        const response = await axios.post<FlinkJobSubmission>('/api/query/jobs', { sql: sqlToRun },
          { signal: controller.signal, timeout: REQUEST_TIMEOUT_MS });
        const ms = Date.now() - start;
        setExecutionMs(ms);
        setSubmittedJob(response.data);
        setResults(null);
        saveToHistory({ sql: sqlToRun, ts: Date.now(), ms, ok: true, engine: 'FLINK JOB' });
        toast(`Streaming job submitted: ${response.data.status}`, 'success');
        return { status: 'ok', ms, result: null, job: response.data, error: null };
      } else {
        const readMode = offsetMode === 'LATEST' ? 'latest-offset' : 'earliest-offset';
        const limit = maxRows;
        const response = await axios.post<QueryResult>('/api/query/run-sync',
          { sql: sqlToRun, readMode, maxRows: limit, queryId },
          { signal: controller.signal, timeout: REQUEST_TIMEOUT_MS });
        const ms = Date.now() - start;
        setExecutionMs(ms);
        setResultLimit(limit);
        setResults(response.data);
        // Une requête qui a échoué entre aussi dans l'historique : c'est très souvent celle-là
        // que l'on veut rouvrir pour la corriger, et elle n'y était pas.
        saveToHistory({
          sql: sqlToRun,
          ts: Date.now(),
          ms,
          ok: !response.data.error,
          rows: response.data.error ? undefined : response.data.rows.length,
          // `?? undefined` : le serveur peut renvoyer `engine: null` (chemins d'erreur), ce que
          // l'interface locale — qui déclarait `engine?: string` — ne disait pas. Une entrée
          // d'historique portant `null` s'afficherait « null » au lieu de ne rien afficher.
          engine: response.data.error ? undefined : (response.data.engine ?? undefined),
        });
        const failed = !!response.data.error;
        if (!failed) {
          // Refresh the schema browser when:
          // 1. The user explicitly ran a CREATE TABLE statement.
          // 2. The backend auto-registered a Flink table during query execution.
          const strippedForCheck = sqlToRun.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim();
          if (strippedForCheck.toUpperCase().startsWith('CREATE TABLE') || response.data.tableRegistered) {
            fetchSchema();
          }
        }
        return {
          status: failed ? 'failed' : 'ok',
          ms,
          // Pas de compte de lignes sur un échec : « 0 ligne » se lit comme une réponse.
          rows: failed ? undefined : response.data.rows.length,
          engine: failed ? undefined : (response.data.engine ?? undefined),
          limit,
          result: response.data,
          job: null,
          error: failed ? describeQueryError(response.data.error ?? 'Query failed') : null,
        };
      }
    } catch (error) {
      const ms = Date.now() - start;
      setExecutionMs(ms);
      // Une annulation demandée par l'utilisateur n'est pas un échec : pas de panneau rouge, et
      // rien dans l'historique — on n'a appris rien de la requête, seulement qu'on l'a arrêtée.
      if (axios.isCancel(error)) {
        setResults(null);
        setSubmittedJob(null);
        return { status: 'cancelled', ms, result: null, job: null, error: null };
      }
      saveToHistory({ sql: sqlToRun, ts: Date.now(), ms, ok: false });
      // describeApiError couvre aussi ce que le corps de réponse ne dit pas : backend
      // injoignable, requête interrompue. Sans lui, une panne de transport s'affichait
      // « Query execution failed », qui n'apprend rien.
      const info = describeApiError(error, 'Query execution failed');
      setResults(null);
      setSubmittedJob(null);
      setPanelError(info);
      toast(info.title, 'error');
      return { status: 'failed', ms, result: null, job: null, error: info };
    } finally {
      setExecuting(false);
      executingRef.current = false;
      abortRef.current = null;
      runningQueryIdRef.current = null;
    }
  };

  /**
   * Exécute une suite d'instructions, dans l'ordre, en s'arrêtant à la première qui échoue.
   *
   * Depuis que Run vise l'instruction sous le curseur, un onglet qui contient `CREATE TABLE …;`
   * puis `SELECT …;` doit être lancé morceau par morceau. Avant, Run envoyait tout le texte — mais
   * le backend classait sur le premier mot, donc le cas n'a jamais été servi non plus. Or créer une
   * table puis la lire est exactement ce que le flux d'auto-enregistrement encourage.
   *
   * L'arrêt à la première erreur est délibéré : les instructions d'un même onglet se suivent
   * généralement parce que la suivante dépend de la précédente, et enchaîner sur une table qui n'a
   * pas été créée produirait une seconde erreur qui masquerait la première. Les instructions jamais
   * atteintes sont marquées comme telles — `skipped`, et non « réussi sans lignes ».
   *
   * Chaque instruction garde ce qu'elle a donné : le lot n'affichait que le résultat de la
   * **dernière**, si bien que lancer cinq requêtes laissait une grille et aucune trace des quatre
   * autres — ni lignes, ni durée, ni moteur, ni la preuve qu'elles avaient tourné.
   */
  const runBatch = async (plan: PlannedStatement[]) => {
    /*
     * Le bouton Run se désactive pendant l'exécution, mais ⌘↵ appelle cette fonction *directement*
     * : deux frappes rapides lançaient deux requêtes. La seconde écrasait `abortRef` et
     * `runningQueryIdRef`, si bien que Stop n'annulait plus la première, dont le `finally`
     * repassait ensuite l'écran en « Complete » alors qu'une requête était toujours en vol.
     *
     * `batchRef` couvre le même trou pendant un lot : entre deux instructions, aucune requête n'est
     * en vol, donc `executingRef` est faux — une frappe tombant dans cet intervalle lançait une
     * requête *concurrente* de la boucle, les deux écrivant le même résultat affiché.
     */
    if (executingRef.current || batchRef.current) {
      toast('A query is already running — stop it first', 'info');
      return;
    }
    setPanelError(null);
    if (!plan.length) {
      // Envoyer une chaîne vide pour que le moteur dise lui-même qu'elle est vide, c'est faire
      // dire à une erreur de planner ce qu'on savait avant de partir.
      setPanelError({
        title: 'Nothing to run',
        hint: 'This tab holds no statement — only blank space or comments.',
        raw: 'No statement to execute.',
      });
      return;
    }
    // Une instruction n'est pas un lot : pas de liste au-dessus de la grille pour un résultat
    // unique, et celle d'une exécution précédente s'en va avec lui.
    if (plan.length === 1) {
      setBatch(null);
      setSelectedRun(null);
      await executeStatement(plan[0].text);
      return;
    }
    batchRef.current = true;
    batchCancelRef.current = false;
    let runs: Run[] = plan.map((p, i) => ({
      index: i + 1,
      sql: p.text,
      kind: detectStatementType(p.text),
      status: 'pending',
    }));
    // Le lot est posé *avant* de commencer : on voit ce qui va tourner, pas seulement ce qui a
    // tourné, et la barre de progression a de quoi se remplir.
    setBatch(runs);
    setSelectedRun(0);
    try {
      for (let i = 0; i < runs.length; i += 1) {
        runs = runs.map((r, j) => (j === i ? { ...r, status: 'running' as const } : r));
        setBatch(runs);
        setSelectedRun(i);
        const outcome = await executeStatement(runs[i].sql);
        runs = forgetOldestResults(runs.map((r, j) => (j === i ? { ...r, ...outcome } : r)));
        setBatch(runs);

        // Stop pressé *entre* deux instructions n'annulait rien : la requête en vol était déjà
        // terminée, et la boucle enchaînait sur la suivante. L'arrêt est donc un drapeau que la
        // boucle relit, et pas seulement un `abort()` sur une requête HTTP.
        const stopped = outcome.status === 'cancelled' || batchCancelRef.current;
        if (stopped || outcome.status !== 'ok') {
          const remaining = runs.length - i - 1;
          runs = runs.map((r, j) => (j > i && r.status === 'pending' ? { ...r, status: 'skipped' as const } : r));
          setBatch(runs);
          const tail = remaining ? ` — ${remaining} never run` : '';
          // Un arrêt demandé n'est pas un échec : il était annoncé en rouge comme une erreur.
          toast(stopped
            ? `Stopped after statement ${i + 1} of ${runs.length}${tail}`
            : `Failed at statement ${i + 1} of ${runs.length}${tail}`, stopped ? 'info' : 'error');
          return;
        }
      }
      toast(`Ran ${runs.length} statements`, 'success');
    } finally {
      batchRef.current = false;
      batchCancelRef.current = false;
    }
  };

  /** Exécute toutes les instructions de l'onglet. */
  const runAllStatements = () => runBatch(planFor('all'));

  /**
   * Réaffiche ce qu'une instruction du lot a donné. Le résultat lui-même est ce qui a été conservé
   * — y compris son plafond de lignes, sans quoi le badge « limit reached » jugerait un résultat
   * ancien à l'aune du sélecteur tel qu'il est réglé maintenant.
   */
  const selectRun = useCallback((index: number) => {
    const run = batch?.[index];
    if (!run) return;
    setSelectedRun(index);
    setSortCol(null);
    setDetailIndex(null);
    setResults(run.result ?? null);
    setSubmittedJob(run.job ?? null);
    setPanelError(run.error ?? null);
    setExecutionMs(run.ms ?? null);
    setRanSql(run.sql);
    if (typeof run.limit === 'number') setResultLimit(run.limit);
  }, [batch]);

  /** Relance une instruction du lot, à sa place — la puce reprend ce qu'elle vient de donner. */
  const rerunStatement = async (index: number) => {
    const run = batch?.[index];
    if (!run) return;
    if (executingRef.current || batchRef.current) {
      toast('A query is already running — stop it first', 'info');
      return;
    }
    setPanelError(null);
    setSelectedRun(index);
    setBatch(prev => prev && prev.map((r, j) => (j === index ? { ...r, status: 'running' as const } : r)));
    const outcome = await executeStatement(run.sql);
    setBatch(prev => prev && forgetOldestResults(
      prev.map((r, j) => (j === index ? { ...r, ...outcome, forgotten: false } : r))));
  };

  /** Lien rejouable vers la requête de l'onglet courant. */
  const copyQueryLink = useCallback(() => {
    const link = buildQueryLink(`${window.location.origin}${location.pathname}`, sql);
    if (!link) { toast('Nothing to link — the tab is empty', 'info'); return; }
    void copyText(link).then(ok =>
      toast(ok ? 'Link copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error'));
  }, [sql, location.pathname, toast]);

  // Idem : la ref se met à jour dans un effet, pas au milieu du rendu.
  useEffect(() => { runQueryRef.current = runQuery; runAllRef.current = () => void runAllStatements(); });

  /**
   * Arrête la requête en cours. Deux effets distincts, et seul le premier est garanti :
   * l'abandon de la requête HTTP rend la main immédiatement, quel que soit le moteur ;
   * l'appel au backend annule en plus le job Flink quand il y en a un (le lecteur Kafka
   * direct, lui, n'a pas de job à annuler et terminera son fetch en cours côté serveur).
   */
  const cancelRunningQuery = useCallback(async () => {
    const queryId = runningQueryIdRef.current;
    const controller = abortRef.current;
    controller?.abort();
    // Un lot ne s'arrête pas en abandonnant une requête HTTP : entre deux instructions il n'y en a
    // aucune en vol, et la boucle enchaînait. Le drapeau est ce qu'elle relit après chaque
    // instruction — Stop arrête donc le lot, et pas seulement l'instruction en cours.
    batchCancelRef.current = batchRef.current;

    // Ne confirmer que ce qui a réellement eu lieu. Sans contrôleur ni id, ce bouton n'a
    // rien annulé du tout, et annoncer « Query cancelled » là-dessus est précisément ce qui
    // fait décrire le symptôme comme « le bouton est inactif » plutôt que « l'écran est
    // resté bloqué » — le message détournait le diagnostic. On remet aussi l'écran dans un
    // état cohérent, sans quoi il reste en « exécution » indéfiniment.
    if (!controller && !queryId) {
      // Rien en vol, mais un lot en cours : c'est le drapeau qui l'arrête, à l'instruction
      // suivante. Répondre « No query was running » ici décrirait mal ce qui vient de se passer.
      if (batchRef.current) { toast('Stopping the batch after this statement', 'info'); return; }
      setExecuting(false);
      toast('No query was running', 'info');
      return;
    }
    if (!queryId) { toast('Query cancelled', 'info'); return; }

    /*
     * Le serveur dit maintenant ce que l'annulation a obtenu, et les deux cas ne veulent pas dire
     * la même chose : un scan `KAFKA_DIRECT` n'a **aucun** job Flink par construction, donc
     * « NO_ACTIVE_JOB » y est le cas normal, pas l'anomalie — la requête HTTP est bien abandonnée,
     * mais le fetch en cours se termine côté serveur. Annoncer « annulée » dans les deux cas
     * promettait plus que ce qui s'était produit.
     */
    try {
      const res = await axios.post<QueryCancelResponse>(
        `/api/query/cancel/${encodeURIComponent(queryId)}`);
      toast(res.data.cancelled
        ? 'Flink job cancelled'
        : 'Request aborted — no Flink job to cancel, the server finishes its in-flight read', 'info');
    } catch {
      // L'abandon HTTP a bien eu lieu : c'est la seule chose garantie, et la seule à annoncer.
      toast('Request aborted — the server could not be reached to cancel the job', 'info');
    }
  }, [toast]);

  const handleSortColumn = useCallback((col: string) => {
    if (sortCol === col) setSortDir(d => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortCol(col); setSortDir('asc'); }
    // Après un tri, l'index ouvert désigne une autre ligne : le panneau se referme plutôt que de
    // montrer silencieusement autre chose que ce qu'on avait choisi.
    setDetailIndex(null);
  }, [sortCol]);

  const openRowDetail = useCallback((index: number, column: string) => {
    setDetailIndex(index);
    setDetailColumn(column);
  }, []);

  const stepRowDetail = useCallback((delta: number) => {
    setDetailIndex(prev => (prev === null ? prev : clamp(prev + delta, 0, sortedRows.length - 1)));
  }, [sortedRows.length]);

  // Le panneau de détail se ferme sur Escape, comme toute surface superposée de l'application.
  useEffect(() => {
    if (detailIndex === null) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDetailIndex(null); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [detailIndex]);

  /** La ligne ouverte, ou `null` si l'index ne désigne plus rien (nouveau résultat, plus court). */
  const detailRow = detailIndex !== null ? sortedRows[detailIndex] ?? null : null;

  const copyCell = useCallback((value: unknown) => {
    // Un NULL se copie comme la chaîne vide : c'est la valeur, pas son rendu, que l'on emporte.
    const text = value === null || value === undefined
      ? ''
      : typeof value === 'object' ? JSON.stringify(value) : String(value);
    void copyText(text).then(ok =>
      toast(ok ? 'Copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error'));
  }, [toast]);

  /**
   * Exporte **ce qui est affiché**, tri compris. L'export partait de `results.rows` pendant que la
   * grille rendait `sortedRows` : trier une colonne puis exporter donnait un fichier dans un autre
   * ordre que l'écran, sans que rien ne le signale.
   */
  const exportResults = (format: 'csv' | 'json') => {
    if (!results?.rows.length) return;
    const [content, mime, ext] = format === 'json'
      ? [toJson(sortedRows), 'application/json', 'json']
      : [toCsv(results.columns, sortedRows), 'text/csv', 'csv'];
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    // Un nom horodaté : trois exports successifs se retrouvaient sous « query-results (2).csv ».
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    a.href = url; a.download = `${activeTab.name.replace(/[^\w.-]+/g, '_')}-${stamp}.${ext}`; a.click();
    URL.revokeObjectURL(url);
    toast(`Exported ${sortedRows.length.toLocaleString()} rows as ${ext.toUpperCase()}`, 'success');
  };

  /**
   * Pose du SQL dans l'espace de travail sans jamais détruire ce qui s'y trouve.
   *
   * La barre latérale, l'historique et l'aperçu DDL appelaient tous `updateSql(...)`, qui remplace
   * *tout l'onglet actif*. Cliquer un topic pour voir sa forme effaçait donc la requête en cours
   * d'écriture, sans confirmation ni annulation — le défaut même qui avait été corrigé sur
   * l'assistant de fenêtrage, et laissé partout ailleurs. Un onglet vide est rempli, un onglet
   * qui contient quelque chose reste intact et le SQL part dans un nouvel onglet.
   */
  const openSql = useCallback((sqlText: string, name?: string) => {
    if (!activeTab.sql.trim()) {
      updateSql(sqlText);
      if (name) setTabs(prev => prev.map(t => t.id === activeTabId ? { ...t, name } : t));
      return 'current' as const;
    }
    addTab(sqlText, name);
    return 'new' as const;
  }, [activeTab.sql, activeTabId, addTab, updateSql]);

  /**
   * Le raccourci de la barre latérale, sans écraser l'onglet en cours.
   *
   * Le SQL posé suit le mode d'exécution (`sidebarSqlFor`) : en mode Job, un `SELECT` était refusé
   * par la garde de mode, donc cliquer un topic n'y menait qu'à un panneau d'erreur.
   *
   * En mode Job le schéma est chargé au besoin — le même appel que déclenche le dépliage d'une
   * table dans la barre latérale — parce que c'est lui qui permet de lister les colonnes plutôt
   * que d'écrire `SELECT *`, lequel emporte `proc_time` et fait échouer l'INSERT sur l'arité. Une
   * table que Flink ne connaît pas encore rend un schéma vide : on retombe alors sur `SELECT *`,
   * en le disant dans le SQL généré.
   */
  const openSelectFor = useCallback(async (table: string) => {
    let schema = tableSchemasRef.current[table];
    if (executionMode === 'ASYNC_JOB' && !schema) {
      try {
        const r = await axios.get<Record<string, string>>(`/api/query/schema/${encodeURIComponent(table)}`);
        schema = r.data;
        if (schema && Object.keys(schema).length > 0) setTableSchemas(prev => ({ ...prev, [table]: r.data }));
      } catch { /* pas encore enregistrée côté Flink — le SQL généré le dit et reste utilisable */ }
    }
    // Une cible prise dans le catalogue résout, là où `<source>_out` ne peut qu'échouer.
    const sink = executionMode === 'ASYNC_JOB'
      ? pickSinkTable(table, schemaRef.current?.tables, internalPrefixRef.current) : null;
    const sqlText = sidebarSqlFor(table, executionMode, maxRows, schema, sink);
    const where = openSql(sqlText, table);
    // La sélection ne peut être posée qu'une fois le nouveau texte rendu : l'effet ci-dessous s'en
    // charge, sur le `sql` qui vient d'être écrit.
    pendingSinkSelectionRef.current = sinkNameRange(sqlText);
    if (where === 'new') toast(`Opened ${table} in a new tab`, 'success');
  }, [openSql, maxRows, executionMode, toast]);

  /**
   * Pose la sélection sur le nom de sink du dernier INSERT généré, pour que la première frappe le
   * remplace. `setSelection` accepte un `IRange` littéral, donc rien ici ne dépend de l'instance
   * Monaco.
   */
  useEffect(() => {
    const pending = pendingSinkSelectionRef.current;
    if (!pending) return;
    pendingSinkSelectionRef.current = null;
    const ed = editorRef.current;
    const model = ed?.getModel();
    if (!ed || !model) return;
    const from = model.getPositionAt(pending.start);
    const to = model.getPositionAt(pending.end);
    ed.setSelection({
      startLineNumber: from.lineNumber, startColumn: from.column,
      endLineNumber: to.lineNumber, endColumn: to.column,
    });
    ed.focus();
  }, [sql]);

  /**
   * Remplace *tout* le texte de l'onglet actif — utilisé par l'assistant de fenêtrage.
   *
   * Le remplacement passe par l'API d'édition de Monaco sur la plage complète du modèle, et non
   * par `updateSql` : écrire la valeur depuis React appelle `model.setValue()`, qui vide la pile
   * d'annulation, donc le texte écrasé serait perdu sans recours. Passé par `executeEdits`, il
   * reste à un ⌘Z. `updateSql` n'est la voie de repli que lorsqu'il n'y a pas d'éditeur monté.
   */
  const replaceSql = useCallback((text: string) => {
    const ed = editorRef.current;
    const model = ed?.getModel();
    if (!ed || !model) {
      updateSql(text);
      return 'replaced' as const;
    }
    const had = model.getValue().trim().length > 0;
    ed.executeEdits('kse-replace', [{ range: model.getFullModelRange(), text }]);
    // Le curseur en fin de texte : c'est là que la suite s'écrit, et ça évite de laisser une
    // sélection couvrant tout ce qui vient d'être posé.
    ed.setPosition(model.getPositionAt(text.length));
    ed.revealPositionInCenterIfOutsideViewport(model.getPositionAt(text.length));
    ed.focus();
    return had ? ('replaced' as const) : ('filled' as const);
  }, [updateSql]);

  /*
   * Les propositions suivent le mode, comme le raccourci de la barre latérale : en mode Job elles
   * étaient purement absentes, alors que c'est le mode où la forme attendue — un INSERT INTO, et
   * lui seul — est la moins évidente.
   */
  const starters = useMemo(
    () => (executionMode === 'ASYNC_JOB'
      ? starterJobQueries(schema, tableSchemas, internalPrefix)
      : starterQueries(schema, internalPrefix)),
    [schema, executionMode, tableSchemas, internalPrefix],
  );

  /**
   * Table visée par l'assistant : celle que la requête cite, sinon la première du catalogue.
   *
   * **Sous sa forme table Flink**, et c'est la correction d'un bug : `resolveScope` résout un nom
   * cité vers la clé du *catalogue*, qui pour un topic est le nom pointé (`demo.orders.1.received`).
   * L'assistant l'écrivait tel quel dans le SQL, alors que la table est enregistrée sous
   * `demo_orders_1_received` — `toTableName` remplace points et tirets par des underscores. Flink
   * lisait donc `demo.orders.1.received` comme un identifiant en quatre parties
   * (catalogue.base.table…) et ne résolvait rien. C'est la règle que la barre latérale applique
   * déjà quand elle pose `SELECT * FROM` sur un topic, et que le schéma consulté juste en dessous
   * appliquait aussi — seul le nom écrit dans la requête restait brut. Le panneau affiche cette
   * même forme, donc il nomme désormais la table que la requête ira réellement chercher.
   */
  const windowTable = useMemo(
    () => resolveScopeAsTables(sql, [...(schema?.tables ?? []), ...(schema?.topics ?? [])])[0]
      ?? toTableName(schema?.tables[0] ?? 'source_table'),
    [sql, schema],
  );


  /**
   * Pose le SQL généré par l'assistant à la place de *tout* le contenu de l'onglet actif.
   *
   * L'assistant insérait auparavant au curseur, pour ne pas détruire le travail en cours ; ce
   * qu'il produit est une requête entière et non un fragment, donc l'insertion laissait presque
   * toujours deux requêtes collées à corriger à la main. Le remplacement est annulable (voir
   * `replaceSql`), et le message le dit plutôt que de laisser la découverte à l'utilisateur.
   *
   * Un onglet qui contient déjà du SQL demande confirmation, comme partout ailleurs dans
   * l'application (`useConfirm`) : l'annulation Monaco rattrape la perte, mais elle ne se
   * découvre qu'après coup, et un clic ne doit pas effacer une requête en cours d'écriture sans
   * le dire. Un onglet vide n'a rien à perdre et ne demande rien.
   */
  const applyWindowLogic = useCallback(async (generated: string) => {
    if (sql.trim()) {
      const ok = await confirm({
        title: 'Replace the editor content?',
        description: <>The window query replaces everything in “{activeTab.name}”. The overwritten
          SQL stays one undo (⌘Z / Ctrl+Z) away in the editor.</>,
        confirmLabel: 'Replace',
        tone: 'danger',
        icon: 'bolt',
      });
      if (!ok) return;
    }
    const where = replaceSql(generated);
    toast({
      filled: 'Window query written to the editor',
      replaced: 'Window query replaced the editor content — ⌘Z / Ctrl+Z to undo',
    }[where], 'success');
  }, [sql, activeTab.name, confirm, replaceSql, toast]);


  // ── Render ────────────────────────────────────────────────────────────────────
  return (
    /*
     * Colonne, pour que le bandeau « fenêtre trop étroite » ait une place au-dessus de la scène
     * sans rien pousser hors de l'écran : la ligne éditeur/résultats garde `flex-1 min-h-0`, donc
     * elle continue de se partager exactement la hauteur restante.
     */
    <div className="flex flex-col h-full overflow-hidden">
      <NarrowWindowNotice />
      <div className="flex flex-1 min-h-0 overflow-hidden">

      {ddlPreviewTopic && (
        <DdlPreviewModal
          topic={ddlPreviewTopic}
          ddl={ddlPreview}
          error={ddlPreviewError}
          loading={ddlPreviewLoading}
          onClose={() => setDdlPreviewTopic(null)}
          onRetry={() => void fetchDdlPreview(ddlPreviewTopic)}
          onCopy={() => { if (ddlPreview) void copyText(ddlPreview).then(ok =>
            toast(ok ? 'DDL copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error')); }}
          onInsert={() => {
            if (!ddlPreview) return;
            const where = openSql(ddlPreview, `DDL ${ddlPreviewTopic}`);
            setDdlPreviewTopic(null);
            toast(where === 'new' ? 'DDL opened in a new tab' : 'DDL inserted in editor', 'success');
          }}
        />
      )}

      <SchemaBrowser
        ref={asideRef}
        schema={schema}
        schemaLoading={schemaLoading}
        onRefresh={fetchSchema}
        width={sidebarWidth}
        widthMin={SIDEBAR_MIN}
        widthMax={SIDEBAR_MAX}
        onResizeStart={e => startDrag('sidebar', 'col-resize', e)}
        onResizeKey={e => {
          if (e.key === 'ArrowLeft') { setSidebarWidth(w => w - 16); e.preventDefault(); }
          if (e.key === 'ArrowRight') { setSidebarWidth(w => w + 16); e.preventDefault(); }
          if (e.key === 'Home') { setSidebarWidth(DEFAULT_LAYOUT.sidebarWidth); e.preventDefault(); }
        }}
        actionLabelFor={target => sidebarActionLabel(target, executionMode)}
        expandedTables={expandedTables}
        tableSchemas={tableSchemas}
        onToggleTable={toggleTable}
        onSelectFrom={openSelectFor}
        onDropTable={table => void dropTable(table)}
        onPreviewDdl={fetchDdlPreview}
        savedQueries={savedQueries}
        onLoadSaved={loadSavedQuery}
        onDeleteSaved={id => void deleteSavedQuery(id)}
        saveInputVisible={saveInputVisible}
        saveInputName={saveInputName}
        onSaveInputChange={setSaveInputName}
        onSaveOpen={() => { setSaveInputVisible(true); setSaveInputName(activeTab.name); }}
        onSaveCancel={() => setSaveInputVisible(false)}
        onSaveConfirm={() => void saveQuery()}
        activeTabName={activeTab.name}
      />

      {/* ── Main Content ─────────────────────────────────────────────────────── */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* Toolbar */}
        <header className="h-14 border-b border-outline-variant/60 flex items-center px-4 md:px-6 justify-between gap-4 bg-surface-container-low/40 shrink-0">
          {/* Les réglages de lecture défilent au lieu de disparaître. « Offset » était masqué sous
              `md`, « Rows » sous `lg` — mais ils continuaient de s'appliquer : sur une fenêtre
              étroite, la requête partait en EARLIEST/50 sans qu'aucun des deux ne soit visible,
              ni modifiable. Un réglage actif que personne ne peut voir est le pire des deux
              mondes. */}
          <div className="flex items-center gap-4 min-w-0 overflow-x-auto custom-scrollbar">
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-[12px] text-on-surface-variant hidden sm:inline">Mode</span>
              <Segmented
                ariaLabel="Execution mode"
                value={executionMode}
                onChange={setExecutionMode}
                options={[{ value: 'SYNC_READ', label: 'Sync read' }, { value: 'ASYNC_JOB', label: 'Flink job' }]}
              />
            </div>
            {executionMode === 'SYNC_READ' && (
              <>
                <div className="flex items-center gap-2 shrink-0">
                  <Tooltip content="Which end of each topic the direct reader starts from. Earliest replays history; Latest reads what arrives from now on.">
                    <span tabIndex={0} className="text-[12px] text-on-surface-variant rounded">Offset</span>
                  </Tooltip>
                  <Segmented
                    ariaLabel="Offset mode"
                    value={offsetMode}
                    onChange={setOffsetMode}
                    options={[{ value: 'EARLIEST', label: 'Earliest' }, { value: 'LATEST', label: 'Latest' }]}
                  />
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <label htmlFor="kse-max-rows" className="text-[12px] text-on-surface-variant">Rows</label>
                  <Select
                    id="kse-max-rows"
                    aria-label="Maximum rows to fetch"
                    className="h-7 w-24 text-[12px]"
                    value={String(maxRows)}
                    onChange={e => setMaxRows(Number(e.target.value))}
                  >
                    {ROW_LIMITS.map(n => <option key={n} value={n}>{n.toLocaleString()}</option>)}
                  </Select>
                </div>
              </>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <div className="relative" ref={historyRef}>
              <button onClick={() => setShowHistory(h => !h)} aria-label="Query history"
                aria-expanded={showHistory} aria-haspopup="menu"
                className="inline-flex items-center gap-1.5 h-9 px-3 text-on-surface-variant hover:text-on-surface border border-outline-variant rounded-md hover:bg-surface-container-high transition-colors" title="Query history">
                <span className="material-symbols-outlined text-[18px]">history</span>
                {history.length > 0 && <span className="text-[10px] bg-primary text-on-primary font-semibold px-1.5 rounded-full tabular-nums">{history.length}</span>}
              </button>
              {showHistory && (
                <div role="menu" className="absolute right-0 top-full mt-1.5 w-96 max-h-64 overflow-y-auto bg-surface-container-high border border-outline-variant rounded-xl shadow-2xl z-30 animate-slide-up">
                  <div className="px-3 py-2 border-b border-outline-variant/60 flex items-center justify-between sticky top-0 bg-surface-container-high">
                    <span className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Recent Queries</span>
                    <button onClick={() => { setHistory([]); removeStored(HISTORY_STORAGE_KEY); }} className="text-[11px] text-outline hover:text-error transition-colors">Clear</button>
                  </div>
                  {history.length === 0 ? <p className="p-4 text-[12px] text-outline">No history yet</p>
                    : history.map(h => (
                      // Une entrée d'historique est une requête entière : elle remplaçait tout
                      // l'onglet actif au clic, effaçant sans préavis ce qu'on était en train
                      // d'écrire. Elle suit désormais la même règle que les requêtes sauvegardées.
                      <button key={`${h.ts}-${h.sql}`} role="menuitem"
                        onClick={() => { openSql(h.sql); setShowHistory(false); }}
                        title={h.sql}
                        className="w-full text-left px-3 py-2 hover:bg-primary/10 border-b border-outline-variant/40 last:border-0 transition-colors">
                        <div className="flex items-start gap-2">
                          {/* Une entrée dit maintenant ce que la requête a donné : sans issue,
                              retrouver « celle qui avait marché » demandait de toutes les relire. */}
                          <span aria-hidden="true" className={cn(
                            'material-symbols-outlined text-[14px] mt-0.5 shrink-0',
                            h.ok === false ? 'text-error' : h.ok ? 'text-success' : 'text-outline',
                          )}>{h.ok === false ? 'error' : h.ok ? 'check_circle' : 'schedule'}</span>
                          <div className="min-w-0 flex-1">
                            <p className="font-mono text-[12px] text-on-surface truncate">{h.sql.replace(/\s+/g, ' ')}</p>
                            <p className="text-[11px] text-outline mt-0.5">
                              {new Date(h.ts).toLocaleString()}
                              {describeHistoryEntry(h) && <> · {describeHistoryEntry(h)}</>}
                            </p>
                          </div>
                        </div>
                      </button>
                    ))
                  }
                </div>
              )}
            </div>
            <Tooltip content="Run the query, or just the selection when there is one. Shortcut: ⌘↵ / Ctrl+Enter.">
              <span tabIndex={0} className="text-[11px] text-outline hidden lg:block font-mono rounded">⌘↵</span>
            </Tooltip>
            {(executing || runningStatement >= 0) && (
              <Button variant="secondary" onClick={() => void cancelRunningQuery()} icon="stop_circle">
                Stop
              </Button>
            )}
            {/* Ce que Run va envoyer, dit avant d'appuyer. « Run statement » sans dire laquelle
                serait la moitié inquiétante de la fonctionnalité. */}
            {/* Le mode ne conditionne plus rien ici. En mode Flink Job, Run visait déjà la seule
                instruction sous le curseur : un onglet de trois INSERT en soumettait *un* sans que
                rien ne le dise, et « Run all » n'y était pas offert — une exécution partielle
                silencieuse, exactement ce que ce compteur existe pour empêcher. */}
            {!hasSelection && statements.length > 1 && (
              <>
                <Tooltip content="This tab holds several statements. ⌘↵ runs the one the cursor is in, ⌘⇧↵ runs them all — select a fragment to run something else.">
                  <span tabIndex={0} className="text-[11px] text-on-surface-variant tabular-nums rounded hidden md:block">
                    {runningStatement >= 0 && batch
                      ? `Running ${runningStatement + 1}/${batch.length}`
                      : `Statement ${cursorStatement + 1}/${statements.length}`}
                  </span>
                </Tooltip>
                {/* Créer une table puis la lire est ce que l'auto-enregistrement encourage, et
                    depuis que Run vise une seule instruction, cela demandait deux gestes. */}
                <Tooltip content="Runs every statement in this tab, in order, stopping at the first one that fails. Shortcut: ⌘⇧↵.">
                  <Button variant="secondary" onClick={() => void runAllStatements()}
                    disabled={executing || runningStatement >= 0} icon="playlist_play">
                    Run all
                  </Button>
                </Tooltip>
              </>
            )}
            <Button
              variant="primary"
              onClick={runQuery}
              loading={executing}
              icon={executing ? undefined : 'play_arrow'}
            >
              {executing ? 'Running…'
                : executionMode === 'ASYNC_JOB' ? 'Submit job'
                : hasSelection ? 'Run selection'
                : statements.length > 1 ? 'Run statement' : 'Run query'}
            </Button>
          </div>
        </header>

        {/* Split pane */}
        <div ref={containerRef} className="flex-1 flex flex-col min-h-0 overflow-hidden">

          {/* Editor + Window Assistant */}
          <div className="flex overflow-hidden" style={{ height: `${splitPercent}%` }}>
            <div className="flex-1 flex flex-col min-w-0 bg-surface/40">

              {/* ── Tab bar ── */}
              {/* Une barre d'onglets est un `tablist` : les onglets étaient des `<div onClick>`,
                  donc ni tabulables ni actionnables au clavier, et sans état annoncé. Le motif
                  standard veut un seul arrêt de tabulation, les flèches naviguant à l'intérieur. */}
              <div role="tablist" aria-label="Query tabs"
                className="flex items-center border-b border-outline-variant/60 bg-surface-container-low/60 shrink-0 overflow-x-auto">
                {tabs.map((tab, index) => (
                  <div
                    key={tab.id}
                    className={`group/tab flex items-center gap-1.5 border-r border-outline-variant/40 shrink-0 transition-colors ${tab.id === activeTabId ? 'bg-surface text-on-surface border-b-2 border-b-primary' : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high/50'}`}
                  >
                    {renamingTabId === tab.id ? (
                      <input
                        autoFocus
                        aria-label="Rename tab"
                        value={renamingName}
                        onChange={e => setRenamingName(e.target.value)}
                        onBlur={commitRename}
                        onKeyDown={e => { if (e.key === 'Enter') commitRename(); if (e.key === 'Escape') setRenamingTabId(null); }}
                        className="bg-transparent border-none outline-none text-[12px] font-medium w-24 text-primary mx-3 my-2"
                      />
                    ) : (
                      <Tooltip content="Double-click to rename this tab.">
                        <button
                          type="button"
                          role="tab"
                          id={`kse-tab-${tab.id}`}
                          aria-selected={tab.id === activeTabId}
                          aria-controls="kse-editor-pane"
                          tabIndex={tab.id === activeTabId ? 0 : -1}
                          onClick={() => setActiveTabId(tab.id)}
                          onDoubleClick={e => startRename(tab, e)}
                          onKeyDown={e => {
                            if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
                              const step = e.key === 'ArrowRight' ? 1 : -1;
                              const next = tabs[(index + step + tabs.length) % tabs.length];
                              setActiveTabId(next.id);
                              requestAnimationFrame(() => document.getElementById(`kse-tab-${next.id}`)?.focus());
                              e.preventDefault();
                            }
                            if (e.key === 'F2') { startRename(tab, e as unknown as React.MouseEvent); e.preventDefault(); }
                          }}
                          className="text-[12px] font-medium pl-3 py-2 rounded"
                        >{tab.name}</button>
                      </Tooltip>
                    )}
                    {tabs.length > 1 && (
                      <button type="button" onClick={e => void closeTab(tab.id, e)} aria-label={`Close ${tab.name}`}
                        className="opacity-0 group-hover/tab:opacity-100 focus-visible:opacity-100 text-outline hover:text-on-surface transition-all pr-3 py-2">
                        <span className="material-symbols-outlined text-[14px]">close</span>
                      </button>
                    )}
                  </div>
                ))}
                <button type="button" onClick={() => addTab()} className="px-2.5 py-2 text-outline hover:text-on-surface transition-colors shrink-0" title="New tab" aria-label="New tab">
                  <span className="material-symbols-outlined text-[16px]">add</span>
                </button>
                {/* Le retour arrière est *visible* : un raccourci que personne ne découvre n'est
                    pas une réponse, et ⌘⇧T appartient au navigateur, qui ne le laisse pas passer. */}
                {closedTabs.length > 0 && (
                  <Tooltip content={`Reopen “${closedTabs[0].name}”, closed a moment ago. The last ${CLOSED_TAB_CAP} closed tabs are kept until the page is reloaded.`}>
                    <button type="button" onClick={reopenClosedTab}
                      aria-label={`Reopen ${closedTabs[0].name}`}
                      className="px-2.5 py-2 text-outline hover:text-on-surface transition-colors shrink-0">
                      <span aria-hidden="true" className="material-symbols-outlined text-[16px]">undo</span>
                    </button>
                  </Tooltip>
                )}
                {/* Format + Link pushed to the right */}
                <div className="ml-auto px-3 flex items-center gap-3">
                  {/* L'éditeur acceptait un `?sql=` sans jamais en produire — à rebours de Stream
                      Flow et du Topic Explorer, qui portent tous deux un « Link ». Les requêtes
                      sauvegardées vivant dans un seul navigateur, montrer une requête à quelqu'un
                      passait par un copier-coller. */}
                  <Tooltip content="Copies a link that reopens this query in the editor. The tab's whole content travels in the URL.">
                    <button type="button" onClick={copyQueryLink}
                      className="flex items-center gap-1 text-[12px] text-on-surface-variant hover:text-on-surface transition-colors rounded">
                      <span aria-hidden="true" className="material-symbols-outlined text-[16px]">link</span>
                      Link
                    </button>
                  </Tooltip>
                  <Tooltip content="Reformats the SQL in the editor: one clause per line, one select item per line, keywords upper-cased. String literals and comments are left untouched. Shortcut: Shift + Alt + F.">
                  <button type="button" onClick={() => void editorRef.current?.getAction('editor.action.formatDocument')?.run()}
                    className="flex items-center gap-1 text-[12px] text-on-surface-variant hover:text-on-surface transition-colors rounded">
                    <span className="material-symbols-outlined text-[16px]">auto_fix_high</span>
                    Format
                  </button>
                  </Tooltip>
                </div>
              </div>

              <div className="flex-1 overflow-hidden" id="kse-editor-pane" role="tabpanel"
                aria-labelledby={`kse-tab-${activeTabId}`}>
                <Editor
                  height="100%"
                  defaultLanguage="sql"
                  theme="vs-dark"
                  value={sql}
                  onChange={val => updateSql(val || '')}
                  onMount={(editor, monacoApi) => {
                    editorRef.current = editor;
                    // `onMount` reçoit l'API Monaco en second argument : elle est garantie prête
                    // à cet instant, là où `useMonaco()` peut l'être avant ou après le montage.
                    registerEditorCommands(editor, monacoApi);
                    // Le bouton doit annoncer ce qu'il va exécuter — tout l'onglet, la sélection,
                    // ou l'instruction sous le curseur.
                    editor.onDidChangeCursorSelection(e => {
                      setHasSelection(!e.selection.isEmpty());
                      const model = editor.getModel();
                      if (model) setCursorOffset(model.getOffsetAt(e.selection.getPosition()));
                    });
                  }}
                  options={{
                    fontSize: 14, fontFamily: 'JetBrains Mono', minimap: { enabled: false },
                    padding: { top: 16 }, scrollBeyondLastLine: false, automaticLayout: true,
                    suggestOnTriggerCharacters: true,
                    quickSuggestions: { other: true, comments: false, strings: false },
                  }}
                />
              </div>
            </div>

            <WindowAssistant
              table={windowTable}
              tableSchema={tableSchemas[toTableName(windowTable)]}
              open={assistantOpen}
              onToggle={toggleAssistant}
              onApply={applyWindowLogic}
            />
          </div>

          {/* Drag handle */}
          <div
            role="separator"
            aria-orientation="horizontal"
            aria-label="Resize the editor and results panes"
            aria-valuenow={Math.round(splitPercent)}
            aria-valuemin={SPLIT_MIN}
            aria-valuemax={SPLIT_MAX}
            tabIndex={0}
            onPointerDown={e => startDrag('split', 'row-resize', e)}
            onKeyDown={e => {
              if (e.key === 'ArrowUp') { setSplitPercent(p => p - 4); e.preventDefault(); }
              if (e.key === 'ArrowDown') { setSplitPercent(p => p + 4); e.preventDefault(); }
              if (e.key === 'Home') { setSplitPercent(DEFAULT_LAYOUT.splitPercent); e.preventDefault(); }
            }}
            style={{ touchAction: 'none' }}
            className="h-2 border-y border-outline-variant/60 bg-surface-container-low/60 hover:bg-primary/10 focus-visible:bg-primary/20 cursor-row-resize flex items-center justify-center transition-colors shrink-0 group">
            <div className="w-16 h-0.5 bg-primary/20 group-hover:bg-primary/50 rounded-full transition-colors" />
          </div>

          {/* Results panel */}
          <div className="flex flex-col bg-surface-container-low/40 overflow-hidden" style={{ height: `calc(${100 - splitPercent}% - 0.5rem)` }}>
            <div className="h-10 border-b border-outline-variant/60 flex items-center px-4 justify-between gap-3 bg-surface-container-low/80 shrink-0">
              <div className="flex items-center gap-5 min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[16px] text-on-surface-variant">timer</span>
                  <span className="text-[11px] text-on-surface-variant">Execution <span className="text-on-surface tabular-nums">{executing ? '…' : executionMs !== null ? formatDuration(executionMs) : '—'}</span></span>
                </div>
                <div className="flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[16px] text-on-surface-variant">list_alt</span>
                  <span className="text-[11px] text-on-surface-variant">
                    {/* Tronqué = autant de lignes que le plafond *de cette requête*. La constante
                        locale d'avant ne suivait pas `explorer.default-max-rows` côté serveur. */}
                    Rows <span className={`tabular-nums ${truncated ? 'text-warning' : 'text-on-surface'}`}>
                      {shownRun?.forgotten ? (shownRun.rows ?? 0) : (results?.rows.length ?? 0)}
                    </span>
                    {truncated && (
                      <Tooltip content={`The scan stopped at the ${resultLimit.toLocaleString()}-row limit, so this result is probably incomplete — raise "Rows" to fetch more.`}>
                        <span tabIndex={0} className="ml-1.5 text-[10px] text-warning font-medium rounded">limit reached</span>
                      </Tooltip>
                    )}
                  </span>
                </div>
                {(results?.engine || (executionMode === 'SYNC_READ' && (results || executing))) && (
                  <Tooltip content={
                    (results?.engine ?? 'KAFKA_DIRECT') === 'KAFKA_DIRECT'
                      ? 'Kafka Direct: a bounded scan over Kafka messages. It supports SELECT, WHERE, aggregates and TUMBLE windows — but no multi-topic JOIN, which is the limit worth knowing before reading these rows.'
                      : 'Flink: executed by the embedded Flink SQL engine (EXPLAIN / DDL).'
                  }>
                  <span tabIndex={0} className="rounded">
                    <Badge tone={(results?.engine ?? 'KAFKA_DIRECT') === 'KAFKA_DIRECT' ? 'primary' : 'secondary'}>
                      {results?.engine ?? 'Kafka Direct'}
                    </Badge>
                  </span>
                  </Tooltip>
                )}
              </div>
              <div className="flex items-center gap-3 shrink-0">
                {results && !results.error && results.rows.length > 0 && (
                  <div className="flex items-center gap-1">
                    <button onClick={() => exportResults('csv')} className="flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors"><span className="material-symbols-outlined text-[16px]">download</span>CSV</button>
                    <span className="text-outline">·</span>
                    <button onClick={() => exportResults('json')} className="flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors"><span className="material-symbols-outlined text-[16px]">download</span>JSON</button>
                  </div>
                )}
                {/* La grille gardait les lignes de la requête précédente pendant qu'on écrivait la
                    suivante, sans rien dire. Le bouton relance la requête *affichée*. */}
                {staleResults && !queryError && (
                  <Tooltip content="The SQL in the editor has changed since these rows were fetched. Run again to refresh them.">
                    <button type="button" onClick={runQuery}
                      className="inline-flex items-center gap-1 text-[11px] font-medium text-warning hover:underline rounded">
                      <span aria-hidden="true" className="material-symbols-outlined text-[14px]">sync_problem</span>
                      Stale — rerun
                    </button>
                  </Tooltip>
                )}
                <Badge tone={executing ? 'primary' : (queryError ? 'error' : (results || submittedJob || shownRun?.forgotten) ? 'success' : 'neutral')} dot>
                  {executing ? 'Running' : queryError ? 'Error' : submittedJob ? 'Job submitted'
                    : (results || shownRun?.forgotten) ? 'Complete' : 'Idle'}
                </Badge>
              </div>
            </div>

            {/* ── Le lot : une puce par instruction ──
                « Run all » n'affichait que le résultat de la dernière : les autres n'avaient laissé
                ni lignes, ni durée, ni moteur, ni la preuve qu'elles avaient tourné. Chaque puce
                rappelle ce que son instruction a donné et la ramène dans la grille au clic. */}
            {batch && (
              <div className="border-b border-outline-variant/60 bg-surface-container-low/60 shrink-0">
                <div className="flex items-center justify-between gap-3 px-4 pt-2">
                  <p className="text-[11px] text-on-surface-variant">
                    <span className="font-medium text-on-surface">Batch</span> · {summariseBatch(batch)}
                  </p>
                  <button type="button" onClick={() => { setBatch(null); setSelectedRun(null); }}
                    className="text-[11px] text-outline hover:text-on-surface transition-colors"
                    disabled={runningStatement >= 0}>
                    Dismiss
                  </button>
                </div>
                <div role="group" aria-label="Statements in this run"
                  className="flex items-stretch gap-1.5 px-4 py-2 overflow-x-auto custom-scrollbar">
                  {batch.map((run, i) => (
                    <button
                      key={run.index}
                      type="button"
                      onClick={() => selectRun(i)}
                      // Le nom accessible porte l'issue : l'icône qui la donne à l'œil est
                      // `aria-hidden`, et « Statement 3 » seul ne dit pas si elle a tourné.
                      aria-label={`Statement ${run.index}, ${run.status}`}
                      aria-pressed={selectedRun === i}
                      title={run.sql}
                      className={cn(
                        'shrink-0 max-w-[16rem] text-left px-2.5 py-1.5 rounded-lg border transition-colors',
                        selectedRun === i
                          ? 'border-primary bg-primary/10'
                          : 'border-outline-variant hover:border-primary/50 hover:bg-surface-container-high/60',
                        run.status === 'pending' || run.status === 'skipped' ? 'opacity-60' : '',
                      )}
                    >
                      <span className="flex items-center gap-1.5">
                        <span aria-hidden="true" className={cn(
                          'material-symbols-outlined text-[14px]',
                          run.status === 'ok' ? 'text-success'
                            : run.status === 'failed' ? 'text-error'
                              : run.status === 'running' ? 'text-primary animate-pulse' : 'text-outline',
                        )}>{STATEMENT_ICON[run.status]}</span>
                        <span className="text-[11px] font-medium text-on-surface tabular-nums">{run.index}</span>
                        <span className="text-[10px] text-on-surface-variant uppercase tracking-wide">
                          {run.kind.replace('_', ' ')}
                        </span>
                      </span>
                      <span className="block font-mono text-[10px] text-on-surface-variant truncate mt-0.5">
                        {previewStatement(run.sql, 40)}
                      </span>
                      {/* Rien d'inventé : une instruction jamais atteinte n'affiche pas « 0 ligne ». */}
                      {describeStatementRun(run) && (
                        <span className="block text-[10px] text-outline mt-0.5">{describeStatementRun(run)}</span>
                      )}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Réserves du moteur sur un résultat par ailleurs réussi — typiquement les
                prédicats WHERE que le lecteur direct n'a pas su appliquer. Le backend les
                calcule depuis toujours ; l'UI les jetait, et présentait donc un scan non
                filtré comme un résultat filtré. */}
            {!queryError && !!results?.warnings?.length && (
              <div className="mx-4 mt-3 flex items-start gap-2 px-3 py-2 rounded-lg border border-warning/30 bg-warning/10 shrink-0" role="status">
                <span className="material-symbols-outlined text-warning text-[18px] mt-px shrink-0">warning</span>
                <div className="min-w-0">
                  <p className="text-warning text-[12px] font-semibold">
                    {results.warnings.length === 1 ? 'Engine caveat' : `Engine caveats (${results.warnings.length})`}
                  </p>
                  <ul className="mt-0.5 space-y-0.5">
                    {results.warnings.map((w, i) => (
                      <li key={i} className="text-[11px] text-on-surface-variant leading-relaxed break-words">{w}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}

            {/* La grille et le détail se partagent la hauteur restante : le panneau se pose à côté
                plutôt que par-dessus, pour qu'on garde la ligne dans son contexte. */}
            <div className="flex-1 flex min-h-0 overflow-hidden">
            <div ref={resultsScrollRef} className="flex-1 min-w-0 overflow-auto custom-scrollbar">
              {queryError ? (
                <div className="p-4">
                  <div className="flex items-start gap-3 p-3 rounded-lg border border-error/30 bg-error/10" role="alert">
                    <span className="material-symbols-outlined text-error text-base mt-0.5 shrink-0">error</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-error font-semibold text-sm break-words">{queryError.title}</p>
                      {queryError.hint && (
                        <p className="text-on-surface-variant text-[12px] mt-1 leading-relaxed">{queryError.hint}</p>
                      )}
                      <div className="flex items-center flex-wrap gap-x-4 gap-y-1 mt-2">
                        {queryError.location && (
                          <button
                            onClick={() => jumpToError(queryError.location!)}
                            className="inline-flex items-center gap-1 text-[11px] font-medium text-primary hover:underline"
                          >
                            <span className="material-symbols-outlined text-[14px]">my_location</span>
                            Jump to line {queryError.location.line}:{queryError.location.column}
                          </button>
                        )}
                        <button onClick={() => setShowErrorDetails(s => !s)} className="text-[11px] text-on-surface-variant hover:text-on-surface transition-colors">
                          {showErrorDetails ? '▲ Hide raw error' : '▼ Show raw error'}
                        </button>
                      </div>
                      {showErrorDetails && (
                        <pre className="mt-2 text-[10px] text-on-surface-variant font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-error/20 pt-2">{queryError.raw}</pre>
                      )}
                    </div>
                    <button onClick={() => void copyText(queryError.raw || queryError.title).then(ok =>
                      toast(ok ? 'Error copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error'))}
                      className="text-outline hover:text-on-surface shrink-0 transition-colors" title="Copy error" aria-label="Copy the error message">
                      <span className="material-symbols-outlined text-sm">content_copy</span>
                    </button>
                  </div>
                </div>
              ) : submittedJob ? (
                <div className="p-4">
                  <div className="flex items-start gap-3 p-4 rounded-lg border border-success/30 bg-success/10">
                    <span className="material-symbols-outlined text-success text-base mt-0.5 shrink-0">rocket_launch</span>
                    <div className="flex-1 min-w-0 space-y-2">
                      <div>
                        <p className="text-success font-semibold text-sm">Flink job submitted</p>
                        <p className="text-xs text-on-surface-variant">The SQL was accepted in asynchronous job mode and is now tracked by the dashboard.</p>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-[11px] font-mono text-on-surface">
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Status</p>
                          <p>{submittedJob.status}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Type</p>
                          <p>{submittedJob.statementType.replace('_', ' ')}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Query ID</p>
                          <p className="break-all">{submittedJob.queryId}</p>
                        </div>
                        <div>
                          <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Flink Job ID</p>
                          <p className="break-all">{submittedJob.flinkJobId}</p>
                        </div>
                      </div>
                      <pre className="text-[10px] text-on-surface-variant font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-success/20 pt-2">{submittedJob.sql}</pre>
                    </div>
                  </div>
                </div>
              ) : shownRun?.forgotten ? (
                /* Les lignes de cette instruction ont été libérées pour borner ce que le lot
                   retient. Une grille vide se lirait « aucune ligne » : ce panneau dit ce qui a
                   été mesuré, et propose le seul geste qui les ramène. */
                <div className="p-4">
                  <EmptyState
                    icon="inventory_2"
                    title="These rows are no longer held"
                    description={`Statement ${shownRun.index} returned ${(shownRun.rows ?? 0).toLocaleString()} rows. A batch keeps the most recent ${MAX_RETAINED_BATCH_ROWS.toLocaleString()} rows, so these were released — run it again to see them.`}
                  />
                  <div className="flex justify-center mt-2">
                    <Button variant="secondary" icon="play_arrow" disabled={executing}
                      onClick={() => void rerunStatement(selectedRun ?? 0)}>
                      Run statement {shownRun.index} again
                    </Button>
                  </div>
                </div>
              ) : results?.columns.length ? (
                <ResultsGrid
                  columns={results.columns}
                  rows={visibleRows}
                  virtualized={virtualized}
                  window={gridWindow}
                  sortCol={sortCol}
                  sortDir={sortDir}
                  onSort={handleSortColumn}
                  onOpenRow={openRowDetail}
                  selectedIndex={detailIndex}
                  measureRow={measureRow}
                />
              ) : (
                <div className="p-4">
                  <EmptyState
                    icon="terminal"
                    title={executionMode === 'ASYNC_JOB' ? 'No job submitted yet' : 'No results yet'}
                    description={executionMode === 'ASYNC_JOB' ? 'Submit an INSERT INTO statement to launch a streaming job and track it here.' : 'Run a query with ⌘↵ to see results in this panel.'}
                  />
                  {/* Propositions de départ, bâties sur le catalogue réellement chargé — donc
                      jamais sur une table absente. L'onglet initial portait à leur place une
                      requête écrite en dur, en syntaxe ksqlDB et sur un `orders_stream` qui
                      n'existe nulle part : le tout premier Run échouait. */}
                  {!sql.trim() && starters.length > 0 && (
                    <div className="mx-auto mt-2 max-w-lg">
                      <p className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em] mb-2">Start from your cluster</p>
                      <div className="space-y-1.5">
                        {starters.map(s => (
                          <button key={s.label} type="button"
                            // Même geste que la barre latérale : la cible d'un INSERT est posée
                            // sélectionnée, donc remplaçable d'une frappe.
                            onClick={() => { openSql(s.sql); pendingSinkSelectionRef.current = sinkNameRange(s.sql); editorRef.current?.focus(); }}
                            className="w-full text-left px-3 py-2 rounded-lg border border-outline-variant hover:border-primary/50 hover:bg-primary/5 transition-colors group/starter">
                            <div className="flex items-center gap-2">
                              <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-primary">play_arrow</span>
                              <span className="text-[12px] font-medium text-on-surface">{s.label}</span>
                            </div>
                            <p className="font-mono text-[11px] text-on-surface-variant truncate mt-1">{s.preview ?? s.sql}</p>
                            <p className="text-[11px] text-outline mt-0.5">{s.hint}</p>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
            {/* `!!…length`, pas `…length` : un `&&` sur un nombre rend le littéral `0` dans le DOM
                quand il vaut zéro, au lieu de ne rien rendre. */}
            {detailRow && detailIndex !== null && !!results?.columns.length && (
              <RowDetail
                columns={results.columns}
                row={detailRow}
                index={detailIndex}
                total={sortedRows.length}
                focusColumn={detailColumn}
                onClose={() => setDetailIndex(null)}
                onStep={stepRowDetail}
                onCopy={copyCell}
              />
            )}
            </div>
          </div>
        </div>
      </main>
      </div>
    </div>
  );
};

export default QueryWorkbench;
