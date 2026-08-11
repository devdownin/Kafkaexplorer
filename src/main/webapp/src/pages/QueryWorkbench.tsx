import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import Editor, { useMonaco } from '@monaco-editor/react';
import type { editor, languages } from 'monaco-editor';
import '../monaco-setup';
import axios from 'axios';
import { useToast } from '../components/Toast';
import {
  Button, Badge, Input, Select, Field, NumberInput, EmptyState, Tooltip, useConfirm, cn,
  useVirtualRows, ScrollList,
} from '../components/ui';
import {
  describeQueryError, describeApiError, offsetLocation,
  type QueryErrorInfo, type QueryErrorLocation,
} from './queryError';
import { resolveScope, toTableName } from './sqlScope';
import { buildWindowSql, windowCaveat, guessTimeColumn, type WindowKind, type WindowUnit } from './windowSql';
import { toCsv, toJson } from './resultExport';
import {
  formatSql, sortRows, cellText, nextActiveTabId, isResultStale,
  writeStored, readStored, removeStored,
  readLayout, clamp, LAYOUT_STORAGE_KEY, DEFAULT_LAYOUT,
  SPLIT_MIN, SPLIT_MAX, SIDEBAR_MIN, SIDEBAR_MAX, type WorkbenchLayout,
} from './queryWorkbench';
import { randomId } from '../randomId';
import { copyText } from '../clipboard';

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

/**
 * Grille de résultats, mémoïsée et définie **hors** de la page.
 *
 * Elle était rendue en ligne dans `QueryWorkbench` : chaque frappe dans l'éditeur — donc chaque
 * `setTabs` — reconstruisait jusqu'à deux cents lignes de cellules, avec la sérialisation de
 * chaque valeur, alors que rien du résultat n'avait bougé. Sortie du composant parent et
 * `React.memo`-ée, elle ne re-rend que lorsque les lignes, le tri ou la fenêtre de virtualisation
 * changent réellement.
 */
interface ResultsGridProps {
  columns: string[];
  rows: Record<string, unknown>[];
  virtualized: boolean;
  window: { start: number; padTop: number; padBottom: number };
  sortCol: string | null;
  sortDir: 'asc' | 'desc';
  onSort: (col: string) => void;
  onCopyCell: (value: unknown) => void;
  measureRow: (tr: HTMLTableRowElement | null) => void;
}

const ResultsGrid = React.memo(function ResultsGrid({
  columns, rows, virtualized, window: vwin, sortCol, sortDir, onSort, onCopyCell, measureRow,
}: ResultsGridProps) {
  return (
    <table className={cn('w-full text-left border-collapse', virtualized && 'table-fixed')}>
      <thead className="sticky top-0 bg-surface-container-high/90 backdrop-blur-sm z-10">
        <tr>
          {columns.map(col => (
            // `aria-sort` sur l'en-tête et un vrai `<button>` dedans : l'en-tête était un `<th>`
            // cliquable, donc un tri inatteignable au clavier et un ordre jamais annoncé.
            <th key={col} scope="col"
              aria-sort={sortCol === col ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
              className="px-4 py-2.5 border-b border-outline-variant/60 text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em] whitespace-nowrap">
              <button type="button" onClick={() => onSort(col)}
                className="flex items-center gap-1 hover:text-on-surface select-none transition-colors rounded">
                {col}
                {sortCol === col
                  ? <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">{sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward'}</span>
                  : <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">unfold_more</span>}
              </button>
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-outline-variant/40">
        {/* Cale supérieure : préserve la hauteur des lignes non montées. */}
        {virtualized && vwin.padTop > 0 && (
          <tr aria-hidden="true" className="border-t-0"><td colSpan={columns.length} style={{ height: vwin.padTop, padding: 0 }} /></tr>
        )}
        {rows.map((row, i) => {
          const absIndex = virtualized ? vwin.start + i : i;
          return (
            <tr key={absIndex} ref={virtualized && i === 0 ? measureRow : undefined} className="hover:bg-surface-container-high/40 transition-colors">
              {columns.map(col => {
                const { text, isNull } = cellText(row[col]);
                return (
                  <td key={col} onClick={() => onCopyCell(row[col])}
                    className={cn(
                      'px-4 py-2.5 text-[12px] font-mono cursor-pointer hover:text-primary transition-colors',
                      // Un NULL SQL et une chaîne vide se rendaient tous deux par une cellule
                      // vide — c'est pourtant la distinction qui dit si une jointure a trouvé
                      // sa ligne.
                      isNull ? 'text-outline italic' : 'text-on-surface',
                      // En mode virtualisé, les lignes doivent rester à hauteur constante :
                      // on force chaque cellule sur une seule ligne (troncature + tooltip).
                      virtualized && 'whitespace-nowrap max-w-md truncate',
                    )}
                    title={virtualized ? text : 'Click to copy'}>
                    {text}
                  </td>
                );
              })}
            </tr>
          );
        })}
        {/* Cale inférieure. */}
        {virtualized && vwin.padBottom > 0 && (
          <tr aria-hidden="true" className="border-t-0"><td colSpan={columns.length} style={{ height: vwin.padBottom, padding: 0 }} /></tr>
        )}
      </tbody>
    </table>
  );
});

interface SchemaInfo { topics: string[]; tables: string[]; health: boolean; }
interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  error: string | null;
  tableRegistered?: boolean;
  engine?: string;
  /**
   * Caveats the engine attached to an otherwise successful result — above all, WHERE predicates
   * the direct reader could not apply. It reports them precisely so the UI does not present an
   * unfiltered scan as a filtered one.
   */
  warnings?: string[];
}
interface FlinkJobSubmission {
  queryId: string;
  flinkJobId: string;
  statementType: string;
  status: string;
  sql: string;
  startedAt: number;
  endedAt: number | null;
  cancelRequested: boolean;
}
interface Tab { id: string; name: string; sql: string; }
interface SavedQuery { id: string; name: string; sql: string; savedAt: number; }
type ExecutionMode = 'SYNC_READ' | 'ASYNC_JOB';

/** Choix de plafond de lignes. La valeur part au backend en `maxRows` — voir runQuery. */
const ROW_LIMITS = [50, 100, 500, 1000, 5000] as const;
const DEFAULT_LIMIT = 50;
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
const DEFAULT_SQL = "SELECT\n  window_start, window_end, product_id,\n  SUM(quantity) AS total_sales\nFROM orders_stream\nWINDOW TUMBLING (SIZE 5 MINUTES)\nGROUP BY\n  window_start, window_end, product_id\nEMIT CHANGES;";
// Au-delà de ce nombre de lignes, la grille passe en rendu virtualisé (seules
// les lignes visibles sont montées). En-deçà, on garde le rendu classique —
// aucun changement d'apparence ni de comportement pour le cas courant.
const VIRTUALIZE_THRESHOLD = 200;
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
    tabs = [{ id: '1', name: 'Query 1', sql: urlSql ? decodeURIComponent(urlSql) : DEFAULT_SQL }];
    return { tabs, activeTabId: '1', consumedUrlSql: !!urlSql };
  }

  // Les ids restaurés viennent d'un compteur d'une session précédente : on le repositionne
  // au-dessus, sinon le prochain « + » recréerait un id déjà pris.
  tabCounter = Math.max(tabCounter, ...tabs.map(t => Number(t.id) || 0));

  if (urlSql) {
    const incoming = decodeURIComponent(urlSql);
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

const detectStatementType = (sql: string) => {
  const stripped = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim().toUpperCase();
  if (stripped.startsWith('INSERT INTO')) return 'INSERT';
  if (stripped.startsWith('CREATE TABLE')) return 'CREATE_TABLE';
  if (stripped.startsWith('SELECT')) return 'SELECT';
  if (stripped.startsWith('EXPLAIN')) return 'EXPLAIN';
  return stripped.split(/\s+/, 1)[0] ?? 'UNKNOWN';
};

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

  // ── Tabs ──────────────────────────────────────────────────────────────────────
  // Une seule restauration, au premier rendu : relire le stockage à chaque rendu
  // ressusciterait les onglets fermés.
  const [restored] = useState(() => restoreTabs(new URLSearchParams(location.search).get('sql')));
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
  }, [tabs, confirm]);

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
    ed.addCommand(m.KeyMod.CtrlCmd | m.KeyMod.Shift | m.KeyCode.KeyF, () => {
      void ed.getAction('editor.action.formatDocument')?.run();
    });
  }, []);

  /**
   * Monaco n'a **pas** de formateur SQL (voir `formatSql`). Le bouton « Format » appelait
   * `editor.action.formatDocument`, qui sans fournisseur enregistré ne reformate rien et se
   * contente d'un message discret dans l'éditeur : le bouton était décoratif depuis toujours.
   */
  useEffect(() => {
    if (!monaco) return;
    const disp = monaco.languages.registerDocumentFormattingEditProvider('sql', {
      provideDocumentFormattingEdits: model => [{
        range: model.getFullModelRange(),
        text: formatSql(model.getValue()),
      }],
    });
    return () => disp.dispose();
  }, [monaco]);

  // Auto-completion provider
  useEffect(() => {
    if (!monaco) return;
    const disp = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: [' ', '\n', '.'],
      provideCompletionItems: (_model, position) => {
        const word = _model.getWordUntilPosition(position);
        const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
        const suggestions: languages.CompletionItem[] = [];
        schemaRef.current?.tables.forEach(table => suggestions.push({
          label: table, kind: monaco.languages.CompletionItemKind.Class, insertText: table, range, detail: 'Flink Table',
        }));
        // Colonnes : limitées aux tables que la requête cite réellement. Proposer celles de
        // toutes les tables chargées noyait les bonnes au milieu de dizaines d'inutiles.
        // Tant qu'aucune table n'est citée (curseur dans le SELECT avant le FROM), on retombe
        // sur tout ce qui est connu plutôt que de ne rien proposer.
        const loaded = tableSchemasRef.current;
        const scope = resolveScope(_model.getValue(), Object.keys(loaded));
        const scoped = scope.filter(t => loaded[t]);
        const columnSources = scoped.length ? scoped : Object.keys(loaded);
        columnSources.forEach(tableName =>
          Object.entries(loaded[tableName] ?? {}).forEach(([col, type]) => suggestions.push({
            label: col, kind: monaco.languages.CompletionItemKind.Field, insertText: col, range, detail: type,
            documentation: `${tableName}.${col}`,
            // Les colonnes en portée passent devant les mots-clés, triés par défaut sur le label.
            sortText: scoped.length ? `0_${col}` : `1_${col}`,
          }))
        );
        ['TUMBLE', 'HOP', 'SESSION', 'CUMULATE', 'DESCRIPTOR', 'PROCTIME', 'ROWTIME',
          'WATERMARK', 'EMIT CHANGES', 'INTERVAL', 'OVER', 'PARTITION BY',
          'JSON_VALUE', 'JSON_QUERY', 'JSON_EXISTS'].forEach(kw =>
          suggestions.push({ label: kw, kind: monaco.languages.CompletionItemKind.Keyword, insertText: kw, range })
        );
        return { suggestions };
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
        const tables = schemaRef.current?.tables ?? [];
        if (!tables.includes(word.word)) return null;
        const cols = tableSchemasRef.current[word.word];
        const body = cols
          ? `**Flink Table** \`${word.word}\`\n\n| Column | Type |\n|--------|------|\n${Object.entries(cols).map(([c, t]) => `| \`${c}\` | \`${t}\` |`).join('\n')}`
          : `**Flink Table** \`${word.word}\`\n\n_Expand in sidebar to load schema_`;
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
  // Origine du fragment exécuté dans le document, quand seule la sélection a été lancée.
  // Les positions d'erreur du moteur sont relatives à ce fragment ; sans ce décalage, le
  // marqueur Monaco et le « jump to line » pointeraient le haut du document.
  const [runOrigin, setRunOrigin] = useState<QueryErrorLocation | null>(null);
  // Reflète la présence d'une sélection non vide, pour libeller le bouton en conséquence.
  const [hasSelection, setHasSelection] = useState(false);
  const [executionMode, setExecutionMode] = useState<ExecutionMode>('SYNC_READ');
  const [offsetMode, setOffsetMode] = useState<'EARLIEST' | 'LATEST'>('EARLIEST');
  const [panelError, setPanelError] = useState<QueryErrorInfo | null>(null);
  const [sortCol, setSortCol] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
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

  // Erreur classée (titre lisible + piste + position) — voir queryError.ts.
  // Une requête rejetée avant exécution (mode, validateur backend) passe par le même
  // panneau que l'échec d'exécution : même titre lisible, même piste, même marqueur
  // Monaco. Un rejet pré-vol l'emporte, puisqu'il n'y a alors aucun résultat.
  const queryError = useMemo(() => {
    const info = panelError ?? (results?.error ? describeQueryError(results.error) : null);
    if (!info) return null;
    // Ramène la position dans le repère du document quand seule la sélection a été exécutée.
    return { ...info, location: offsetLocation(info.location, runOrigin ?? undefined) };
  }, [panelError, results, runOrigin]);

  // Le résultat bute sur son propre plafond → il est probablement incomplet.
  const truncated = !!results && !results.error && results.rows.length >= resultLimit;

  /**
   * Les lignes affichées ne répondent plus au SQL en cours d'édition. La grille gardait le
   * résultat précédent sans rien dire pendant qu'on écrivait la requête suivante — on lit alors
   * un tableau en croyant qu'il répond au texte sous les yeux. Stream Flow marque son graphe
   * exactement de la même façon.
   */
  const staleResults = !executing && !!results && isResultStale(ranSql, sql);

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
    setRunOrigin(null);
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
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDdlPreviewTopic(null); };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      ddlOpenerRef.current?.focus();
      ddlOpenerRef.current = null;
    };
  }, [ddlPreviewTopic]);

  const fetchDdlPreview = async (topicName: string) => {
    ddlOpenerRef.current = document.activeElement as HTMLElement | null;
    setDdlPreviewTopic(topicName);
    setDdlPreview(null);
    setDdlPreviewLoading(true);
    try {
      const res = await axios.get<{ ddl?: string; error?: string }>(`/api/query/ddl-preview?topic=${encodeURIComponent(topicName)}`);
      setDdlPreview(res.data.ddl ?? null);
      if (res.data.error) toast(`DDL preview failed: ${res.data.error}`, 'error');
    } catch { toast('Failed to generate DDL preview', 'error'); }
    finally { setDdlPreviewLoading(false); }
  };

  // ── History ───────────────────────────────────────────────────────────────────
  const [showHistory, setShowHistory] = useState(false);
  const historyRef = useRef<HTMLDivElement>(null);
  const [history, setHistory] = useState<{ sql: string; ts: number }[]>(
    () => readStored<{ sql: string; ts: number }[]>(HISTORY_STORAGE_KEY, []),
  );
  const saveToHistory = (sqlStr: string) => {
    const entry = { sql: sqlStr.trim(), ts: Date.now() };
    const next = [entry, ...history.filter(h => h.sql !== entry.sql)].slice(0, 20);
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
  const [windowType, setWindowType] = useState<WindowKind>('TUMBLE');
  const [windowSize, setWindowSize] = useState(5);
  const [windowSlide, setWindowSlide] = useState(1);
  const [windowUnit, setWindowUnit] = useState<WindowUnit>('MINUTE');
  const [windowTimeCol, setWindowTimeCol] = useState('');
  const [windowPartitionBy, setWindowPartitionBy] = useState('');

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
  const { splitPercent, sidebarWidth } = layout;

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
      const r = await axios.get('/api/query/init');
      setSchema(r.data);
      // Le catalogue a changé (CREATE TABLE, auto-enregistrement) : une table dont le schéma
      // avait échoué faute d'existence mérite une nouvelle tentative.
      schemaFetchAttempted.current.clear();
    }
    catch { toast('Failed to load schema', 'error'); }
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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- au montage seulement : fetchSchema est recréé à chaque rendu
  }, []);

  /**
   * Charge le schéma des tables citées par la requête en cours, pour que l'autocomplétion
   * connaisse leurs colonnes sans que l'utilisateur ait à déplier la sidebar.
   *
   * Borné volontairement : une tentative au plus par nom et par visite (jusqu'au prochain
   * rafraîchissement du catalogue), et seulement pour un nom que le catalogue connaît — sinon
   * chaque frappe dans le FROM déclencherait une requête sur un nom incomplet.
   */
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
      try { const r = await axios.get(`/api/query/schema/${tableName}`); setTableSchemas(prev => ({ ...prev, [tableName]: r.data })); }
      catch { toast(`Failed to load schema for ${tableName}`, 'error'); }
    }
  };

  const runQuery = async () => {
    /**
     * Le bouton Run se désactive pendant l'exécution, mais ⌘↵ appelle cette fonction *directement*
     * : deux frappes rapides lançaient deux requêtes. La seconde écrasait `abortRef` et
     * `runningQueryIdRef`, si bien que Stop n'annulait plus la première, dont le `finally`
     * repassait ensuite l'écran en « Complete » alors qu'une requête était toujours en vol.
     */
    if (executingRef.current) {
      toast('A query is already running — stop it first', 'info');
      return;
    }
    setPanelError(null);

    // N'exécuter que la sélection quand il y en a une — habitude universelle des éditeurs SQL,
    // et le seul moyen de lancer une requête parmi plusieurs dans le même onglet. L'onglet
    // garde son contenu entier ; seule la portion envoyée change.
    const editor = editorRef.current;
    const selection = editor?.getSelection();
    const selected = selection && !selection.isEmpty()
      ? editor?.getModel()?.getValueInRange(selection) ?? ''
      : '';
    const runningSelection = selected.trim().length > 0;
    const sqlToRun = runningSelection ? selected : sql;
    setRunOrigin(runningSelection && selection
      ? { line: selection.startLineNumber, column: selection.startColumn }
      : null);

    const statementType = detectStatementType(sqlToRun);

    if (executionMode === 'SYNC_READ' && statementType === 'INSERT') {
      setResults(null); setSubmittedJob(null);
      setPanelError({
        title: 'INSERT INTO cannot run in Read mode',
        hint: 'Switch the execution mode to Flink Job — Read mode returns rows, so it only accepts SELECT, EXPLAIN and CREATE TABLE.',
        raw: 'INSERT INTO must be submitted in Flink Job mode.',
      });
      return;
    }
    if (executionMode === 'ASYNC_JOB' && statementType !== 'INSERT') {
      setResults(null); setSubmittedJob(null);
      setPanelError({
        title: 'Flink Job mode only accepts INSERT INTO',
        hint: `This statement is ${statementType || 'not an INSERT'}. Switch back to Read mode to run it and see the rows.`,
        raw: 'Flink Job mode only accepts INSERT INTO statements.',
      });
      return;
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
        const vRes = await axios.post<{ valid: boolean; error?: string }>('/api/query/validate', { sql: sqlToRun });
        if (!vRes.data.valid) {
          // Rejet avant exécution : le backend renvoie déjà le texte du parser (avec sa
          // ligne/colonne quand il en a une), on le classe comme n'importe quelle erreur.
          validatedSqlRef.current = null;
          setResults(null); setSubmittedJob(null);
          setPanelError(describeQueryError(vRes.data.error ?? 'SQL validation failed'));
          return;
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
    setExecuting(true); setResults(null); setSubmittedJob(null); setSortCol(null);
    // Ce qui a réellement été exécuté — c'est lui, et non le contenu courant de l'onglet, qui dit
    // si les lignes affichées répondent encore au texte sous les yeux.
    setRanSql(sqlToRun);
    try {
      if (executionMode === 'ASYNC_JOB') {
        const response = await axios.post<FlinkJobSubmission>('/api/query/jobs', { sql: sqlToRun },
          { signal: controller.signal });
        setExecutionMs(Date.now() - start);
        setSubmittedJob(response.data);
        setResults(null);
        saveToHistory(sqlToRun);
        toast(`Streaming job submitted: ${response.data.status}`, 'success');
      } else {
        const readMode = offsetMode === 'LATEST' ? 'latest-offset' : 'earliest-offset';
        const limit = maxRows;
        const response = await axios.post<QueryResult>('/api/query/run-sync',
          { sql: sqlToRun, readMode, maxRows: limit, queryId }, { signal: controller.signal });
        setExecutionMs(Date.now() - start);
        setResultLimit(limit);
        setResults(response.data);
        if (!response.data.error) {
          saveToHistory(sqlToRun);
          // Refresh the schema browser when:
          // 1. The user explicitly ran a CREATE TABLE statement.
          // 2. The backend auto-registered a Flink table during query execution.
          const strippedForCheck = sqlToRun.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, '').trim();
          if (strippedForCheck.toUpperCase().startsWith('CREATE TABLE') || response.data.tableRegistered) {
            fetchSchema();
          }
        }
      }
    } catch (error) {
      setExecutionMs(Date.now() - start);
      // Une annulation demandée par l'utilisateur n'est pas un échec : pas de panneau rouge.
      if (axios.isCancel(error)) {
        setResults(null);
        setSubmittedJob(null);
        return;
      }
      // describeApiError couvre aussi ce que le corps de réponse ne dit pas : backend
      // injoignable, requête interrompue. Sans lui, une panne de transport s'affichait
      // « Query execution failed », qui n'apprend rien.
      const info = describeApiError(error, 'Query execution failed');
      setResults(null);
      setSubmittedJob(null);
      setPanelError(info);
      toast(info.title, 'error');
    } finally {
      setExecuting(false);
      executingRef.current = false;
      abortRef.current = null;
      runningQueryIdRef.current = null;
    }
  };
  // Idem : la ref se met à jour dans un effet, pas au milieu du rendu.
  useEffect(() => { runQueryRef.current = runQuery; });

  /**
   * Arrête la requête en cours. Deux effets distincts, et seul le premier est garanti :
   * l'abandon de la requête HTTP rend la main immédiatement, quel que soit le moteur ;
   * l'appel au backend annule en plus le job Flink quand il y en a un (le lecteur Kafka
   * direct, lui, n'a pas de job à annuler et terminera son fetch en cours côté serveur).
   */
  const cancelRunningQuery = useCallback(() => {
    const queryId = runningQueryIdRef.current;
    const controller = abortRef.current;
    controller?.abort();
    if (queryId) {
      axios.post(`/api/query/cancel/${encodeURIComponent(queryId)}`)
        .catch(() => { /* best-effort : l'UI est déjà rendue à l'utilisateur */ });
    }
    // Ne confirmer que ce qui a réellement eu lieu. Sans contrôleur ni id, ce bouton n'a
    // rien annulé du tout, et annoncer « Query cancelled » là-dessus est précisément ce qui
    // fait décrire le symptôme comme « le bouton est inactif » plutôt que « l'écran est
    // resté bloqué » — le message détournait le diagnostic. On remet aussi l'écran dans un
    // état cohérent, sans quoi il reste en « exécution » indéfiniment.
    if (controller || queryId) {
      toast('Query cancelled', 'info');
    } else {
      setExecuting(false);
      toast('No query was running', 'info');
    }
  }, [toast]);

  const handleSortColumn = useCallback((col: string) => {
    if (sortCol === col) setSortDir(d => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortCol(col); setSortDir('asc'); }
  }, [sortCol]);

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

  /** Le raccourci « SELECT * FROM … » de la barre latérale, sans écraser l'onglet en cours. */
  const openSelectFor = useCallback((table: string) => {
    const where = openSql(`SELECT * FROM ${table} LIMIT ${maxRows}`, table);
    if (where === 'new') toast(`Opened ${table} in a new tab`, 'success');
  }, [openSql, maxRows, toast]);

  /** Insère à la position du curseur — utilisé par les fragments (assistant de fenêtrage). */
  const insertSql = useCallback((text: string) => {
    const ed = editorRef.current;
    const selection = ed?.getSelection();
    if (!ed || !selection) {
      updateSql(sql ? `${sql}\n\n${text}` : text);
      return 'appended' as const;
    }
    ed.executeEdits('kse-insert', [{ range: selection, text }]);
    ed.focus();
    return selection.isEmpty() ? ('inserted' as const) : ('replaced' as const);
  }, [sql, updateSql]);

  /** Table visée par l'assistant : celle que la requête cite, sinon la première du catalogue. */
  const windowTable = useMemo(
    () => resolveScope(sql, [...(schema?.tables ?? []), ...(schema?.topics ?? [])])[0]
      ?? schema?.tables[0] ?? 'source_table',
    [sql, schema],
  );

  // Pré-remplit la colonne temporelle depuis le schéma chargé, tant que l'utilisateur n'a rien
  // saisi. L'assistant écrivait `event_time` en dur — un nom que la plupart des topics n'ont pas.
  const guessedTimeCol = useMemo(
    () => guessTimeColumn(tableSchemas[toTableName(windowTable)]),
    [tableSchemas, windowTable],
  );
  const effectiveTimeCol = windowTimeCol.trim() || guessedTimeCol || 'event_time';

  const windowSpec = useMemo(() => ({
    kind: windowType,
    table: windowTable,
    timeColumn: effectiveTimeCol,
    size: windowSize,
    unit: windowUnit,
    slide: windowSlide,
    partitionBy: windowPartitionBy,
  }), [windowType, windowTable, effectiveTimeCol, windowSize, windowUnit, windowSlide, windowPartitionBy]);

  /**
   * Insère la requête générée à la position du curseur (en remplaçant la sélection).
   * Elle écrasait auparavant tout l'onglet — le travail en cours était perdu sans confirmation.
   */
  const applyWindowLogic = () => {
    const where = insertSql(buildWindowSql(windowSpec));
    toast({
      appended: 'Window query appended',
      inserted: 'Window query inserted at cursor',
      replaced: 'Window query replaced the selection',
    }[where], 'success');
  };

  const formatMs = (ms: number) => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;

  // ── Render ────────────────────────────────────────────────────────────────────
  return (
    <div className="flex h-full overflow-hidden">

      {/* ── DDL Preview Modal ────────────────────────────────────────────────── */}
      {ddlPreviewTopic && (
        <div className="fixed inset-0 glass-overlay z-50 flex items-center justify-center p-8"
          role="dialog" aria-modal="true" aria-label="DDL preview"
          onClick={() => setDdlPreviewTopic(null)}>
          <div onClick={e => e.stopPropagation()}
            className="bg-surface-container border border-outline-variant rounded-xl w-full max-w-2xl max-h-[80vh] flex flex-col shadow-2xl">
            <div className="flex items-center justify-between p-4 border-b border-outline-variant">
              <div>
                <h2 className="text-[14px] font-semibold text-on-surface">DDL Preview</h2>
                <p className="text-[11px] text-on-surface-variant font-mono mt-0.5">{ddlPreviewTopic}</p>
              </div>
              <button onClick={() => setDdlPreviewTopic(null)} aria-label="Close"
                className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors">
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>
            <div className="flex-1 overflow-auto p-4">
              {ddlPreviewLoading
                ? <div className="flex items-center gap-2 text-on-surface-variant py-4"><span className="material-symbols-outlined animate-spin text-[16px]">progress_activity</span><span className="text-[13px]">Inferring schema…</span></div>
                : ddlPreview
                  ? <pre className="text-[12px] font-mono text-on-surface whitespace-pre-wrap leading-relaxed">{ddlPreview}</pre>
                  : <p className="text-[13px] text-on-surface-variant">Failed to generate DDL</p>
              }
            </div>
            <div className="p-4 border-t border-outline-variant flex items-center justify-end gap-2">
              <Button variant="outline" size="sm" icon="content_copy" disabled={!ddlPreview}
                onClick={() => { if (ddlPreview) void copyText(ddlPreview).then(ok =>
                  toast(ok ? 'DDL copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error')); }}>
                Copy
              </Button>
              <Button variant="primary" size="sm" icon="edit_note" disabled={!ddlPreview}
                onClick={() => {
                  if (!ddlPreview) return;
                  const where = openSql(ddlPreview, `DDL ${ddlPreviewTopic}`);
                  setDdlPreviewTopic(null);
                  toast(where === 'new' ? 'DDL opened in a new tab' : 'DDL inserted in editor', 'success');
                }}>
                Insert in editor
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Schema Browser Sidebar (resizable) ──────────────────────────────── */}
      <aside ref={asideRef} className="relative flex-shrink-0 flex flex-col border-r border-outline-variant/60 bg-surface-container-low" style={{ width: sidebarWidth }}>
        {/* Sidebar drag handle */}
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize the schema browser"
          aria-valuenow={Math.round(sidebarWidth)}
          aria-valuemin={SIDEBAR_MIN}
          aria-valuemax={SIDEBAR_MAX}
          tabIndex={0}
          onPointerDown={e => startDrag('sidebar', 'col-resize', e)}
          onKeyDown={e => {
            if (e.key === 'ArrowLeft') { setSidebarWidth(w => w - 16); e.preventDefault(); }
            if (e.key === 'ArrowRight') { setSidebarWidth(w => w + 16); e.preventDefault(); }
            if (e.key === 'Home') { setSidebarWidth(DEFAULT_LAYOUT.sidebarWidth); e.preventDefault(); }
          }}
          style={{ touchAction: 'none' }}
          className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 focus-visible:bg-primary/60 transition-colors z-10"
        />

        <div className="p-4 flex items-center gap-3 border-b border-outline-variant/60">
          <div className="size-8 bg-primary/15 text-primary rounded-lg flex items-center justify-center shrink-0">
            <span className="material-symbols-outlined text-[18px]">database</span>
          </div>
          <div className="min-w-0">
            <h1 className="text-[14px] font-semibold tracking-tight text-on-surface">SQL Workbench</h1>
            <p className="text-[11px] text-on-surface-variant">Flink SQL Engine</p>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Schema Browser</h2>
            <button onClick={fetchSchema} disabled={schemaLoading} aria-label="Refresh schema" className="p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors disabled:opacity-50" title="Refresh">
              <span className={`material-symbols-outlined text-[18px] ${schemaLoading ? 'animate-spin' : ''}`}>{schemaLoading ? 'progress_activity' : 'refresh'}</span>
            </button>
          </div>

          <div className="space-y-1">
            {/* Flink Tables */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">grid_view</span>
                <span className="text-sm font-medium">Flink Tables</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.tables.length ?? 0}</span>
              </summary>
              <ScrollList count={schema?.tables.length ?? 0} className="pl-4 pt-1 space-y-0.5">
                {schema?.tables.map(table => (
                  <div key={table}>
                    <div className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/tbl">
                      {/* Un `<div onClick>` n'est ni tabulable ni actionnable au clavier : toute la
                          barre latérale était inatteignable sans souris. */}
                      <button type="button" onClick={() => toggleTable(table)}
                        aria-expanded={!!expandedTables[table]}
                        className="flex-1 flex items-center gap-1 min-w-0 text-left rounded">
                        <span className={`material-symbols-outlined text-xs text-on-surface-variant transition-transform duration-200 shrink-0 ${expandedTables[table] ? 'rotate-90' : ''}`}>chevron_right</span>
                        <span className="text-xs text-on-surface truncate font-mono">{table}</span>
                      </button>
                      <button type="button" onClick={() => openSelectFor(table)}
                        className="opacity-0 group-hover/tbl:opacity-100 focus-visible:opacity-100 text-outline hover:text-primary transition-all shrink-0 ml-1" title="SELECT from this table" aria-label={`SELECT from ${table}`}>
                        <span className="material-symbols-outlined text-sm">play_arrow</span>
                      </button>
                    </div>
                    {expandedTables[table] && tableSchemas[table] && (
                      <div className="ml-6 pl-3 border-l border-primary/20 py-1 space-y-1">
                        {Object.entries(tableSchemas[table]).map(([col, type]) => (
                          <div key={col} className="flex justify-between items-center text-[10px] py-0.5">
                            <span className="text-on-surface-variant truncate pr-2 font-mono">{col}</span>
                            <span className="text-primary/60 font-mono uppercase shrink-0">{type}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </ScrollList>
            </details>

            {/* Kafka Topics */}
            <details className="group" open>
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">topic</span>
                <span className="text-sm font-medium">Kafka Topics</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.topics.length ?? 0}</span>
              </summary>
              <div className="pl-4 pt-1 space-y-0.5">
                {schemaLoading ? (
                  <div className="flex items-center gap-2 py-3 px-2 text-on-surface-variant">
                    <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                    <span className="text-xs">Loading…</span>
                  </div>
                ) : schema?.topics.length === 0 ? (
                  <p className="text-[10px] text-outline px-2 py-2">No topics with messages</p>
                ) : (
                  <ScrollList count={schema?.topics.length ?? 0} className="space-y-0.5">
                    {schema?.topics.map(topic => (
                      <div key={topic} className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/topic">
                        <button type="button" onClick={() => openSelectFor(toTableName(topic))}
                          className="flex-1 min-w-0 text-left rounded" aria-label={`SELECT from ${topic}`}>
                          <span className="text-xs text-on-surface-variant hover:text-primary font-mono truncate block">{topic}</span>
                        </button>
                        <button type="button" onClick={e => { e.stopPropagation(); fetchDdlPreview(topic); }}
                          className="opacity-0 group-hover/topic:opacity-100 focus-visible:opacity-100 text-outline hover:text-primary transition-all shrink-0 ml-1" title="Preview DDL" aria-label={`Preview the generated DDL for ${topic}`}>
                          <span className="material-symbols-outlined text-sm">code</span>
                        </button>
                      </div>
                    ))}
                  </ScrollList>
                )}
                <p className="text-[10px] text-outline px-2 pt-1">Only topics with messages shown</p>
              </div>
            </details>

            {/* ── Saved Queries ── */}
            <details className="group">
              <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
                <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
                <span className="material-symbols-outlined text-base text-on-surface-variant">bookmark</span>
                <span className="text-sm font-medium">Saved Queries</span>
                <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{savedQueries.length}</span>
              </summary>
              <div className="pl-4 pt-2 space-y-1">
                {/* Save current query */}
                {saveInputVisible ? (
                  <div className="flex items-center gap-1 px-2 pb-2">
                    <Input
                      autoFocus
                      aria-label="Saved query name"
                      value={saveInputName}
                      onChange={e => setSaveInputName(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') saveQuery(); if (e.key === 'Escape') setSaveInputVisible(false); }}
                      placeholder={activeTab.name}
                      className="flex-1 h-8 text-[12px]"
                    />
                    <button onClick={saveQuery} aria-label="Confirm save" className="p-1 rounded-md text-primary hover:bg-surface-container-high">
                      <span className="material-symbols-outlined text-[18px]">check</span>
                    </button>
                    <button onClick={() => setSaveInputVisible(false)} aria-label="Cancel" className="p-1 rounded-md text-outline hover:text-on-surface hover:bg-surface-container-high">
                      <span className="material-symbols-outlined text-[18px]">close</span>
                    </button>
                  </div>
                ) : (
                  <button onClick={() => { setSaveInputVisible(true); setSaveInputName(activeTab.name); }}
                    className="flex items-center gap-1.5 w-full px-2 py-1.5 text-[12px] font-medium text-on-surface-variant hover:text-on-surface border border-dashed border-outline-variant hover:border-outline rounded-md transition-colors">
                    <span className="material-symbols-outlined text-[16px]">add</span>Save current query
                  </button>
                )}
                {savedQueries.length === 0 && !saveInputVisible && (
                  <p className="text-[11px] text-outline px-2 py-1">No saved queries yet</p>
                )}
                <ScrollList count={savedQueries.length} className="space-y-1">
                  {savedQueries.map(q => (
                    <div key={q.id} className="flex items-center gap-1 py-1 px-2 rounded hover:bg-primary/5 transition-colors group/saved">
                      <div onClick={() => loadSavedQuery(q)} className="flex-1 min-w-0 cursor-pointer">
                        <p className="text-xs text-on-surface truncate font-medium">{q.name}</p>
                        <p className="text-[10px] text-outline">{new Date(q.savedAt).toLocaleDateString()}</p>
                      </div>
                      <button onClick={() => deleteSavedQuery(q.id)}
                        className="opacity-0 group-hover/saved:opacity-100 text-outline hover:text-error transition-all shrink-0" title="Delete" aria-label="Delete this saved query">
                        <span className="material-symbols-outlined text-sm">delete</span>
                      </button>
                    </div>
                  ))}
                </ScrollList>
              </div>
            </details>
          </div>
        </div>

        <div className="p-3 border-t border-outline-variant/60 flex items-center gap-2 text-[11px] text-on-surface-variant">
          <span className={`w-1.5 h-1.5 rounded-full ${schema?.health ? 'bg-success' : 'bg-outline'}`} />
          <span>{schema?.health ? 'Engine connected' : 'Engine offline'}</span>
          <span className="ml-auto tabular-nums">{schema?.tables.length ?? 0} tables · {schema?.topics.length ?? 0} topics</span>
        </div>
      </aside>

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
                        <p className="font-mono text-[12px] text-on-surface truncate">{h.sql.replace(/\s+/g, ' ')}</p>
                        <p className="text-[11px] text-outline mt-0.5">{new Date(h.ts).toLocaleString()}</p>
                      </button>
                    ))
                  }
                </div>
              )}
            </div>
            <Tooltip content="Run the query, or just the selection when there is one. Shortcut: ⌘↵ / Ctrl+Enter.">
              <span tabIndex={0} className="text-[11px] text-outline hidden lg:block font-mono rounded">⌘↵</span>
            </Tooltip>
            {executing && (
              <Button variant="secondary" onClick={cancelRunningQuery} icon="stop_circle">
                Stop
              </Button>
            )}
            <Button
              variant="primary"
              onClick={runQuery}
              loading={executing}
              icon={executing ? undefined : 'play_arrow'}
            >
              {executing ? 'Running…'
                : executionMode === 'ASYNC_JOB' ? 'Submit job'
                : hasSelection ? 'Run selection' : 'Run query'}
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
                {/* Format button pushed to the right */}
                <div className="ml-auto px-3 flex items-center">
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
                    // Le bouton doit annoncer ce qu'il va exécuter — tout l'onglet ou la sélection.
                    editor.onDidChangeCursorSelection(e => setHasSelection(!e.selection.isEmpty()));
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

            {/* Window Assistant */}
            <div className="w-72 border-l border-outline-variant/60 bg-surface-container-low/40 p-4 flex flex-col gap-4 overflow-y-auto shrink-0">
              <div className="flex items-center gap-2">
                <span className="material-symbols-outlined text-primary text-[20px]">magic_button</span>
                <h3 className="text-[14px] font-semibold text-on-surface">Window Assistant</h3>
              </div>
              <p className="text-[12px] text-on-surface-variant leading-relaxed">
                Builds a windowed aggregation over <span className="font-mono text-on-surface">{windowTable}</span>.
              </p>
              <div className="space-y-3">
                <Field label="Window type">
                  {p => (
                    <Select {...p} value={windowType} onChange={e => setWindowType(e.target.value as WindowKind)}>
                      <option value="TUMBLE">Tumbling (Non-overlapping)</option>
                      <option value="HOP">Hopping (Overlapping)</option>
                      <option value="SESSION">Session (Inactivity based)</option>
                    </Select>
                  )}
                </Field>
                <Field
                  label={windowType === 'SESSION' ? 'Inactivity gap' : 'Size'}
                  className="flex-1"
                >
                  {p => (
                    <div className="flex gap-2">
                      {/* parseInt(e.target.value) donnait NaN dès que le champ était vidé. */}
                      <NumberInput {...p} min={1} fallback={5} className="flex-1"
                        value={windowSize} onChange={setWindowSize} />
                      <Select aria-label="Time unit" className="w-28"
                        value={windowUnit} onChange={e => setWindowUnit(e.target.value as WindowUnit)}>
                        <option value="SECOND">SECOND</option>
                        <option value="MINUTE">MINUTE</option>
                        <option value="HOUR">HOUR</option>
                      </Select>
                    </div>
                  )}
                </Field>
                {windowType === 'HOP' && (
                  <Field label="Slide" description="How far each window advances. Must be smaller than the size to overlap.">
                    {p => (
                      <NumberInput {...p} min={1} fallback={1}
                        value={windowSlide} onChange={setWindowSlide} />
                    )}
                  </Field>
                )}
                <Field
                  label="Time column"
                  description={guessedTimeCol
                    ? `Detected “${guessedTimeCol}” in the table schema.`
                    : 'Expand the table in the sidebar to detect one automatically.'}
                >
                  {p => (
                    <Input {...p} value={windowTimeCol} placeholder={effectiveTimeCol}
                      onChange={e => setWindowTimeCol(e.target.value)} />
                  )}
                </Field>
                {windowType === 'SESSION' && (
                  <Field label="Partition by" description="Required by Flink for SESSION windows.">
                    {p => (
                      <Input {...p} value={windowPartitionBy} placeholder="user_id"
                        onChange={e => setWindowPartitionBy(e.target.value)} />
                    )}
                  </Field>
                )}
                {windowCaveat(windowSpec) ? (
                  <div className="p-3 bg-warning/10 border border-warning/30 rounded-lg">
                    <p className="text-[11px] text-on-surface-variant leading-snug">
                      <span className="font-semibold text-warning">Heads up:</span> {windowCaveat(windowSpec)}
                    </p>
                  </div>
                ) : (
                  <div className="p-3 bg-primary/5 border border-primary/20 rounded-lg">
                    <p className="text-[11px] text-on-surface-variant leading-snug"><span className="font-semibold text-primary">Tip:</span> Tumbling windows are ideal for periodic metrics like “Orders per 5 min”.</p>
                  </div>
                )}
                <Button variant="secondary" className="w-full" icon="bolt" onClick={applyWindowLogic}>
                  Insert at cursor
                </Button>
              </div>
            </div>
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
                  <span className="text-[11px] text-on-surface-variant">Execution <span className="text-on-surface tabular-nums">{executing ? '…' : executionMs !== null ? formatMs(executionMs) : '—'}</span></span>
                </div>
                <div className="flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[16px] text-on-surface-variant">list_alt</span>
                  <span className="text-[11px] text-on-surface-variant">
                    {/* Tronqué = autant de lignes que le plafond *de cette requête*. La constante
                        locale d'avant ne suivait pas `explorer.default-max-rows` côté serveur. */}
                    Rows <span className={`tabular-nums ${truncated ? 'text-warning' : 'text-on-surface'}`}>{results?.rows.length ?? 0}</span>
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
                <Badge tone={executing ? 'primary' : (queryError ? 'error' : (results || submittedJob) ? 'success' : 'neutral')} dot>
                  {executing ? 'Running' : queryError ? 'Error' : submittedJob ? 'Job submitted' : results ? 'Complete' : 'Idle'}
                </Badge>
              </div>
            </div>

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

            <div ref={resultsScrollRef} className="flex-1 overflow-auto custom-scrollbar">
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
              ) : results?.columns.length ? (
                <ResultsGrid
                  columns={results.columns}
                  rows={visibleRows}
                  virtualized={virtualized}
                  window={gridWindow}
                  sortCol={sortCol}
                  sortDir={sortDir}
                  onSort={handleSortColumn}
                  onCopyCell={copyCell}
                  measureRow={measureRow}
                />
              ) : (
                <EmptyState
                  icon="terminal"
                  title={executionMode === 'ASYNC_JOB' ? 'No job submitted yet' : 'No results yet'}
                  description={executionMode === 'ASYNC_JOB' ? 'Submit an INSERT INTO statement to launch a streaming job and track it here.' : 'Run a query with ⌘↵ to see results in this panel.'}
                />
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};

export default QueryWorkbench;
