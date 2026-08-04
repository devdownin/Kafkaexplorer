import React from 'react';
import { Button, Input, Select } from '../ui';
import {
  DIRECTIONS,
  OPERATORS,
  SCOPES,
  canRun,
  describeCoverage,
  isFieldScoped,
  nextScanAction,
  type ScanAction,
  type SearchCoverage,
  type SearchMode,
  type TopicSearchCriteria,
} from './topicSearch';

interface Props {
  /** Field paths from the inferred schema — the whole point is not having to guess them. */
  schemaPaths: string[];
  criteria: TopicSearchCriteria;
  onChange: (criteria: TopicSearchCriteria) => void;
  onSearch: () => void;
  onContinue: (action: ScanAction) => void;
  onCancel: () => void;
  onCopyLink: () => void;
  onClear: () => void;
  searching: boolean;
  active: boolean;
  coverage: SearchCoverage | null;
  /**
   * Le critère de la passe affichée. La couverture et le bouton « en lire plus » parlent de la
   * recherche qui a tourné, pas du formulaire tel qu'il est en train d'être édité.
   */
  ranCriteria: TopicSearchCriteria | null;
  warnings: string[];
  error: string | null;
  /** Une passe abandonnée en cours de route : elle ne rapporte rien, et doit le dire. */
  stopped: boolean;
  loadedHits: number;
}

const TopicSearchPanel: React.FC<Props> = ({
  schemaPaths, criteria, onChange, onSearch, onContinue, onCancel, onCopyLink, onClear,
  searching, active, coverage, ranCriteria, warnings, error, stopped, loadedHits,
}) => {
  const set = <K extends keyof TopicSearchCriteria>(key: K, value: TopicSearchCriteria[K]) =>
    onChange({ ...criteria, [key]: value });

  const fieldScoped = isFieldScoped(criteria.mode);
  const keyScoped = criteria.mode === 'KEY';
  const canSearch = canRun(criteria);

  const submitOnEnter = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && canSearch && !searching) onSearch();
  };

  const action = coverage && ranCriteria && !searching
    ? nextScanAction(coverage, ranCriteria)
    : null;

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

        {/* Par quel bout le scan entre. Le budget de scan est borné, donc ce choix décide de ce
            qui sera lu — et non simplement de l'ordre dans lequel on le lit. */}
        <Select
          value={criteria.direction}
          onChange={e => set('direction', e.target.value as TopicSearchCriteria['direction'])}
          aria-label="Scan direction"
          className="w-40"
          title={criteria.direction === 'NEWEST'
            ? 'Reads back from the most recent records. Older ones are only reached by scanning further back.'
            : 'Reads forward from the oldest record in range. On a large topic the scan budget is spent on the oldest history.'}
        >
          {DIRECTIONS.map(direction => (
            <option key={direction.value} value={direction.value}>{direction.label}</option>
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
          <SearchButtons
            canSearch={canSearch}
            searching={searching}
            onSearch={onSearch}
            onCancel={onCancel}
          />
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
          <SearchButtons
            canSearch={canSearch}
            searching={searching}
            onSearch={onSearch}
            onCancel={onCancel}
          />
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
          <SearchButtons
            canSearch={canSearch}
            searching={searching}
            onSearch={onSearch}
            onCancel={onCancel}
          />
        </div>
      )}

      {error && (
        <p className="text-[12px] text-error flex items-center gap-1.5">
          <span className="material-symbols-outlined text-[16px]">error</span>
          {error}
        </p>
      )}

      {/* Une passe interrompue est perdue côté client : le dire, plutôt que de laisser croire que
          les résultats affichés incluent ce qu'elle avait commencé à lire. */}
      {stopped && !searching && (
        <p className="text-[12px] text-warning flex items-center gap-1.5">
          <span className="material-symbols-outlined text-[16px]">cancel</span>
          Scan stopped — that pass was abandoned, so nothing from it is shown.
        </p>
      )}

      {/* What the scan actually covered — a search that stops early must say so, and the numbers
          are those of every pass together, not of the last one. */}
      {active && coverage && ranCriteria && !error && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[12px] text-on-surface-variant border-t border-outline-variant/60 pt-2.5">
          <span>
            <span className="font-mono font-semibold text-on-surface tabular-nums">{loadedHits}</span>
            {coverage.matched > loadedHits && <span> of {coverage.matched}</span>} match
            {loadedHits === 1 ? '' : 'es'}
          </span>
          <span>
            <span className="font-mono tabular-nums">{coverage.scanned.toLocaleString()}</span> scanned
            {' '}in <span className="font-mono tabular-nums">{(coverage.elapsedMs / 1000).toFixed(1)}s</span>
            {coverage.passes > 1 && <span> over {coverage.passes} passes</span>}
          </span>
          <span className={coverage.exhausted ? 'text-success' : 'text-warning'}>
            {describeCoverage(coverage, ranCriteria)}
          </span>
          {action && (
            <Button
              variant="ghost"
              icon={action.kind === 'DEEPEN' ? 'history' : 'more_horiz'}
              onClick={() => onContinue(action)}
              title={action.hint}
            >
              {action.label}
            </Button>
          )}
          {searching && (
            <span className="text-on-surface-variant">Scanning…</span>
          )}
          <Button variant="ghost" icon="link" onClick={onCopyLink} title="Copy a link that reruns this search">
            Link
          </Button>
          <Button variant="ghost" icon="close" onClick={onClear} disabled={searching}>
            Clear
          </Button>
        </div>
      )}

      {warnings.map((warning, i) => (
        <p key={i} className="text-[12px] text-warning flex items-start gap-1.5">
          <span className="material-symbols-outlined text-[16px] shrink-0">warning</span>
          {warning}
        </p>
      ))}
    </div>
  );
};

/**
 * Un scan dure jusqu'à dix secondes par passe : tant qu'il tourne, le bouton d'à côté doit
 * permettre de l'arrêter, pas seulement de constater qu'on ne peut rien faire.
 */
const SearchButtons: React.FC<{
  canSearch: boolean;
  searching: boolean;
  onSearch: () => void;
  onCancel: () => void;
}> = ({ canSearch, searching, onSearch, onCancel }) => (
  <div className="flex items-center gap-2">
    <Button variant="primary" icon="search" onClick={onSearch} disabled={!canSearch || searching}>
      {searching ? 'Searching…' : 'Search'}
    </Button>
    {searching && (
      <Button variant="outline" icon="stop_circle" onClick={onCancel}>Stop</Button>
    )}
  </div>
);

export default TopicSearchPanel;
