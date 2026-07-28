import React from 'react';
import { Button, Input, Select } from '../ui';

/** One Kafka record with its coordinates — what /api/topic returns and what a search hit is. */
export interface TopicMessage {
  partition: number;
  offset: number;
  timestamp: number;
  key: string | null;
  value: string | null;
  headers: Record<string, string | null>;
  valueBytes: number;
  truncated: boolean;
}

export type SearchMode = 'CONTAINS' | 'REGEX' | 'FIELD' | 'HEADER' | 'KEY';

export interface TopicSearchCriteria {
  mode: SearchMode;
  query: string;
  caseSensitive: boolean;
  searchKey: boolean;
  /**
   * Étend une recherche texte / regex aux valeurs des headers Kafka. Décoché par défaut :
   * l'activer d'office changerait ce que renvoie une recherche existante.
   */
  searchHeaders: boolean;
  /**
   * Mode KEY : ne lire que la partition que le partitionneur par défaut aurait choisie pour cette
   * clé. Divise le travail par le nombre de partitions, au prix de deux hypothèses invérifiables
   * (partitionneur par défaut, nombre de partitions inchangé) — d'où l'opt-in.
   */
  keyPartitioning: boolean;
  /** Chemin de champ en mode FIELD, nom du header en mode HEADER. */
  field: string;
  operator: string;
  value: string;
  /** Minutes to look back; 0 means "from the beginning of the topic". */
  sinceMinutes: number;
}

export interface TopicSearchResponse {
  hits: TopicMessage[];
  scanned: number;
  matched: number;
  elapsedMs: number;
  exhausted: boolean;
  stopReason: 'MAX_HITS' | 'MAX_SCAN' | 'TIMEOUT' | 'EXHAUSTED' | 'ERROR';
  nextCursor: Record<string, number>;
  warnings: string[];
}

/**
 * Splits text around every occurrence of `needle`, so a caller can mark the matches: even
 * indexes are the text between matches, odd indexes are the matches themselves.
 */
export const splitOnMatches = (text: string, needle: string, caseSensitive: boolean): string[] => {
  if (!needle) return [text];
  const haystack = caseSensitive ? text : text.toLowerCase();
  const target = caseSensitive ? needle : needle.toLowerCase();
  const parts: string[] = [];
  let cursor = 0;
  for (;;) {
    const found = haystack.indexOf(target, cursor);
    if (found < 0) break;
    parts.push(text.slice(cursor, found), text.slice(found, found + target.length));
    cursor = found + target.length;
  }
  parts.push(text.slice(cursor));
  return parts;
};

export const emptyCriteria: TopicSearchCriteria = {
  mode: 'CONTAINS',
  query: '',
  caseSensitive: false,
  searchKey: true,
  searchHeaders: false,
  keyPartitioning: false,
  field: '',
  operator: 'EQ',
  value: '',
  sinceMinutes: 0,
};

const OPERATORS: { value: string; label: string }[] = [
  { value: 'EQ', label: '=' },
  { value: 'NEQ', label: '≠' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'REGEX', label: 'matches regex' },
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
  { value: 'EXISTS', label: 'exists' },
];

const SCOPES: { value: number; label: string }[] = [
  { value: 0, label: 'Whole topic' },
  { value: 15, label: 'Last 15 min' },
  { value: 60, label: 'Last hour' },
  { value: 1440, label: 'Last 24 h' },
];

const SEARCH_MODES: SearchMode[] = ['CONTAINS', 'REGEX', 'FIELD', 'HEADER', 'KEY'];

/**
 * Le sélecteur de portée ne propose que les valeurs de `SCOPES` : une fenêtre arbitraire venue
 * de l'URL est arrondie à la plus petite portée qui la couvre, sinon le champ afficherait une
 * durée que la liste ne contient pas et la recherche partirait sur autre chose que l'affiché.
 */
function snapScope(minutes: number): number {
  if (!Number.isFinite(minutes) || minutes <= 0) return 0;
  return SCOPES.map(s => s.value).filter(v => v > 0).find(v => v >= minutes) ?? 0;
}

/**
 * Critère de recherche porté par la query string — c'est ainsi qu'un saut de la page Stream Flow
 * ouvre le topic sur *sa* recherche, au lieu de laisser retranscrire le critère à la main dans un
 * formulaire qui n'a pas les mêmes champs.
 *
 * Rend `null` dès que l'URL ne décrit pas une recherche exécutable : une navigation ordinaire
 * vers un topic ne doit rien déclencher.
 */
export function criteriaFromQuery(search: string): TopicSearchCriteria | null {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const mode = (params.get('mode') ?? '').toUpperCase() as SearchMode;
  if (!SEARCH_MODES.includes(mode)) return null;

  const query = (params.get('q') ?? '').trim();
  const value = (params.get('value') ?? '').trim();
  const field = (params.get('field') ?? '').trim();
  const scoped = mode === 'FIELD' || mode === 'HEADER';
  // Mêmes conditions que le bouton « Search » du panneau : une cible sans valeur, ou une
  // recherche texte sans texte, n'aurait rien à exécuter.
  if (scoped && !field) return null;
  if ((scoped || mode === 'KEY') ? !value : !query) return null;

  const operator = params.get('op') ?? '';
  return {
    ...emptyCriteria,
    mode,
    query,
    field,
    operator: OPERATORS.some(o => o.value === operator) ? operator : emptyCriteria.operator,
    value,
    caseSensitive: params.get('case') === '1',
    searchHeaders: params.get('headers') === '1',
    keyPartitioning: params.get('keyPartitioning') === '1',
    sinceMinutes: snapScope(Number(params.get('since'))),
  };
}

const STOP_REASONS: Record<string, string> = {
  MAX_HITS: 'hit limit reached',
  MAX_SCAN: 'scan budget reached',
  TIMEOUT: 'time budget reached',
  EXHAUSTED: 'whole range scanned',
  ERROR: 'search failed',
};

interface Props {
  /** Field paths from the inferred schema — the whole point is not having to guess them. */
  schemaPaths: string[];
  criteria: TopicSearchCriteria;
  onChange: (criteria: TopicSearchCriteria) => void;
  onSearch: () => void;
  onLoadMore: () => void;
  onClear: () => void;
  searching: boolean;
  active: boolean;
  result: TopicSearchResponse | null;
  error: string | null;
  loadedHits: number;
}

const TopicSearchPanel: React.FC<Props> = ({
  schemaPaths, criteria, onChange, onSearch, onLoadMore, onClear,
  searching, active, result, error, loadedHits,
}) => {
  const set = <K extends keyof TopicSearchCriteria>(key: K, value: TopicSearchCriteria[K]) =>
    onChange({ ...criteria, [key]: value });

  // FIELD et HEADER portent leur cible dans `field` ; KEY compare directement `value`.
  const fieldScoped = criteria.mode === 'FIELD' || criteria.mode === 'HEADER';
  const keyScoped = criteria.mode === 'KEY';
  const canSearch = fieldScoped
    ? criteria.field.trim().length > 0
    : keyScoped
      ? criteria.value.trim().length > 0
      : criteria.query.trim().length > 0;

  const submitOnEnter = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && canSearch && !searching) onSearch();
  };

  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3 space-y-3">
      {/* Mode */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="inline-flex bg-surface-container border border-outline-variant rounded-md p-0.5">
          {(['CONTAINS', 'REGEX', 'FIELD', 'HEADER', 'KEY'] as SearchMode[]).map(mode => (
            <button
              key={mode}
              onClick={() => set('mode', mode)}
              aria-pressed={criteria.mode === mode}
              className={`px-3 h-7 text-[12px] font-medium rounded transition-colors ${
                criteria.mode === mode
                  ? 'bg-surface-container-highest text-on-surface'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              {mode === 'CONTAINS' ? 'Text'
                : mode === 'REGEX' ? 'Regex'
                : mode === 'FIELD' ? 'Field'
                : mode === 'HEADER' ? 'Header' : 'Key'}
            </button>
          ))}
        </div>

        <Select
          value={String(criteria.sinceMinutes)}
          onChange={e => set('sinceMinutes', Number(e.target.value))}
          aria-label="Search range"
          className="w-40"
        >
          {SCOPES.map(scope => (
            <option key={scope.value} value={scope.value}>{scope.label}</option>
          ))}
        </Select>

        <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
          <input
            type="checkbox"
            checked={criteria.caseSensitive}
            onChange={e => set('caseSensitive', e.target.checked)}
          />
          Case sensitive
        </label>

        {keyScoped && criteria.operator === 'EQ' && (
          <label
            className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer"
            title="Reads only the partition the default partitioner would have chosen for this key. Much faster, but it assumes the default partitioner and an unchanged partition count."
          >
            <input
              type="checkbox"
              checked={criteria.keyPartitioning}
              onChange={e => set('keyPartitioning', e.target.checked)}
            />
            Only this key&#39;s partition
          </label>
        )}

        {!fieldScoped && !keyScoped && (
          <>
            <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
              <input
                type="checkbox"
                checked={criteria.searchKey}
                onChange={e => set('searchKey', e.target.checked)}
              />
              Search keys too
            </label>
            <label
              className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer"
              title="Also match Kafka header values — a correlation id often travels only there."
            >
              <input
                type="checkbox"
                checked={criteria.searchHeaders}
                onChange={e => set('searchHeaders', e.target.checked)}
              />
              Search headers too
            </label>
          </>
        )}
      </div>

      {/* Criteria */}
      {keyScoped ? (
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={criteria.operator}
            onChange={e => set('operator', e.target.value)}
            aria-label="Operator"
            className="w-44"
          >
            {OPERATORS.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
          </Select>
          {criteria.operator !== 'EXISTS' && (
            <Input
              value={criteria.value}
              onChange={e => set('value', e.target.value)}
              onKeyDown={submitOnEnter}
              placeholder="Record key, e.g. order-88219"
              aria-label="Record key"
              className="flex-1 min-w-[12rem] font-mono"
            />
          )}
          <Button variant="primary" icon="search" onClick={onSearch} disabled={!canSearch || searching}>
            {searching ? 'Searching…' : 'Search'}
          </Button>
        </div>
      ) : fieldScoped ? (
        <div className="flex flex-wrap items-center gap-2">
          {criteria.mode === 'HEADER' ? (
            // Les noms de headers ne viennent d'aucun schéma : saisie libre, pas de liste.
            <Input
              value={criteria.field}
              onChange={e => set('field', e.target.value)}
              onKeyDown={submitOnEnter}
              placeholder="Header name, e.g. correlation-id"
              aria-label="Header name"
              className="flex-1 min-w-[12rem]"
            />
          ) : (
            <Select
              value={criteria.field}
              onChange={e => set('field', e.target.value)}
              aria-label="Field path"
              className="flex-1 min-w-[12rem]"
            >
              <option value="">Select a field…</option>
              {/* Un chemin venu d'un lien (une trace Stream Flow) ou d'un payload que
                  l'échantillon n'a pas montré n'est pas dans le schéma inféré : l'ajouter à la
                  liste, plutôt que d'afficher « Select a field… » au-dessus d'une recherche qui,
                  elle, l'utilise bel et bien. */}
              {(criteria.field && !schemaPaths.includes(criteria.field)
                ? [criteria.field, ...schemaPaths]
                : schemaPaths
              ).map(path => <option key={path} value={path}>{path}</option>)}
            </Select>
          )}
          <Select
            value={criteria.operator}
            onChange={e => set('operator', e.target.value)}
            aria-label="Operator"
            className="w-44"
          >
            {OPERATORS.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
          </Select>
          {criteria.operator !== 'EXISTS' && (
            <Input
              value={criteria.value}
              onChange={e => set('value', e.target.value)}
              onKeyDown={submitOnEnter}
              placeholder="Value"
              aria-label="Value"
              className="flex-1 min-w-[10rem]"
            />
          )}
          <Button variant="primary" icon="search" onClick={onSearch} disabled={!canSearch || searching}>
            {searching ? 'Searching…' : 'Search'}
          </Button>
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">search</span>
            <Input
              value={criteria.query}
              onChange={e => set('query', e.target.value)}
              onKeyDown={submitOnEnter}
              placeholder={criteria.mode === 'REGEX' ? 'Regular expression…' : 'Search the whole topic…'}
              aria-label="Search query"
              className="pl-9"
            />
          </div>
          <Button variant="primary" icon="search" onClick={onSearch} disabled={!canSearch || searching}>
            {searching ? 'Searching…' : 'Search'}
          </Button>
        </div>
      )}

      {error && (
        <p className="text-[12px] text-error flex items-center gap-1.5">
          <span className="material-symbols-outlined text-[16px]">error</span>
          {error}
        </p>
      )}

      {/* What the scan actually covered — a search that stops early must say so. */}
      {active && result && !error && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[12px] text-on-surface-variant border-t border-outline-variant/60 pt-2.5">
          <span>
            <span className="font-mono font-semibold text-on-surface tabular-nums">{loadedHits}</span>
            {result.matched > loadedHits && <span> of {result.matched}</span>} match
            {loadedHits === 1 ? '' : 'es'}
          </span>
          <span>
            <span className="font-mono tabular-nums">{result.scanned.toLocaleString()}</span> scanned
            {' '}in <span className="font-mono tabular-nums">{(result.elapsedMs / 1000).toFixed(1)}s</span>
          </span>
          <span className={result.exhausted ? 'text-success' : 'text-warning'}>
            {result.exhausted ? 'Whole range scanned' : STOP_REASONS[result.stopReason] ?? result.stopReason}
          </span>
          {!result.exhausted && result.stopReason !== 'ERROR' && (
            <Button variant="ghost" icon="more_horiz" onClick={onLoadMore} disabled={searching}>
              {searching ? 'Scanning…' : 'Continue scanning'}
            </Button>
          )}
          <Button variant="ghost" icon="close" onClick={onClear} disabled={searching}>
            Clear
          </Button>
        </div>
      )}

      {result?.warnings?.map((warning, i) => (
        <p key={i} className="text-[12px] text-warning flex items-start gap-1.5">
          <span className="material-symbols-outlined text-[16px] shrink-0">warning</span>
          {warning}
        </p>
      ))}
    </div>
  );
};

export default TopicSearchPanel;
