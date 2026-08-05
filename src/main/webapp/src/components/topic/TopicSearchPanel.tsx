import React from 'react';
import { Button, ErrorPanel, Input, Select } from '../ui';
import type { QueryErrorInfo } from '../../pages/queryError';
import {
  DIRECTIONS,
  SCAN_BUDGETS,
  SCOPES,
  describeAdvanced,
  describeCoverage,
  describeCriterion,
  describePartitionScope,
  describeScanShare,
  isFieldScoped,
  keyPartitioningApplies,
  nextScanAction,
  operatorsFor,
  suggestWidenings,
  switchMode,
  type ScanAction,
  type SearchCoverage,
  type SearchErrors,
  type SearchHistoryEntry,
  type SearchMode,
  type SearchSuggestion,
  type TopicSearchCriteria,
} from './topicSearch';

/** Ids stables : après validation, le formulaire doit pouvoir focaliser le premier champ fautif. */
export const FIELD_IDS = {
  query: 'topic-search-query',
  field: 'topic-search-field',
  value: 'topic-search-value',
} as const;

interface Props {
  /** Field paths from the inferred schema — the whole point is not having to guess them. */
  schemaPaths: string[];
  partitionCount: number;
  /** Taille estimée du topic, pour situer ce que la passe a réellement ouvert. */
  topicSize: number;
  criteria: TopicSearchCriteria;
  onChange: (criteria: TopicSearchCriteria) => void;
  onSearch: () => void;
  onContinue: (action: ScanAction) => void;
  onCancel: () => void;
  onCopyLink: () => void;
  onClear: () => void;
  onExport: (format: 'csv' | 'json') => void;
  onApply: (criteria: TopicSearchCriteria) => void;
  history: SearchHistoryEntry[];
  advancedOpen: boolean;
  onToggleAdvanced: (open: boolean) => void;
  searching: boolean;
  active: boolean;
  coverage: SearchCoverage | null;
  /**
   * Le critère de la passe affichée. La couverture, les exports et le bouton « en lire plus »
   * parlent de la recherche qui a tourné, pas du formulaire tel qu'il est en train d'être édité.
   */
  ranCriteria: TopicSearchCriteria | null;
  warnings: string[];
  error: QueryErrorInfo | null;
  errors: SearchErrors;
  /** Une passe abandonnée en cours de route : elle ne rapporte rien, et doit le dire. */
  stopped: boolean;
  loadedHits: number;
}

const TopicSearchPanel: React.FC<Props> = ({
  schemaPaths, partitionCount, topicSize, criteria, onChange, onSearch, onContinue, onCancel,
  onCopyLink, onClear, onExport, onApply, history, advancedOpen, onToggleAdvanced, searching,
  active, coverage, ranCriteria, warnings, error, errors, stopped, loadedHits,
}) => {
  const set = <K extends keyof TopicSearchCriteria>(key: K, value: TopicSearchCriteria[K]) =>
    onChange({ ...criteria, [key]: value });

  const fieldScoped = isFieldScoped(criteria.mode);
  const keyScoped = criteria.mode === 'KEY';
  const operators = operatorsFor(criteria.mode);

  const action = coverage && ranCriteria && !searching
    ? nextScanAction(coverage, ranCriteria)
    : null;
  const suggestions = active && ranCriteria && loadedHits === 0 && !searching
    ? suggestWidenings(ranCriteria)
    : [];
  const advanced = describeAdvanced(criteria);

  const togglePartition = (partition: number) => {
    const next = criteria.partitions.includes(partition)
      ? criteria.partitions.filter(p => p !== partition)
      : [...criteria.partitions, partition].sort((a, b) => a - b);
    onChange({ ...criteria, partitions: next });
  };

  return (
    // Un vrai <form> : Entrée soumet depuis n'importe quel champ, et la validation se fait en un
    // point plutôt qu'à travers un onKeyDown recopié sur chaque input.
    <form
      onSubmit={e => { e.preventDefault(); onSearch(); }}
      className="rounded-xl border border-outline-variant bg-surface-container-low p-3 space-y-3"
    >
      {/* Mode */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="inline-flex bg-surface-container border border-outline-variant rounded-md p-0.5">
          {(['CONTAINS', 'REGEX', 'FIELD', 'HEADER', 'KEY'] as SearchMode[]).map(mode => (
            <button
              key={mode}
              type="button"
              onClick={() => onChange(switchMode(criteria, mode))}
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
          className="w-36"
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

        {/* Le serveur ignore le partitionnement par clé dès qu'une partition est choisie à la
            main, et sans le dire : ne pas proposer les deux à la fois. */}
        {keyScoped && keyPartitioningApplies(criteria) && (
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
        <div className="flex flex-wrap items-start gap-2">
          <Select
            value={criteria.operator}
            onChange={e => set('operator', e.target.value)}
            aria-label="Operator"
            className="w-44"
          >
            {operators.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
          </Select>
          <ValueInput
            id={FIELD_IDS.value}
            value={criteria.value}
            onChange={value => set('value', value)}
            placeholder="Record key, e.g. order-88219"
            label="Record key"
            error={errors.value}
            className="flex-1 min-w-[12rem] font-mono"
          />
          <SearchButtons searching={searching} onCancel={onCancel} />
        </div>
      ) : fieldScoped ? (
        <div className="flex flex-wrap items-start gap-2">
          <div className="flex-1 min-w-[12rem]">
            {criteria.mode === 'HEADER' ? (
              // Les noms de headers ne viennent d'aucun schéma : saisie libre, pas de liste.
              <Input
                id={FIELD_IDS.field}
                value={criteria.field}
                onChange={e => set('field', e.target.value)}
                placeholder="Header name, e.g. correlation-id"
                aria-label="Header name"
                aria-invalid={Boolean(errors.field)}
                aria-describedby={errors.field ? `${FIELD_IDS.field}-error` : undefined}
                className="w-full"
              />
            ) : (
              <Select
                id={FIELD_IDS.field}
                value={criteria.field}
                onChange={e => set('field', e.target.value)}
                aria-label="Field path"
                aria-invalid={Boolean(errors.field)}
                aria-describedby={errors.field ? `${FIELD_IDS.field}-error` : undefined}
                className="w-full"
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
            <FieldError id={`${FIELD_IDS.field}-error`} message={errors.field} />
          </div>
          <Select
            value={criteria.operator}
            onChange={e => set('operator', e.target.value)}
            aria-label="Operator"
            className="w-44"
          >
            {operators.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
          </Select>
          {criteria.operator !== 'EXISTS' && (
            <ValueInput
              id={FIELD_IDS.value}
              value={criteria.value}
              onChange={value => set('value', value)}
              placeholder="Value"
              label="Value"
              error={errors.value}
              className="flex-1 min-w-[10rem]"
            />
          )}
          <SearchButtons searching={searching} onCancel={onCancel} />
        </div>
      ) : (
        <div className="flex items-start gap-2">
          <div className="flex-1">
            <div className="relative">
              <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">search</span>
              <Input
                id={FIELD_IDS.query}
                value={criteria.query}
                onChange={e => set('query', e.target.value)}
                placeholder={criteria.mode === 'REGEX' ? 'Regular expression…' : 'Search the whole topic…'}
                aria-label="Search query"
                aria-invalid={Boolean(errors.query)}
                aria-describedby={errors.query ? `${FIELD_IDS.query}-error` : undefined}
                className="pl-9 w-full"
              />
            </div>
            <FieldError id={`${FIELD_IDS.query}-error`} message={errors.query} />
          </div>
          <SearchButtons searching={searching} onCancel={onCancel} />
        </div>
      )}

      {/* Options avancées. Repliées par défaut : chacune se justifie, toutes ensemble elles font
          une barre que l'œil ne balaye plus. Ce qui n'est pas au défaut reste annoncé ici. */}
      <div className="border-t border-outline-variant/60 pt-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => onToggleAdvanced(!advancedOpen)}
            aria-expanded={advancedOpen}
            aria-controls="topic-search-advanced"
            className="flex items-center gap-1 text-[12px] font-medium text-on-surface-variant hover:text-on-surface transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[16px]">
              {advancedOpen ? 'expand_more' : 'chevron_right'}
            </span>
            Scan options
          </button>
          {!advancedOpen && advanced.map(note => (
            <span
              key={note}
              className="px-2 h-6 inline-flex items-center rounded border border-outline-variant text-[11px] text-on-surface-variant"
            >
              {note}
            </span>
          ))}
        </div>

        {advancedOpen && (
          <div id="topic-search-advanced" className="mt-2.5 space-y-2.5">
            <div className="flex flex-wrap items-center gap-3">
              {/* Par quel bout le scan entre. Le budget est borné, donc ce choix décide de ce qui
                  sera lu — et non simplement de l'ordre dans lequel on le lit. */}
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

              <Select
                value={String(criteria.maxScan)}
                onChange={e => set('maxScan', Number(e.target.value))}
                aria-label="Scan budget"
                className="w-44"
                title="How many records one pass may read before giving up. A larger budget is slower but reaches deeper."
              >
                {SCAN_BUDGETS.map(budget => (
                  <option key={budget.value} value={budget.value}>{budget.label}</option>
                ))}
              </Select>
            </div>

            {/* Partitions : on arrive souvent avec un numéro déjà en main. */}
            {partitionCount > 1 && (
              <div role="group" aria-label="Partitions" className="flex flex-wrap items-center gap-1.5">
                <span className="text-[11px] text-on-surface-variant mr-0.5">Partitions</span>
                <button
                  type="button"
                  onClick={() => set('partitions', [])}
                  aria-pressed={criteria.partitions.length === 0}
                  className={`px-2 h-6 rounded text-[11px] font-mono border transition-colors ${
                    criteria.partitions.length === 0
                      ? 'border-primary/40 bg-primary/15 text-primary'
                      : 'border-outline-variant text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  all
                </button>
                {Array.from({ length: partitionCount }, (_, partition) => {
                  const selected = criteria.partitions.includes(partition);
                  return (
                    <button
                      key={partition}
                      type="button"
                      onClick={() => togglePartition(partition)}
                      aria-pressed={selected}
                      className={`px-2 h-6 rounded text-[11px] font-mono border transition-colors ${
                        selected
                          ? 'border-primary/40 bg-primary/15 text-primary'
                          : 'border-outline-variant text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      p{partition}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Historique : un critère de champ se retape sinon à chaque incident. */}
      {history.length > 0 && !active && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-on-surface-variant mr-0.5">Recent</span>
          {history.map(entry => (
            <button
              key={`${entry.ranAt}`}
              type="button"
              onClick={() => onApply(entry.criteria)}
              title={`${entry.hits} hit${entry.hits === 1 ? '' : 's'} · ${new Date(entry.ranAt).toLocaleString()}`}
              className="px-2 h-6 rounded border border-outline-variant text-[11px] text-on-surface-variant hover:text-on-surface hover:border-outline transition-colors max-w-[18rem] truncate"
            >
              {describeCriterion(entry.criteria)}
            </button>
          ))}
        </div>
      )}

      {/* Un échec de recherche est une chose sur laquelle agir : il reste à l'écran, avec le
          texte du serveur, au lieu de passer en toast de trois secondes. */}
      {error && <ErrorPanel error={error} onRetry={onSearch} />}

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
            {describeScanShare(coverage.scanned, topicSize) && (
              <span> · {describeScanShare(coverage.scanned, topicSize)}</span>
            )}
          </span>
          {describePartitionScope(ranCriteria) && (
            <span className="text-warning">{describePartitionScope(ranCriteria)}</span>
          )}
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
          {searching && <span className="text-on-surface-variant">Scanning…</span>}
          <Button variant="ghost" icon="link" onClick={onCopyLink} title="Copy a link that reruns this search">
            Link
          </Button>
          {loadedHits > 0 && (
            <>
              <Button variant="ghost" icon="download" onClick={() => onExport('csv')} title="Export the hits as CSV">
                CSV
              </Button>
              <Button
                variant="ghost"
                icon="data_object"
                onClick={() => onExport('json')}
                title="Export the hits with the criterion and the coverage"
              >
                JSON
              </Button>
            </>
          )}
          <Button variant="ghost" icon="close" onClick={onClear} disabled={searching}>
            Clear
          </Button>
        </div>
      )}

      {/* Un résultat vide n'est pas une impasse : chaque piste est une relance à un clic. */}
      {suggestions.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 border-t border-outline-variant/60 pt-2.5">
          <span className="text-[11px] text-on-surface-variant mr-0.5">Try</span>
          {suggestions.map((suggestion: SearchSuggestion) => (
            <button
              key={suggestion.id}
              type="button"
              onClick={() => onApply(suggestion.criteria)}
              title={suggestion.hint}
              className="px-2 h-6 rounded border border-primary/30 bg-primary/10 text-[11px] text-primary hover:bg-primary/20 transition-colors"
            >
              {suggestion.label}
            </button>
          ))}
        </div>
      )}

      {warnings.map((warning, i) => (
        <p key={i} className="text-[12px] text-warning flex items-start gap-1.5">
          <span className="material-symbols-outlined text-[16px] shrink-0">warning</span>
          {warning}
        </p>
      ))}
    </form>
  );
};

const FieldError: React.FC<{ id: string; message?: string }> = ({ id, message }) =>
  message ? (
    <p id={id} role="alert" className="mt-1 text-[11px] text-error flex items-center gap-1">
      <span aria-hidden="true" className="material-symbols-outlined text-[13px]">error</span>
      {message}
    </p>
  ) : null;

const ValueInput: React.FC<{
  id: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  label: string;
  error?: string;
  className?: string;
}> = ({ id, value, onChange, placeholder, label, error, className }) => (
  <div className={className}>
    <Input
      id={id}
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      aria-label={label}
      aria-invalid={Boolean(error)}
      aria-describedby={error ? `${id}-error` : undefined}
      className="w-full"
    />
    <FieldError id={`${id}-error`} message={error} />
  </div>
);

/**
 * Un scan dure jusqu'à dix secondes par passe : tant qu'il tourne, le bouton d'à côté doit
 * permettre de l'arrêter, pas seulement de constater qu'on ne peut rien faire.
 *
 * Le bouton de recherche n'est plus désactivé quand le critère est incomplet : il l'était sans
 * rien dire de ce qui manquait. La soumission valide et désigne le champ fautif.
 */
const SearchButtons: React.FC<{
  searching: boolean;
  onCancel: () => void;
}> = ({ searching, onCancel }) => (
  <div className="flex items-center gap-2">
    <Button type="submit" variant="primary" icon="search" disabled={searching}>
      {searching ? 'Searching…' : 'Search'}
    </Button>
    {searching && (
      <Button variant="outline" icon="stop_circle" onClick={onCancel}>Stop</Button>
    )}
  </div>
);

export default TopicSearchPanel;
