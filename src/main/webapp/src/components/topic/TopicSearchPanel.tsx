import React from 'react';
import { Button, Combobox, ErrorPanel, HelpTip, Input, Select, Tooltip } from '../ui';
import type { QueryErrorInfo } from '../../pages/queryError';
import {
  DIRECTIONS,
  HIT_CAPS,
  SCAN_BUDGETS,
  SCOPES,
  START_MODES,
  describeFollow,
  describeMode,
  describeOperator,
  describeStopReason,
  directionApplies,
  isPinned,
  raiseHitCapAction,
  switchStart,
  type StartMode,
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
  type PinnedSearch,
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
  fromTime: 'topic-search-from-time',
  fromOffset: 'topic-search-from-offset',
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
  /** Recherches épinglées sur ce topic — celles qu'on rejoue à chaque incident. */
  pinned: PinnedSearch[];
  onTogglePin: () => void;
  /** Valeurs observées au chemin choisi, tirées des messages déjà échantillonnés. */
  fieldValues: string[];
  following: boolean;
  onToggleFollow: () => void;
  followAvailable: boolean;
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
  onCopyLink, onClear, onExport, onApply, history, pinned, onTogglePin, fieldValues, following,
  onToggleFollow, followAvailable, advancedOpen, onToggleAdvanced, searching,
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
  const capAction = coverage && ranCriteria && !searching
    ? raiseHitCapAction(coverage, ranCriteria)
    : null;
  const advanced = describeAdvanced(criteria);
  const pinnedNow = ranCriteria ? isPinned(pinned, ranCriteria) : false;

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
          {/* Le nom du mode dit où l'on cherche, pas ce que cela implique — c'est pourtant ce
              qui fait choisir le mauvais et lire un zéro sans comprendre. */}
          {(['CONTAINS', 'REGEX', 'FIELD', 'HEADER', 'KEY'] as SearchMode[]).map(mode => (
            <Tooltip key={mode} content={describeMode(mode)} placement="bottom">
              <button
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
            </Tooltip>
          ))}
        </div>

        <div className="flex items-center gap-1">
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
          <HelpTip
            label="More information about the search range"
            content="Bounds how far back the scan may look. Reading from the newest end, the window
              raises the floor: the scan still starts at the most recent records and stops at the
              window's edge."
          />
        </div>

        <span className="flex items-center gap-1">
          <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
            <input
              type="checkbox"
              checked={criteria.caseSensitive}
              onChange={e => set('caseSensitive', e.target.checked)}
            />
            Case sensitive
          </label>
          <HelpTip
            label="More information about case sensitivity"
            content="Off by default: SHIPPED, shipped and Shipped all match. Turning it on is the
              most common way to get zero results without noticing."
          />
        </span>

        {/* Le serveur ignore le partitionnement par clé dès qu'une partition est choisie à la
            main, et sans le dire : ne pas proposer les deux à la fois. */}
        {keyScoped && keyPartitioningApplies(criteria) && (
          <span className="flex items-center gap-1">
            <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
              <input
                type="checkbox"
                checked={criteria.keyPartitioning}
                onChange={e => set('keyPartitioning', e.target.checked)}
              />
              Only this key&#39;s partition
            </label>
            <HelpTip
              label="More information about key partitioning"
              content="Reads only the partition the default partitioner would have chosen for this
                key — a twentieth of the work on a twenty-partition topic. It assumes the producer
                used that partitioner and that the partition count never changed, so a narrowed
                scan that finds nothing is not the same answer as a full one."
            />
          </span>
        )}

        {!fieldScoped && !keyScoped && (
          <>
            <span className="flex items-center gap-1">
              <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
                <input
                  type="checkbox"
                  checked={criteria.searchKey}
                  onChange={e => set('searchKey', e.target.checked)}
                />
                Search keys too
              </label>
              <HelpTip
                label="More information about searching record keys"
                content="Also matches the record key, not just the payload. On for a text or regex
                  search: a key is where an identifier usually lives."
              />
            </span>
            <span className="flex items-center gap-1">
              <label className="flex items-center gap-1.5 text-[12px] text-on-surface-variant cursor-pointer">
                <input
                  type="checkbox"
                  checked={criteria.searchHeaders}
                  onChange={e => set('searchHeaders', e.target.checked)}
                />
                Search headers too
              </label>
              <HelpTip
                label="More information about searching headers"
                content="Widens the search to every Kafka header value. Off by default, because
                  turning it on changes what an existing search returns — but a correlation id very
                  often travels only there."
              />
            </span>
          </>
        )}
      </div>

      {/* Criteria */}
      {keyScoped ? (
        <div className="flex flex-wrap items-start gap-2">
          <span className="flex items-center gap-1">
            <Select
              value={criteria.operator}
              onChange={e => set('operator', e.target.value)}
              aria-label="Operator"
              className="w-44"
            >
              {operators.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
            </Select>
            <HelpTip
              label="More information about the comparison"
              content={describeOperator(criteria.operator)}
            />
          </span>
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
          <span className="flex items-center gap-1">
            <Select
              value={criteria.operator}
              onChange={e => set('operator', e.target.value)}
              aria-label="Operator"
              className="w-44"
            >
              {operators.map(op => <option key={op.value} value={op.value}>{op.label}</option>)}
            </Select>
            <HelpTip
              label="More information about the comparison"
              content={describeOperator(criteria.operator)}
            />
          </span>
          {criteria.operator !== 'EXISTS' && (
            // Les valeurs observées au chemin choisi : on ne sait pas de mémoire si le statut
            // s'écrit SHIPPED, shipped ou Shipped, et c'est la cause la plus banale d'un zéro.
            // La saisie reste libre — l'échantillon ne montre pas tout ce que le topic contient.
            <div className="flex-1 min-w-[10rem]">
              {fieldValues.length > 0 ? (
                <Combobox
                  id={FIELD_IDS.value}
                  value={criteria.value}
                  onChange={value => set('value', value)}
                  options={fieldValues}
                  placeholder="Value"
                  aria-label="Value"
                  invalid={Boolean(errors.value)}
                  aria-describedby={errors.value ? `${FIELD_IDS.value}-error` : undefined}
                  onEnter={onSearch}
                />
              ) : (
                <Input
                  id={FIELD_IDS.value}
                  value={criteria.value}
                  onChange={e => set('value', e.target.value)}
                  placeholder="Value"
                  aria-label="Value"
                  aria-invalid={Boolean(errors.value)}
                  aria-describedby={errors.value ? `${FIELD_IDS.value}-error` : undefined}
                  className="w-full"
                />
              )}
              <FieldError id={`${FIELD_IDS.value}-error`} message={errors.value} />
            </div>
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
            <div className="flex flex-wrap items-start gap-3">
              {/* D'où le scan part. Une question d'incident prend presque toujours la forme
                  « à partir de telle heure » ou « à partir de tel offset ». */}
              <span className="flex items-center gap-1">
                <Select
                  value={criteria.startMode}
                  onChange={e => onChange(switchStart(criteria, e.target.value as StartMode))}
                  aria-label="Scan start"
                  className="w-40"
                >
                  {START_MODES.map(start => (
                    <option key={start.value} value={start.value}>{start.label}</option>
                  ))}
                </Select>
                <HelpTip
                  label="More information about where the scan starts"
                  content="A relative window, an absolute instant, or an offset. An instant behaves
                    like the window it replaces; an offset is a forward read, so it overrides the
                    scan direction — and applies to every partition scanned."
                />
              </span>

              {criteria.startMode === 'TIMESTAMP' && (
                <div>
                  <Input
                    id={FIELD_IDS.fromTime}
                    type="datetime-local"
                    value={criteria.fromTime}
                    onChange={e => set('fromTime', e.target.value)}
                    aria-label="Start date and time"
                    aria-invalid={Boolean(errors.fromTime)}
                    aria-describedby={errors.fromTime ? `${FIELD_IDS.fromTime}-error` : undefined}
                    className="w-52"
                  />
                  <FieldError id={`${FIELD_IDS.fromTime}-error`} message={errors.fromTime} />
                </div>
              )}

              {criteria.startMode === 'OFFSET' && (
                <div>
                  <Input
                    id={FIELD_IDS.fromOffset}
                    value={criteria.fromOffset}
                    onChange={e => set('fromOffset', e.target.value)}
                    inputMode="numeric"
                    placeholder="Offset, e.g. 128000"
                    aria-label="Start offset"
                    aria-invalid={Boolean(errors.fromOffset)}
                    aria-describedby={errors.fromOffset ? `${FIELD_IDS.fromOffset}-error` : undefined}
                    className="w-44 font-mono"
                  />
                  <FieldError id={`${FIELD_IDS.fromOffset}-error`} message={errors.fromOffset} />
                </div>
              )}

              {/* Par quel bout le scan entre. Le budget est borné, donc ce choix décide de ce qui
                  sera lu — et non simplement de l'ordre dans lequel on le lit. Un départ par offset
                  est une lecture vers l'avant : le choix ne s'y applique pas et disparaît. */}
              {directionApplies(criteria) && (
                <span className="flex items-center gap-1">
                  <Select
                    value={criteria.direction}
                    onChange={e => set('direction', e.target.value as TopicSearchCriteria['direction'])}
                    aria-label="Scan direction"
                    className="w-40"
                  >
                    {DIRECTIONS.map(direction => (
                      <option key={direction.value} value={direction.value}>{direction.label}</option>
                    ))}
                  </Select>
                  <HelpTip
                    label="More information about the scan direction"
                    content={criteria.direction === 'NEWEST'
                      ? 'Reads back from the most recent records. The budget is bounded, so this decides what gets read at all — older records are only reached by scanning further back.'
                      : 'Reads forward from the oldest record in range. On a topic with millions of records, the whole budget goes to the oldest history.'}
                  />
                </span>
              )}

              <span className="flex items-center gap-1">
                <Select
                  value={String(criteria.maxScan)}
                  onChange={e => set('maxScan', Number(e.target.value))}
                  aria-label="Scan budget"
                  className="w-44"
                >
                  {SCAN_BUDGETS.map(budget => (
                    <option key={budget.value} value={budget.value}>{budget.label}</option>
                  ))}
                </Select>
                <HelpTip
                  label="More information about the scan budget"
                  content="How many records one pass may read before giving up. Deciding once to
                    spend 100 000 beats clicking « continue » ten times; the server caps it at a
                    million."
                />
              </span>

              <span className="flex items-center gap-1">
                <Select
                  value={String(criteria.maxHits)}
                  onChange={e => set('maxHits', Number(e.target.value))}
                  aria-label="Hit cap"
                  className="w-44"
                >
                  {HIT_CAPS.map(cap => (
                    <option key={cap.value} value={cap.value}>{cap.label}</option>
                  ))}
                </Select>
                <HelpTip
                  label="More information about the hit cap"
                  content="How many matches one pass may report. Continuing reads on past the
                    records already scanned, so the cap — not the scan budget — decides whether the
                    matches it skipped are ever shown."
                />
              </span>
            </div>

            {criteria.startMode === 'OFFSET' && partitionCount > 1 && (
              <p className="text-[11px] text-on-surface-variant">
                That offset is applied to every partition scanned — on a {partitionCount}-partition
                topic, pick the one you mean below.
              </p>
            )}

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

      {/* Épinglées : l'historique se dévide, celles-ci restent. */}
      {pinned.length > 0 && !active && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">push_pin</span>
          {pinned.map(entry => (
            <button
              key={entry.pinnedAt}
              type="button"
              onClick={() => onApply(entry.criteria)}
              title={`Pinned ${new Date(entry.pinnedAt).toLocaleString()}`}
              className="px-2 h-6 rounded border border-primary/30 bg-primary/10 text-[11px] text-primary hover:bg-primary/20 transition-colors max-w-[18rem] truncate"
            >
              {describeCriterion(entry.criteria)}
            </button>
          ))}
        </div>
      )}

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
          <Tooltip content={describeStopReason(coverage.stopReason)}>
            <span
              tabIndex={0}
              className={`rounded ${coverage.exhausted ? 'text-success' : 'text-warning'}`}
            >
              {describeCoverage(coverage, ranCriteria)}
            </span>
          </Tooltip>
          {action && (
            <Tooltip content={action.hint}>
              <Button
                variant="ghost"
                icon={action.kind === 'DEEPEN' ? 'history' : 'more_horiz'}
                onClick={() => onContinue(action)}
              >
                {action.label}
              </Button>
            </Tooltip>
          )}
          {/* Les matches sautés au-delà du plafond sont hors d'atteinte d'une reprise, qui lit
              *après* la zone déjà parcourue : relever le plafond est le seul geste qui les ramène. */}
          {capAction && ranCriteria && (
            <Tooltip content={capAction.hint}>
              <Button
                variant="ghost"
                icon="expand_content"
                onClick={() => onApply({ ...ranCriteria, maxHits: capAction.maxHits })}
              >
                {capAction.label}
              </Button>
            </Tooltip>
          )}
          {searching && <span className="text-on-surface-variant">Scanning…</span>}
          {/* Reprendre au curseur *est* un tail : « suivre » ne fait que répéter ce geste. */}
          {followAvailable && (
            <Tooltip content={describeFollow(following, coverage)}>
              <Button
                variant={following ? 'secondary' : 'ghost'}
                icon={following ? 'pause' : 'play_arrow'}
                onClick={onToggleFollow}
                aria-pressed={following}
              >
                {following ? 'Following' : 'Follow'}
              </Button>
            </Tooltip>
          )}
          <Tooltip content="Copies a link that reruns this exact search — criterion, scan options and all.">
            <Button variant="ghost" icon="link" onClick={onCopyLink}>Link</Button>
          </Tooltip>
          <Tooltip content={pinnedNow
            ? 'Unpin this search.'
            : 'Keeps this search at hand while the eight-entry history scrolls past.'}
          >
            <Button
              variant="ghost"
              icon="push_pin"
              onClick={onTogglePin}
              aria-pressed={pinnedNow}
            >
              {pinnedNow ? 'Pinned' : 'Pin'}
            </Button>
          </Tooltip>
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
