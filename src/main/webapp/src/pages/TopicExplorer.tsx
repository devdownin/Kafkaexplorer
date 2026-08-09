import React, { useState, useEffect, useMemo } from 'react';
import { useParams, useLocation, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import '../monaco-setup';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import { Button, Badge, Stat, EmptyState, ErrorPanel, StatGridSkeleton, TableSkeleton, Table, useVirtualRows } from '../components/ui';
import { buildTraceLinkForKey } from './streamFlow';
import TopicSearchPanel, { FIELD_IDS } from '../components/topic/TopicSearchPanel';
import TopicConsumersPanel from '../components/topic/TopicConsumersPanel';
import { describeApiError, type QueryErrorInfo } from './queryError';
import { toCsv } from './resultExport';
import {
  FOLLOW_INTERVAL_MS,
  HIT_EXPORT_COLUMNS,
  NO_HIGHLIGHT,
  analyzeHits,
  announceResult,
  canFollow,
  buildRecordLink,
  buildSearchBody,
  buildSearchQuery,
  coverageOf,
  criteriaFromQuery,
  readCriteriaDraft,
  saveCriteriaDraft,
  describeHitInsight,
  effectiveScanBudget,
  emptyCriteria,
  exportFileName,
  filterHits,
  firstErrorField,
  isTypingTarget,
  nextSelectedRank,
  highlightFor,
  highlightedHeader,
  highlightedPath,
  hitsToRows,
  mergeWarnings,
  previewOf,
  pushSearchHistory,
  rankHits,
  readAdvancedOpen,
  readPinned,
  readSearchHistory,
  readViewMode,
  recordFromQuery,
  recordParam,
  revealsHeaders,
  searchToJson,
  sortHits,
  splitForHighlight,
  togglePinned,
  valuesAtPath,
  withRecord,
  validateCriteria,
  writeAdvancedOpen,
  writeViewMode,
  type HitSortKey,
  type MessageView,
  type PinnedSearch,
  type RankedHit,
  type RecordCoordinates,
  type ScanAction,
  type SearchCoverage,
  type SearchErrors,
  type SearchHighlight,
  type SearchHistoryEntry,
  type SearchPass,
  type TopicMessage,
  type TopicSearchCriteria,
  type TopicSearchResponse,
} from '../components/topic/topicSearch';

interface TopicDetail {
  topic: {
    name: string;
    partitions: number;
    estimatedSize: number;
    minOffsets: Record<number, number>;
    maxOffsets: Record<number, number>;
  };
  format: string;
  schema: Record<string, string>;
  ddl: string;
  samples: TopicMessage[];
}

/**
 * Renders text with everything the search matched on marked. Odd indexes are the matches.
 * Le surlignage suit le mode : littéral en recherche texte, motif en regex, valeur comparée en
 * recherche par champ / header / clé — un hit doit montrer *pourquoi* il en est un.
 */
const Highlighted: React.FC<{ text: string; highlight: SearchHighlight }> = ({
  text, highlight,
}) => {
  if (highlight.kind === 'NONE') return <>{text}</>;
  const parts = splitForHighlight(text, highlight);
  return (
    <>
      {parts.map((part, i) => (i % 2 === 1
        ? <mark key={i} className="bg-warning/40 text-on-surface rounded-sm">{part}</mark>
        : <React.Fragment key={i}>{part}</React.Fragment>
      ))}
    </>
  );
};

// ── Interactive JSON renderer ─────────────────────────────────────────────
const JsonNode: React.FC<{
  value: unknown;
  depth: number;
  keyName?: string;
  fieldPath?: string;  // full dot-notation path to this node (e.g. "customer.name")
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
  highlight?: SearchHighlight;
  /** Chemin comparé par la recherche : le nœud qui le porte est désigné dans l'arbre. */
  highlightPath?: string | null;
}> = ({
  value, depth, keyName, fieldPath, onFieldClick, selectedFields,
  highlight = NO_HIGHLIGHT, highlightPath,
}) => {
  const indent = '  '.repeat(depth);
  // Use fieldPath for selection/click if available, otherwise fall back to keyName
  const clickPath = fieldPath ?? keyName;
  const isSelected = clickPath !== undefined && selectedFields?.includes(clickPath);
  const targeted = Boolean(highlightPath) && clickPath === highlightPath;
  /**
   * Une recherche par champ ne marque que la valeur du champ comparé : marquer partout où le
   * texte apparaît désignerait des nœuds que la recherche n'a jamais regardés. Une recherche
   * texte, elle, s'applique bien à toutes les feuilles.
   */
  const leafHighlight = highlightPath ? (targeted ? highlight : NO_HIGHLIGHT) : highlight;
  const childProps = { highlight, highlightPath, onFieldClick, selectedFields };

  const keyEl = keyName !== undefined && onFieldClick && clickPath !== undefined ? (
    <button
      onClick={() => onFieldClick(clickPath)}
      title={isSelected ? 'Remove from SELECT' : 'Add to SELECT'}
      className={`font-mono text-[11px] font-semibold transition-colors rounded px-0.5 -mx-0.5 ${
        isSelected
          ? 'text-primary bg-primary/20 line-through'
          : 'text-warning hover:text-primary hover:bg-primary/10 cursor-pointer'
      } ${targeted ? 'ring-1 ring-warning/60' : ''}`}
    >
      "{keyName}"
    </button>
  ) : keyName !== undefined ? (
    <span className={`text-warning font-mono text-[11px] ${targeted ? 'ring-1 ring-warning/60 rounded' : ''}`}>
      "{keyName}"
    </span>
  ) : null;

  if (value === null) {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">null</span></span>;
  }
  if (typeof value === 'boolean') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-secondary font-mono text-[11px]">{String(value)}</span></span>;
  }
  if (typeof value === 'number') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-success font-mono text-[11px]"><Highlighted text={String(value)} highlight={leafHighlight} /></span></span>;
  }
  if (typeof value === 'string') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-primary font-mono text-[11px]">"<Highlighted text={value} highlight={leafHighlight} />"</span></span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">[]</span></span>;
    return (
      <span>
        {keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}
        <span className="text-on-surface-variant font-mono text-[11px]">{'['}</span>
        <div>
          {value.slice(0, 3).map((item, i) => (
            <div key={i} className="font-mono text-[11px]">
              {indent + '  '}
              <JsonNode value={item} depth={depth + 1} highlight={highlight} highlightPath={highlightPath} />
              {i < Math.min(value.length, 3) - 1 && <span className="text-on-surface-variant">,</span>}
            </div>
          ))}
          {value.length > 3 && <div className="text-on-surface-variant font-mono text-[11px]">{indent}  ... ({value.length} items)</div>}
        </div>
        <span className="text-on-surface-variant font-mono text-[11px]">{indent}{']'}</span>
      </span>
    );
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">{'{}'}</span></span>;
    return (
      <span>
        {keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}
        <span className="text-on-surface-variant font-mono text-[11px]">{'{'}</span>
        <div>
          {entries.map(([k, v], i) => {
            const childPath = fieldPath ? `${fieldPath}.${k}` : k;
            return (
              <div key={k} className="font-mono text-[11px]">
                {indent + '  '}
                <JsonNode
                  value={v}
                  depth={depth + 1}
                  keyName={k}
                  fieldPath={childPath}
                  {...childProps}
                />
                {i < entries.length - 1 && <span className="text-on-surface-variant">,</span>}
              </div>
            );
          })}
        </div>
        <span className="text-on-surface-variant font-mono text-[11px]">{indent}{'}'}</span>
      </span>
    );
  }
  return <span className="text-on-surface-variant font-mono text-[11px]">{String(value)}</span>;
};

// ── Interactive XML renderer — recursive DOM-based, tracks full paths ─────
const XmlViewer: React.FC<{
  xml: string;
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
  highlight?: SearchHighlight;
  highlightPath?: string | null;
}> = ({ xml, onFieldClick, selectedFields, highlight = NO_HIGHLIGHT, highlightPath }) => {
  const doc = useMemo(() => {
    try {
      const parser = new DOMParser();
      const parsed = parser.parseFromString(xml, 'text/xml');
      if (parsed.querySelector('parsererror')) return null;
      return parsed;
    } catch {
      return null;
    }
  }, [xml]);

  if (!doc) {
    return <span className="font-mono text-[11px] text-on-surface whitespace-pre-wrap break-all leading-relaxed">{xml}</span>;
  }

  const renderElement = (el: Element, parentPath: string, depth: number): React.ReactNode => {
    // Use tagName (qualified name, prefix included) to match the backend's getTagName()-based
    // field paths — the server parser is not namespace-aware, so a "<ns:order>" element is keyed
    // as "ns:order". localName would drop the prefix and break field selection on namespaced XML.
    const tag = el.tagName;
    const path = parentPath ? `${parentPath}.${tag}` : tag;
    const childEls = Array.from(el.childNodes).filter(n => n.nodeType === Node.ELEMENT_NODE) as Element[];
    const isLeaf = childEls.length === 0;
    const isSelected = selectedFields?.includes(path);
    const targeted = Boolean(highlightPath) && path === highlightPath;
    const leafHighlight = highlightPath ? (targeted ? highlight : NO_HIGHLIGHT) : highlight;
    const indentPx = depth * 12;

    const tagBtn = onFieldClick ? (
      <button
        onClick={() => onFieldClick(path)}
        title={isSelected ? 'Remove from SELECT' : 'Add to SELECT'}
        className={`transition-colors rounded px-0.5 -mx-0.5 ${
          isSelected
            ? 'text-primary bg-primary/20 line-through'
            : 'text-success hover:text-primary hover:bg-primary/10 cursor-pointer'
        } ${targeted ? 'ring-1 ring-warning/60' : ''}`}
      >
        {tag}
      </button>
    ) : <span className="text-success">{tag}</span>;

    if (isLeaf) {
      return (
        <div key={path} style={{ paddingLeft: `${indentPx}px` }} className="font-mono text-[11px]">
          <span className="text-on-surface-variant">{'<'}</span>{tagBtn}<span className="text-on-surface-variant">{'>'}</span>
          <span className="text-on-surface">
            <Highlighted text={el.textContent?.trim() ?? ''} highlight={leafHighlight} />
          </span>
          <span className="text-on-surface-variant">{`</${tag}>`}</span>
        </div>
      );
    }

    return (
      <div key={path} style={{ paddingLeft: `${indentPx}px` }} className="font-mono text-[11px]">
        <span className="text-on-surface-variant">{'<'}</span>{tagBtn}<span className="text-on-surface-variant">{'>'}</span>
        {childEls.map((child, i) => (
          <React.Fragment key={i}>{renderElement(child, path, depth + 1)}</React.Fragment>
        ))}
        <div style={{ paddingLeft: 0 }}><span className="text-on-surface-variant">{`</${tag}>`}</span></div>
      </div>
    );
  };

  const root = doc.documentElement;
  const rootChildren = Array.from(root.childNodes).filter(n => n.nodeType === Node.ELEMENT_NODE) as Element[];

  return (
    <div className="font-mono text-[11px] leading-relaxed">
      <div><span className="text-slate-500">{'<'}</span><span className="text-emerald-400/50">{root.tagName}</span><span className="text-slate-500">{'>'}</span></div>
      {rootChildren.map((child, i) => (
        <React.Fragment key={i}>{renderElement(child as Element, '', 0)}</React.Fragment>
      ))}
      <div><span className="text-slate-500">{`</${root.tagName}>`}</span></div>
    </div>
  );
};

const formatTimestamp = (ms: number): string => {
  if (!ms || ms < 0) return '—';
  return new Date(ms).toISOString().replace('T', ' ').replace('Z', '');
};

// ── MessageCard ────────────────────────────────────────────────────────────
/**
 * `React.memo` : le critère de recherche vit dans la page, donc chaque caractère tapé dans le
 * formulaire re-rendait la liste entière — et chaque carte re-parsait son payload au passage.
 * Toutes les props passées par la page sont mémoïsées de leur côté pour que la comparaison serve.
 */
const MessageCard: React.FC<{
  message: RankedHit;
  index: number;
  onCopy: (s: string) => void;
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
  highlight: SearchHighlight;
  /** Le header comparé par la recherche, à désigner parmi les autres. */
  highlightHeader?: string | null;
  /** Le chemin comparé, désigné dans la vue structurée. */
  highlightPath?: string | null;
  /** Vrai quand le match s'est joué dans les headers : ils ne peuvent plus rester en infobulle. */
  revealHeaders?: boolean;
  /** Le payload entier, une fois relu par ses coordonnées ; sinon la valeur tronquée du hit. */
  fullValue?: string;
  onLoadFull?: (message: TopicMessage) => void;
  loadingFull?: boolean;
  /** Copie un lien vers *ce* message — ce qu'on colle dans un ticket. */
  onCopyLink?: (message: TopicMessage) => void;
}> = React.memo(({
  message, index, onCopy, onFieldClick, selectedFields, highlight, highlightHeader, highlightPath,
  revealHeaders, fullValue, onLoadFull, loadingFull, onCopyLink,
}) => {
  const sample = fullValue ?? message.value ?? '';
  const marked = highlight.kind !== 'NONE';
  const [expanded, setExpanded] = useState(index < 3);
  // Raw view is what highlighting can mark up, so a card opens raw as soon as there is
  // something to highlight — the user should see *why* the record matched.
  const [raw, setRaw] = useState(marked);
  const headerEntries = Object.entries(message.headers ?? {});
  const [showHeaders, setShowHeaders] = useState(Boolean(revealHeaders));
  // Cards are keyed by list position, so when filtering shifts a different message into this slot
  // the instance is reused — reset the expand state to the default for the new content instead of
  // bleeding the previous message's state.
  useEffect(() => { setExpanded(index < 3); }, [sample, index]);
  useEffect(() => { setRaw(marked); }, [marked, sample]);
  useEffect(() => { setShowHeaders(Boolean(revealHeaders)); }, [revealHeaders, sample]);

  // Le parsing et le reformatage ne dépendent que du payload : les refaire à chaque rendu de la
  // page coûtait un JSON.parse + un JSON.stringify par carte affichée, à chaque frappe.
  const { parsed, isJson, isXml, formatted, lines } = useMemo(() => {
    let value: unknown = null;
    let json = false;
    let xml = false;
    try {
      value = JSON.parse(sample);
      // Only structured JSON (object/array) counts as JSON — a bare number/string/boolean that
      // happens to parse is shown as plain text rather than mislabelled with a JSON badge.
      json = value !== null && typeof value === 'object';
    } catch {
      xml = sample.trimStart().startsWith('<');
    }
    const text = json ? JSON.stringify(value, null, 2) : sample;
    return { parsed: value, isJson: json, isXml: xml, formatted: text, lines: text.split('\n') };
  }, [sample]);
  const needsCollapse = lines.length > 8;

  return (
    <div className="border-b border-outline-variant/40 last:border-b-0 group">
      {/* Record coordinates: without them a hit is a wall of text with no location */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 pt-2.5 text-[10px] font-mono text-on-surface-variant">
        {/* Le rang du hit dans le scan, pas sa place dans la liste : il survit au tri et au filtre. */}
        <span className="text-outline">#{message.rank}</span>
        <span title="Partition">p{message.partition}</span>
        <span title="Offset">@{message.offset.toLocaleString()}</span>
        <span title="Record timestamp">{formatTimestamp(message.timestamp)}</span>
        {message.key !== null && message.key !== undefined && (
          <span className="text-primary truncate max-w-[16rem]" title={`Key: ${message.key}`}>
            key=<Highlighted text={message.key} highlight={highlight} />
          </span>
        )}
        {headerEntries.length > 0 && (
          <button
            onClick={() => setShowHeaders(!showHeaders)}
            aria-expanded={showHeaders}
            className="flex items-center gap-0.5 hover:text-on-surface transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[12px]">
              {showHeaders ? 'expand_more' : 'chevron_right'}
            </span>
            {headerEntries.length} header{headerEntries.length > 1 ? 's' : ''}
          </button>
        )}
        {/* Le badge « truncated » ne pointait sur rien : le reste du payload n'était accessible
            par aucun chemin. Il se relit maintenant par ses coordonnées. */}
        {message.truncated && (fullValue
          ? <span className="text-success" title={`${message.valueBytes} chars`}>full record</span>
          : (
            <button
              onClick={() => onLoadFull?.(message)}
              disabled={loadingFull}
              className="text-warning hover:text-on-surface underline decoration-dotted transition-colors disabled:no-underline"
              title={`Value truncated (${message.valueBytes.toLocaleString()} chars) — read the whole record`}
            >
              {loadingFull ? 'loading…' : 'truncated — load full record'}
            </button>
          )
        )}
      </div>
      {/* Les headers portent très souvent le corrélatif qu'on cherche. Les laisser en infobulle
          rendait une recherche HEADER inspectable seulement à la souris, un hit à la fois. */}
      {showHeaders && headerEntries.length > 0 && (
        <dl className="px-4 pt-1.5 space-y-0.5">
          {headerEntries.map(([name, value]) => {
            const targeted = highlightHeader !== null && highlightHeader !== undefined
              && name.toLowerCase() === highlightHeader.toLowerCase();
            return (
              <div key={name} className="flex gap-2 font-mono text-[10px] leading-relaxed">
                <dt className={`shrink-0 ${targeted ? 'text-warning font-semibold' : 'text-on-surface-variant'}`}>
                  {name}
                </dt>
                <dd className="min-w-0 break-all text-on-surface">
                  <Highlighted text={value ?? ''} highlight={highlight} />
                </dd>
              </div>
            );
          })}
        </dl>
      )}
      <div className="flex items-start gap-3 px-4 pb-4 pt-1 hover:bg-surface-container-high/40 transition-colors">
        <div className="flex-1 min-w-0 overflow-x-auto">
          {raw ? (
            <pre className={`font-mono text-[11px] text-on-surface whitespace-pre-wrap break-all leading-relaxed ${!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}`}>
              <Highlighted text={formatted} highlight={highlight} />
            </pre>
          ) : isJson && parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (
            <div className={`leading-relaxed ${!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}`}>
              <JsonNode
                value={parsed}
                depth={0}
                onFieldClick={onFieldClick}
                selectedFields={selectedFields}
                highlight={highlight}
                highlightPath={highlightPath}
              />
            </div>
          ) : isXml ? (
            <div className={!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}>
              <XmlViewer
                xml={sample}
                onFieldClick={onFieldClick}
                selectedFields={selectedFields}
                highlight={highlight}
                highlightPath={highlightPath}
              />
            </div>
          ) : (
            <pre className={`font-mono text-[11px] text-on-surface whitespace-pre-wrap break-all leading-relaxed ${!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}`}>
              {formatted}
            </pre>
          )}
          {needsCollapse && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-2 text-[10px] font-bold text-primary hover:underline"
            >
              {expanded ? 'Collapse' : `Show all ${lines.length} lines`}
            </button>
          )}
        </div>
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity shrink-0">
          {isJson && <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-primary/10 text-primary">JSON</span>}
          {isXml && <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-success/10 text-success">XML</span>}
          {(isJson || isXml) && (
            <button
              onClick={() => setRaw(!raw)}
              className="p-1.5 text-on-surface-variant hover:text-primary hover:bg-primary/10 rounded transition-colors"
              title={raw ? 'Structured view' : 'Raw view'}
              aria-pressed={raw}
            >
              <span className="material-symbols-outlined text-base">{raw ? 'account_tree' : 'data_object'}</span>
            </button>
          )}
          {/* Le trajet inverse du lien qu'un saut de Stream Flow pose ici : depuis un message,
              suivre sa clé à travers le cluster. La clé est connue exactement, donc la trace
              part en comparaison entière plutôt qu'en recherche de sous-chaîne. */}
          {message.key !== null && message.key !== undefined && message.key !== '' && (
            <Link
              to={buildTraceLinkForKey(message.key)}
              className="p-1.5 text-on-surface-variant hover:text-primary hover:bg-primary/10 rounded transition-colors"
              title={`Trace ${message.key} across topics`}
              aria-label={`Trace ${message.key} across topics`}
            >
              <span aria-hidden="true" className="material-symbols-outlined text-base">route</span>
            </Link>
          )}
          {onCopyLink && (
            <button
              onClick={() => onCopyLink(message)}
              className="p-1.5 text-on-surface-variant hover:text-primary hover:bg-primary/10 rounded transition-colors"
              title={`Copy a link to p${message.partition}@${message.offset}`}
              aria-label="Copy a link to this record"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-base">link</span>
            </button>
          )}
          <button
            onClick={() => onCopy(formatted)}
            className="p-1.5 text-on-surface-variant hover:text-primary hover:bg-primary/10 rounded transition-colors"
            title="Copy"
          >
            <span className="material-symbols-outlined text-base">content_copy</span>
          </button>
        </div>
      </div>
    </div>
  );
});
MessageCard.displayName = 'MessageCard';

/** Hauteur de ligne fixe : c'est ce qui permet de ne monter que les lignes visibles. */
const ROW_HEIGHT = 32;

/**
 * Vue compacte : une ligne par record, le détail à la sélection.
 *
 * Une recherche ramène jusqu'à cent hits et « continuer » les empile ; en cartes, chacun rend un
 * arbre JSON complet, ce qui se parcourt mal et coûte cher. Les lignes sont de hauteur fixe et
 * fenêtrées (`useVirtualRows`), donc trois cents hits ne montent pas trois cents lignes.
 */
const SORT_COLUMNS: { key: HitSortKey; label: string; align: string }[] = [
  { key: 'rank', label: '#', align: 'text-left' },
  { key: 'partition', label: 'P', align: 'text-left' },
  { key: 'offset', label: 'Offset', align: 'text-right' },
  { key: 'timestamp', label: 'Timestamp', align: 'text-left' },
  { key: 'key', label: 'Key', align: 'text-left' },
];

const MessageTable: React.FC<{
  messages: RankedHit[];
  highlight: SearchHighlight;
  /** Rang du hit sélectionné — un index de ligne ne survivrait ni au tri ni au filtre. */
  selected: number | null;
  onSelect: (rank: number) => void;
  sortKey: HitSortKey;
  sortDesc: boolean;
  onSort: (key: HitSortKey) => void;
}> = ({ messages, highlight, selected, onSelect, sortKey, sortDesc, onSort }) => {
  const scrollRef = React.useRef<HTMLDivElement>(null);
  const rows = useVirtualRows(scrollRef, messages.length, ROW_HEIGHT);

  return (
    <div
      ref={scrollRef}
      className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-auto max-h-[26rem]"
    >
      <table className="w-full text-[11px] font-mono border-collapse">
        <thead className="sticky top-0 z-10 bg-surface-container-high">
          <tr className="text-[10px] uppercase tracking-widest text-on-surface-variant">
            {SORT_COLUMNS.map(column => (
              <th
                key={column.key}
                scope="col"
                aria-sort={sortKey === column.key ? (sortDesc ? 'descending' : 'ascending') : 'none'}
                className={`${column.align} font-medium px-3 py-2`}
              >
                <button
                  type="button"
                  onClick={() => onSort(column.key)}
                  className="inline-flex items-center gap-0.5 hover:text-on-surface transition-colors uppercase tracking-widest"
                >
                  {column.label}
                  {sortKey === column.key && (
                    <span aria-hidden="true" className="material-symbols-outlined text-[13px]">
                      {sortDesc ? 'arrow_drop_down' : 'arrow_drop_up'}
                    </span>
                  )}
                </button>
              </th>
            ))}
            <th scope="col" className="text-left font-medium px-3 py-2">Preview</th>
          </tr>
        </thead>
        <tbody>
          {rows.padTop > 0 && (
            <tr aria-hidden="true" style={{ height: rows.padTop }}><td colSpan={6} /></tr>
          )}
          {messages.slice(rows.start, rows.end).map(message => {
            const isSelected = selected === message.rank;
            return (
              <tr
                key={message.rank}
                tabIndex={0}
                aria-selected={isSelected}
                onClick={() => onSelect(message.rank)}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelect(message.rank);
                  }
                }}
                style={{ height: ROW_HEIGHT }}
                className={`cursor-pointer transition-colors ${
                  isSelected ? 'bg-primary/15 text-on-surface' : 'hover:bg-surface-container-high/60'
                }`}
              >
                <td className="px-3 whitespace-nowrap text-outline">{message.rank}</td>
                <td className="px-3 whitespace-nowrap text-on-surface-variant">p{message.partition}</td>
                <td className="px-3 whitespace-nowrap text-right text-on-surface-variant tabular-nums">
                  {message.offset.toLocaleString()}
                </td>
                <td className="px-3 whitespace-nowrap text-on-surface-variant">
                  {formatTimestamp(message.timestamp)}
                </td>
                <td className="px-3 whitespace-nowrap text-primary max-w-[12rem] truncate">
                  {message.key === null || message.key === undefined
                    ? '—'
                    : <Highlighted text={message.key} highlight={highlight} />}
                </td>
                <td className="px-3 whitespace-nowrap text-on-surface max-w-0 w-full truncate">
                  <Highlighted text={previewOf(message.value)} highlight={highlight} />
                </td>
              </tr>
            );
          })}
          {rows.padBottom > 0 && (
            <tr aria-hidden="true" style={{ height: rows.padBottom }}><td colSpan={6} /></tr>
          )}
        </tbody>
      </table>
    </div>
  );
};

const TopicExplorer: React.FC = () => {
  const { name } = useParams<{ name: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { toast } = useToast();
  const [data, setData] = useState<TopicDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [readMode, setReadMode] = useState('earliest-offset');
  const [activeTab, setActiveTab] = useState<'samples' | 'ddl' | 'schema' | 'partitions' | 'consumers'>('samples');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);

  // Server-side search state. `hits` accumulates across passes so "continue scanning"
  // appends instead of replacing what the user is already reading.
  /*
   * Le critère non lancé survit au changement de page : l'URL ne porte qu'une recherche
   * *exécutée*, donc un critère à moitié tapé qu'on quitte pour aller vérifier un nom de champ
   * ailleurs disparaissait. Une recherche portée par l'URL passe par-dessus, plus bas.
   */
  const [criteria, setCriteria] = useState<TopicSearchCriteria>(
    () => readCriteriaDraft(name ?? '') ?? emptyCriteria);
  const [searchResult, setSearchResult] = useState<TopicSearchResponse | null>(null);
  const [coverage, setCoverage] = useState<SearchCoverage | null>(null);
  /** Le critère de la passe affichée : le formulaire peut avoir bougé depuis. */
  const [ranCriteria, setRanCriteria] = useState<TopicSearchCriteria | null>(null);
  const [hits, setHits] = useState<TopicMessage[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<QueryErrorInfo | null>(null);
  const [fieldErrors, setFieldErrors] = useState<SearchErrors>({});
  const [searchActive, setSearchActive] = useState(false);
  const [stopped, setStopped] = useState(false);
  const [history, setHistory] = useState<SearchHistoryEntry[]>([]);
  const [advancedOpen, setAdvancedOpen] = useState(readAdvancedOpen);
  const [view, setView] = useState<MessageView>(readViewMode);
  /** Rang du hit ouvert en vue tableau — stable au tri comme au filtre, contrairement à un index. */
  const [selectedRank, setSelectedRank] = useState<number | null>(null);
  const [hitFilter, setHitFilter] = useState('');
  const [sortKey, setSortKey] = useState<HitSortKey>('rank');
  const [sortDesc, setSortDesc] = useState(false);
  /**
   * Les avertissements de toutes les passes : ceux de la seule dernière réponse s'effaçaient à la
   * reprise alors qu'ils décrivaient toujours une partie des résultats affichés.
   */
  const [warnings, setWarnings] = useState<string[]>([]);
  /** Payloads relus entiers, par `partition-offset`. */
  const [fullRecords, setFullRecords] = useState<Record<string, string>>({});
  const [loadingRecord, setLoadingRecord] = useState<string | null>(null);
  const [pinned, setPinned] = useState<PinnedSearch[]>([]);
  /** Le message ouvert par un lien `?record=p:offset`, avec ce qui a empêché de le lire. */
  const [linkedRecord, setLinkedRecord] = useState<TopicMessage | null>(null);
  const [linkedError, setLinkedError] = useState<QueryErrorInfo | null>(null);
  const [linkedLoading, setLinkedLoading] = useState(false);
  /** Reprises automatiques : le curseur pointe vers l'avant, donc répéter la reprise est un tail. */
  const [following, setFollowing] = useState(false);
  /** La passe en vol, pour pouvoir l'abandonner : un scan dure jusqu'à dix secondes. */
  const abortRef = React.useRef<AbortController | null>(null);
  /** Numéro de passe : ce qui revient d'une passe remplacée ne doit pas atterrir sur la suivante. */
  const runIdRef = React.useRef(0);
  /** La query string que la page a écrite elle-même, pour ne pas la relire comme un ordre. */
  const lastAppliedSearch = React.useRef<string | null>(null);
  /** Les coordonnées portées par l'URL, lues à chaque rendu et relues par la réécriture. */
  const linkedCoordinatesRef = React.useRef<RecordCoordinates | null>(null);
  /**
   * La liste affichée, pour le gestionnaire clavier : il est posé une fois, et le lire par une
   * ref évite de le reposer à chaque frappe dans le filtre.
   */
  const displayedMessagesRef = React.useRef<RankedHit[]>([]);

  useEffect(() => () => abortRef.current?.abort(), []);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- historique et épingles relus pour ce topic
    setHistory(readSearchHistory(name ?? ''));
    setPinned(readPinned(name ?? ''));
  }, [name]);

  // Mémoïsés parce que `MessageCard` l'est : une callback recréée à chaque rendu annulerait la
  // comparaison de props et la carte re-parserait son payload comme avant.
  const linkedCoordinates = useMemo(() => recordFromQuery(location.search), [location.search]);
  // Écrire une ref pendant le rendu est un effet de bord dans une fonction censée être pure.
  useEffect(() => { linkedCoordinatesRef.current = linkedCoordinates; }, [linkedCoordinates]);

  // Mémoïsés parce que `MessageCard` l'est : une callback recréée à chaque rendu annulerait la
  // comparaison de props et la carte re-parserait son payload comme avant.
  const toggleField = React.useCallback((field: string) => {
    setSelectedFields(prev =>
      prev.includes(field) ? prev.filter(f => f !== field) : [...prev, field]
    );
  }, []);

  const copyToClipboard = React.useCallback((text: string) => {
    navigator.clipboard.writeText(text);
    toast('Copied to clipboard', 'success');
  }, [toast]);

  /**
   * Relit un record entier par ses coordonnées. Un hit de recherche est tronqué pour que cent
   * hits restent une petite réponse, ce qui laissait le badge « truncated » pointer sur un reste
   * que rien ne pouvait aller chercher.
   */
  const fetchRecord = React.useCallback(
    (partition: number, offset: number) => axios.get<TopicMessage>(
      `/api/topic/${encodeURIComponent(name ?? '')}/record`, { params: { partition, offset } }),
    [name]);

  const copyRecordLink = React.useCallback((message: TopicMessage) => {
    void navigator.clipboard.writeText(
      buildRecordLink(window.location.origin, location.pathname, message));
    toast(`Link to p${message.partition}@${message.offset} copied`, 'success');
  }, [location.pathname, toast]);

  const loadFullRecord = React.useCallback(async (message: TopicMessage) => {
    const id = `${message.partition}-${message.offset}`;
    setLoadingRecord(id);
    try {
      const response = await fetchRecord(message.partition, message.offset);
      setFullRecords(prev => ({ ...prev, [id]: response.data.value ?? '' }));
      if (response.data.truncated) {
        toast(`Record is ${response.data.valueBytes.toLocaleString()} chars — still capped`, 'info');
      }
    } catch (e) {
      // Compacté, purgé par la rétention, ou hors plage : le serveur répond 404 avec sa raison.
      toast(describeApiError(e, 'Could not read the record').title, 'error');
    } finally {
      setLoadingRecord(current => (current === id ? null : current));
    }
  }, [fetchRecord, toast]);

  useEffect(() => {
    // Guard against out-of-order responses: toggling read mode quickly fires several requests,
    // and without this a slower one could overwrite the newer result with stale data.
    let active = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- chargement du topic
    setLoading(true);
    axios.get(`/api/topic/${encodeURIComponent(name ?? '')}?readMode=${readMode}`)
      .then(res => { if (active) setData(res.data); })
      .catch(() => { if (active) toast('Failed to load topic details', 'error'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- toast is stable
  }, [name, readMode]);

  // Manual retry from the error banner — last-wins is fine here (only shown on the error state).
  const fetchTopicDetails = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/topic/${encodeURIComponent(name ?? '')}?readMode=${readMode}`);
      setData(response.data);
    } catch {
      toast('Failed to load topic details', 'error');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Une passe de scan.
   *
   * `resume` reprend au curseur de la passe précédente : elle lit du terrain neuf, donc ses hits
   * s'ajoutent et sa couverture se cumule. Toute autre passe (première recherche, élargissement du
   * budget) relit depuis le même bout et remplace donc ce qui était affiché — sinon la couverture
   * compterait deux fois des records lus une seule.
   *
   * `applied` permet de lancer une recherche avec un critère qui n'est pas encore passé par
   * l'état React — c'est le cas de celui qui arrive dans l'URL, appliqué et exécuté d'un trait.
   */
  const runSearch = async (
    pass: { resume?: boolean; maxScan?: number } = {},
    applied?: TopicSearchCriteria,
  ) => {
    const active = applied ?? criteria;
    const resume = Boolean(pass.resume);

    // Tous les champs validés d'un coup, et le curseur sur le premier fautif : le bouton se
    // contentait d'être grisé, sans dire ce qu'il attendait.
    const invalid = validateCriteria(active);
    setFieldErrors(invalid);
    if (Object.keys(invalid).length > 0) {
      const target = firstErrorField(invalid);
      if (target) document.getElementById(FIELD_IDS[target])?.focus();
      return;
    }

    const runId = ++runIdRef.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setSearching(true);
    setSearchError(null);
    setStopped(false);
    try {
      const request: SearchPass = {
        // Resuming continues exactly where the previous pass stopped.
        cursor: resume ? searchResult?.nextCursor ?? null : null,
        maxScan: pass.maxScan ?? null,
      };
      const response = await axios.post<TopicSearchResponse>(
        `/api/topic/${encodeURIComponent(name ?? '')}/search`,
        buildSearchBody(active, request),
        { signal: controller.signal });
      if (runIdRef.current !== runId) return;
      setSearchResult(response.data);
      setCoverage(prev => coverageOf(response.data, prev, resume, effectiveScanBudget(active, request)));
      setWarnings(prev => mergeWarnings(prev, response.data.warnings ?? [], resume));
      const nextHits = resume ? [...hits, ...response.data.hits] : response.data.hits;
      setHits(nextHits);
      setRanCriteria(active);
      setSearchActive(true);
      setSelectedRank(null);
      setHitFilter('');
      // Une reprise n'est pas une nouvelle recherche — et en mode « suivre », l'historique serait
      // réécrit toutes les cinq secondes.
      if (!resume) {
        setHistory(pushSearchHistory({
          topic: name ?? '', criteria: active, ranAt: Date.now(), hits: nextHits.length,
        }));
      }
      // L'URL décrit désormais la recherche affichée : un lien collé dans un ticket la rejoue.
      // L'URL décrit la recherche *et* le message ouvert : l'une ne doit pas effacer l'autre.
      const search = withRecord(buildSearchQuery(active), linkedCoordinatesRef.current);
      lastAppliedSearch.current = search;
      navigate({ pathname: location.pathname, search }, { replace: true });
    } catch (e) {
      if (runIdRef.current !== runId) return;
      // Une passe abandonnée n'a rien à dire : ni erreur, ni résultat. `stopped` le signale.
      if (axios.isCancel(e) || controller.signal.aborted) return;
      // Suivre une recherche qui échoue relancerait l'échec toutes les cinq secondes.
      setFollowing(false);
      setSearchError(describeApiError(e, 'Search failed'));
      if (!resume) {
        setHits([]);
        setSearchResult(null);
        setCoverage(null);
      }
    } finally {
      if (runIdRef.current === runId) setSearching(false);
    }
  };

  const cancelSearch = () => {
    setFollowing(false);
    if (!abortRef.current) return;
    abortRef.current.abort();
    setStopped(true);
    setSearching(false);
  };

  const continueSearch = (action: ScanAction) => {
    void runSearch(action.kind === 'RESUME'
      ? { resume: true }
      : { maxScan: action.maxScan }, ranCriteria ?? criteria);
  };

  const copySearchLink = () => {
    const target = ranCriteria ?? criteria;
    const url = `${window.location.origin}${location.pathname}${buildSearchQuery(target)}`;
    void navigator.clipboard.writeText(url);
    toast('Search link copied', 'success');
  };

  /** Applique un critère (historique, relance suggérée) et l'exécute d'un trait. */
  const applyCriteria = (next: TopicSearchCriteria) => {
    setCriteria(next);
    void runSearch({}, next);
  };

  const exportHits = (format: 'csv' | 'json') => {
    const target = ranCriteria ?? criteria;
    const content = format === 'csv'
      ? toCsv(HIT_EXPORT_COLUMNS, hitsToRows(hits))
      // Un export collé dans un ticket doit dire ce qu'il a couvert, pas seulement ce qu'il a vu.
      : searchToJson(name ?? '', target, coverage, hits, warnings);
    const blob = new Blob([content], {
      type: format === 'csv' ? 'text/csv;charset=utf-8' : 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = exportFileName(name ?? '', format);
    anchor.click();
    URL.revokeObjectURL(url);
    toast(`Exported as ${format.toUpperCase()}`, 'success');
  };

  /**
   * Le mode « suivre » : une reprise au curseur toutes les cinq secondes. La passe n'est
   * programmée qu'une fois la précédente terminée — deux passes concurrentes s'annuleraient
   * l'une l'autre, puisque chaque recherche abandonne celle en vol.
   */
  useEffect(() => {
    if (!following || searching || !searchActive) return;
    const timer = window.setTimeout(() => { void runSearch({ resume: true }); }, FOLLOW_INTERVAL_MS);
    return () => window.clearTimeout(timer);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- se réarme après chaque passe
  }, [following, searching, searchActive, searchResult]);

  /**
   * Raccourcis : `/` amène au champ de recherche, `j` / `k` parcourent les hits en vue tableau,
   * `Échap` désélectionne. Ignorés dès qu'une saisie a le focus — sinon « j » deviendrait une
   * commande au milieu d'un mot.
   */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTypingTarget(event.target)) return;
      if (event.key === '/') {
        const target = [FIELD_IDS.query, FIELD_IDS.field, FIELD_IDS.value]
          .map(id => document.getElementById(id))
          .find(Boolean);
        if (target) {
          event.preventDefault();
          target.focus();
        }
        return;
      }
      if (event.key === 'Escape') {
        setSelectedRank(null);
        return;
      }
      if ((event.key === 'j' || event.key === 'k') && view === 'table') {
        event.preventDefault();
        setSelectedRank(current =>
          nextSelectedRank(displayedMessagesRef.current, current, event.key === 'j' ? 1 : -1));
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [view]);

  const togglePin = () => {
    const target = ranCriteria ?? criteria;
    const next = togglePinned(name ?? '', target);
    setPinned(next);
    toast(next.length > pinned.length ? 'Search pinned' : 'Search unpinned', 'success');
  };

  const changeView = (next: MessageView) => {
    setView(next);
    writeViewMode(next);
  };

  const toggleAdvanced = (open: boolean) => {
    setAdvancedOpen(open);
    writeAdvancedOpen(open);
  };

  /** Un second clic sur la même colonne inverse le sens, comme partout ailleurs dans l'app. */
  const changeSort = (key: HitSortKey) => {
    if (key === sortKey) {
      setSortDesc(!sortDesc);
      return;
    }
    setSortKey(key);
    setSortDesc(false);
  };

  // Le brouillon suit la frappe, et s'efface de lui-même quand le formulaire revient à vide.
  useEffect(() => {
    if (!name) return;
    saveCriteriaDraft(name, criteria);
  }, [name, criteria]);

  /**
   * Une recherche décrite dans l'URL s'applique et s'exécute à l'ouverture : c'est ce qui permet
   * à un saut de la page Stream Flow d'amener directement sur les messages concernés. Une seule
   * fois — l'utilisateur reste maître du formulaire ensuite.
   */
  useEffect(() => {
    // La page réécrit l'URL après chaque recherche : sans cette garde, la réécriture serait relue
    // comme un nouveau critère et relancerait la recherche qui vient de la produire, en boucle.
    if (lastAppliedSearch.current === location.search) return;
    lastAppliedSearch.current = location.search;
    const preset = criteriaFromQuery(location.search);
    if (!preset) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- recherche portée par l'URL
    setCriteria(preset);
    void runSearch({}, preset);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- réagit au seul critère porté par l'URL
  }, [location.search]);

  /**
   * Un lien `?record=p:offset` ouvre *ce* message. Le paramètre est indépendant du critère de
   * recherche : une URL qui ne porte que lui n'exécute aucune recherche, et une URL qui porte les
   * deux montre les deux. La clé de l'effet est le couple de coordonnées, donc réécrire la même
   * URL ne relit rien.
   */
  const linkedKey = linkedCoordinates ? recordParam(linkedCoordinates) : '';
  useEffect(() => {
    if (!linkedCoordinates) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- lecture du record désigné par l'URL
      setLinkedRecord(null);
      setLinkedError(null);
      return;
    }
    let active = true;
    setLinkedLoading(true);
    setLinkedError(null);
    fetchRecord(linkedCoordinates.partition, linkedCoordinates.offset)
      .then(response => { if (active) setLinkedRecord(response.data); })
      .catch(e => {
        if (!active) return;
        setLinkedRecord(null);
        // 404 : compacté, purgé par la rétention, ou hors plage — le serveur dit lequel.
        setLinkedError(describeApiError(e, 'Could not read that record'));
      })
      .finally(() => { if (active) setLinkedLoading(false); });
    return () => { active = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- les coordonnées sont la clé
  }, [linkedKey, fetchRecord]);

  const closeLinkedRecord = () => {
    navigate({ pathname: location.pathname, search: withRecord(location.search, null) },
      { replace: true });
  };

  const clearSearch = () => {
    setFollowing(false);
    abortRef.current?.abort();
    runIdRef.current++;
    setSearching(false);
    setSearchActive(false);
    setSearchResult(null);
    setCoverage(null);
    setRanCriteria(null);
    setHits([]);
    setSearchError(null);
    setFieldErrors({});
    setStopped(false);
    setSelectedRank(null);
    setHitFilter('');
    setWarnings([]);
    // Le critère reste dans le formulaire : effacer les résultats ne doit pas effacer la question
    // qu'on venait de poser, souvent longue à ressaisir (un chemin de champ, une regex).
    // L'URL, elle, annonçait la recherche affichée : elle ne doit plus décrire ce qui n'est plus là.
    lastAppliedSearch.current = '';
    navigate({ pathname: location.pathname, search: '' }, { replace: true });
  };

  const openInEditor = () => {
    const cols = selectedFields.length > 0 ? selectedFields.join(', ') : '*';
    navigate(`/query?sql=${encodeURIComponent(`SELECT ${cols} FROM "${name}" LIMIT 50`)}`);
  };

  // The list shows search hits when a search is active, the sampled messages otherwise. Le rang
  // est posé une fois pour toutes ici : il survit ensuite au filtre comme au tri.
  const rankedMessages = useMemo(
    () => rankHits(searchActive ? hits : data?.samples ?? []),
    [searchActive, hits, data]);
  const displayedMessages = useMemo(
    () => sortHits(filterHits(rankedMessages, hitFilter), sortKey, sortDesc),
    [rankedMessages, hitFilter, sortKey, sortDesc]);
  const insightNotes = useMemo(
    () => (searchActive ? describeHitInsight(analyzeHits(rankedMessages), data?.topic.partitions ?? 0) : []),
    [searchActive, rankedMessages, data]);
  // Ce qui est marqué décrit la passe affichée, pas le formulaire : un critère édité après coup
  // désignerait des correspondances que la recherche affichée n'a jamais cherchées. Mémoïsé pour
  // que `MessageCard` puisse l'être : un objet recréé à chaque rendu rendrait `React.memo` inutile.
  const highlight = useMemo(
    () => (searchActive && ranCriteria ? highlightFor(ranCriteria) : NO_HIGHLIGHT),
    [searchActive, ranCriteria]);
  const headerTarget = useMemo(
    () => (searchActive && ranCriteria ? highlightedHeader(ranCriteria) : null),
    [searchActive, ranCriteria]);
  const pathTarget = useMemo(
    () => (searchActive && ranCriteria ? highlightedPath(ranCriteria) : null),
    [searchActive, ranCriteria]);
  const showHeaders = useMemo(
    () => (searchActive && ranCriteria ? revealsHeaders(ranCriteria) : false),
    [searchActive, ranCriteria]);
  /** Valeurs déjà observées au chemin choisi — tirées de l'échantillon, sans requête de plus. */
  const fieldValues = useMemo(
    () => (criteria.mode === 'FIELD' ? valuesAtPath(data?.samples ?? [], criteria.field) : []),
    [criteria.mode, criteria.field, data]);
  const announcement = announceResult(searching, coverage, ranCriteria, hits.length);
  // Écrire la ref pendant le rendu marche, mais c'est un effet de bord dans une fonction qui
  // doit rester pure — et `react-hooks/refs` a raison de le dire.
  useEffect(() => { displayedMessagesRef.current = displayedMessages; }, [displayedMessages]);
  /** Le hit ouvert en vue tableau, retrouvé par son rang : un index ne survivrait pas au tri. */
  const selectedHit = useMemo(
    () => displayedMessages.find(message => message.rank === selectedRank) ?? null,
    [displayedMessages, selectedRank]);

  if (loading && !data) return (
    <div className="p-4 md:p-6 max-w-7xl mx-auto space-y-6">
      <div className="skeleton-shimmer h-8 w-72" />
      <StatGridSkeleton count={3} columns="grid-cols-1 sm:grid-cols-3" />
      <TableSkeleton rows={6} columns={2} />
    </div>
  );
  if (!data) return <ErrorBanner message="Failed to load topic" onRetry={fetchTopicDetails} />;

  return (
    <div className="p-4 md:p-6 max-w-7xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-3">
        <Link to="/" className="flex items-center gap-1 text-[12px] font-medium text-on-surface-variant hover:text-on-surface transition-colors w-fit">
          <span className="material-symbols-outlined text-[16px]">chevron_left</span> Dashboard
        </Link>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <h1 className="text-2xl font-semibold font-mono tracking-tight text-on-surface truncate">{data.topic.name}</h1>
            {data.topic.name.toLowerCase().endsWith('.dlt') && (
              <Badge tone="warning">Dead Letter Topic</Badge>
            )}
            <Badge tone="primary">{data.format}</Badge>
          </div>
          <Button variant="primary" icon="terminal" onClick={openInEditor}>
            {selectedFields.length > 0 ? `Select ${selectedFields.length} field${selectedFields.length > 1 ? 's' : ''}` : 'Query this topic'}
          </Button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Stat label="Partitions" icon="device_hub" value={data.topic.partitions} />
        <Stat label="Messages (approx)" icon="bolt" tone="primary" value={data.topic.estimatedSize.toLocaleString()} />
        <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045] p-5">
          <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant mb-2">Read Mode</p>
          <div className="inline-flex bg-surface-container-low border border-outline-variant rounded-md p-0.5">
            {['earliest-offset', 'latest-offset'].map(mode => (
              <button
                key={mode}
                onClick={() => setReadMode(mode)}
                aria-pressed={readMode === mode}
                className={`px-3 h-7 text-[12px] font-medium rounded transition-colors ${readMode === mode ? 'bg-surface-container-highest text-on-surface' : 'text-on-surface-variant hover:text-on-surface'}`}
              >
                {mode === 'earliest-offset' ? 'Earliest' : 'Latest'}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-outline-variant/60 overflow-x-auto">
        {([
          { key: 'samples', label: `Messages (${searchActive ? hits.length : data.samples.length})`, icon: 'mail' },
          { key: 'ddl', label: 'Flink DDL', icon: 'code' },
          { key: 'schema', label: `Schema (${Object.keys(data.schema).length} fields)`, icon: 'list_alt' },
          { key: 'partitions', label: `Partitions (${data.topic.partitions})`, icon: 'device_hub' },
          { key: 'consumers', label: 'Consumers', icon: 'groups' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex items-center gap-2 px-4 py-2.5 text-[13px] font-medium border-b-2 -mb-px transition-colors whitespace-nowrap ${
              activeTab === tab.key
                ? 'border-primary text-on-surface'
                : 'border-transparent text-on-surface-variant hover:text-on-surface'
            }`}
          >
            <span className="material-symbols-outlined text-[18px]">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </div>

      {/* Samples Tab */}
      {activeTab === 'samples' && (
        <div className="space-y-3">
          <TopicSearchPanel
            schemaPaths={Object.keys(data.schema)}
            partitionCount={data.topic.partitions}
            topicSize={data.topic.estimatedSize}
            criteria={criteria}
            onChange={setCriteria}
            onSearch={() => runSearch()}
            onContinue={continueSearch}
            onCancel={cancelSearch}
            onCopyLink={copySearchLink}
            onClear={clearSearch}
            onExport={exportHits}
            onApply={applyCriteria}
            history={history}
            pinned={pinned}
            onTogglePin={togglePin}
            fieldValues={fieldValues}
            following={following}
            onToggleFollow={() => setFollowing(!following)}
            followAvailable={searchActive && canFollow(searchResult?.nextCursor)}
            advancedOpen={advancedOpen}
            onToggleAdvanced={toggleAdvanced}
            searching={searching}
            active={searchActive}
            coverage={coverage}
            ranCriteria={ranCriteria}
            warnings={warnings}
            error={searchError}
            errors={fieldErrors}
            stopped={stopped}
            loadedHits={hits.length}
          />

          {/* Field selection bar */}
          {selectedFields.length > 0 && (
            <div className="flex items-center gap-3 px-4 py-2.5 rounded-xl border border-primary/30 bg-primary/10">
              <span className="material-symbols-outlined text-primary text-base shrink-0">touch_app</span>
              <div className="flex flex-wrap gap-1.5 flex-1 min-w-0">
                {selectedFields.map(f => (
                  <button
                    key={f}
                    onClick={() => toggleField(f)}
                    className="flex items-center gap-1 px-2 py-0.5 rounded bg-primary/20 text-primary text-[11px] font-mono font-bold hover:bg-error/20 hover:text-error transition-colors"
                    title="Remove"
                  >
                    {f}
                    <span className="material-symbols-outlined text-xs">close</span>
                  </button>
                ))}
              </div>
              <button
                onClick={() => setSelectedFields([])}
                className="text-[10px] text-on-surface-variant hover:text-on-surface shrink-0"
              >
                Clear all
              </button>
            </div>
          )}

          {/* Le message désigné par le lien. Il vit à part des résultats : ce n'est pas une
              recherche qui l'a trouvé, c'est quelqu'un qui l'a nommé. */}
          {linkedCoordinates && (
            <div className="rounded-xl border border-primary/30 bg-primary/5 overflow-hidden">
              <div className="flex items-center justify-between gap-3 px-4 py-2 border-b border-primary/20">
                <span className="text-[12px] text-on-surface-variant">
                  Linked record
                  <span className="font-mono text-primary ml-2">
                    p{linkedCoordinates.partition}@{linkedCoordinates.offset.toLocaleString()}
                  </span>
                </span>
                <Button variant="ghost" size="sm" icon="close" onClick={closeLinkedRecord}>
                  Close
                </Button>
              </div>
              {linkedLoading && (
                <p className="px-4 py-3 text-[12px] text-on-surface-variant">Reading the record…</p>
              )}
              {linkedError && !linkedLoading && (
                <div className="p-3">
                  <ErrorPanel error={linkedError} />
                </div>
              )}
              {linkedRecord && !linkedLoading && (
                <MessageCard
                  message={{ ...linkedRecord, rank: 1 }}
                  index={0}
                  onCopy={copyToClipboard}
                  onFieldClick={toggleField}
                  selectedFields={selectedFields}
                  highlight={NO_HIGHLIGHT}
                  revealHeaders
                  onCopyLink={copyRecordLink}
                />
              )}
            </div>
          )}

          {/* La disparition de « Searching… » ne dit rien à qui ne voit pas l'écran. */}
          <p className="sr-only" role="status" aria-live="polite">{announcement}</p>

          {/* Ce que les hits disent d'eux-mêmes : quarante lignes ne montrent pas qu'elles sont
              trente-huit sur la même partition, ni qu'elles tiennent dans quatre minutes. */}
          {insightNotes.length > 0 && (
            <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-on-surface-variant">
              <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-primary">insights</span>
              {insightNotes.map((note, i) => (
                <React.Fragment key={note}>
                  {i > 0 && <span className="text-outline">·</span>}
                  <span>{note}</span>
                </React.Fragment>
              ))}
            </p>
          )}

          {rankedMessages.length > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3 min-w-0">
                <span className="text-[11px] text-on-surface-variant whitespace-nowrap">
                  {hitFilter.trim()
                    ? `${displayedMessages.length} of ${rankedMessages.length}`
                    : displayedMessages.length}
                  {' '}message{displayedMessages.length === 1 ? '' : 's'}
                  {searchActive ? ' matched' : ' sampled'}
                </span>
                {/* Resserrer sur ce qui est déjà là : instantané, là où relancer une passe coûte
                    dix secondes de scan. */}
                <div className="relative">
                  <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[15px] absolute left-2 top-1/2 -translate-y-1/2 pointer-events-none">filter_alt</span>
                  <input
                    value={hitFilter}
                    onChange={e => setHitFilter(e.target.value)}
                    placeholder="Filter these results…"
                    aria-label="Filter the results already fetched"
                    className="h-7 w-52 pl-7 pr-2 rounded-md bg-surface-container border border-outline-variant text-[12px] text-on-surface placeholder:text-outline focus:outline-none focus:border-primary/60"
                  />
                </div>
              </div>
              <div className="inline-flex bg-surface-container border border-outline-variant rounded-md p-0.5">
                {(['cards', 'table'] as MessageView[]).map(mode => (
                  <button
                    key={mode}
                    onClick={() => changeView(mode)}
                    aria-pressed={view === mode}
                    title={mode === 'cards' ? 'Full payload per message' : 'One line per record, details on selection'}
                    className={`flex items-center gap-1.5 px-3 h-7 text-[12px] font-medium rounded transition-colors ${
                      view === mode
                        ? 'bg-surface-container-highest text-on-surface'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <span aria-hidden="true" className="material-symbols-outlined text-[16px]">
                      {mode === 'cards' ? 'view_agenda' : 'table_rows'}
                    </span>
                    {mode === 'cards' ? 'Cards' : 'Table'}
                  </button>
                ))}
              </div>
            </div>
          )}

          {view === 'table' && displayedMessages.length > 0 && (
            <MessageTable
              messages={displayedMessages}
              highlight={highlight}
              selected={selectedRank}
              onSelect={setSelectedRank}
              sortKey={sortKey}
              sortDesc={sortDesc}
              onSort={changeSort}
            />
          )}

          <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
            {view === 'cards'
              ? displayedMessages.map((message, i) => (
                <MessageCard
                  key={message.rank}
                  message={message}
                  index={i}
                  onCopy={copyToClipboard}
                  onFieldClick={toggleField}
                  selectedFields={selectedFields}
                  highlight={highlight}
                  highlightHeader={headerTarget}
                  highlightPath={pathTarget}
                  revealHeaders={showHeaders}
                  fullValue={fullRecords[`${message.partition}-${message.offset}`]}
                  onLoadFull={loadFullRecord}
                  loadingFull={loadingRecord === `${message.partition}-${message.offset}`}
                  onCopyLink={copyRecordLink}
                />
              ))
              : selectedHit ? (
                <MessageCard
                  key={selectedHit.rank}
                  message={selectedHit}
                  index={0}
                  onCopy={copyToClipboard}
                  onFieldClick={toggleField}
                  selectedFields={selectedFields}
                  highlight={highlight}
                  highlightHeader={headerTarget}
                  highlightPath={pathTarget}
                  revealHeaders={showHeaders}
                  fullValue={fullRecords[`${selectedHit.partition}-${selectedHit.offset}`]}
                  onLoadFull={loadFullRecord}
                  loadingFull={loadingRecord === `${selectedHit.partition}-${selectedHit.offset}`}
                  onCopyLink={copyRecordLink}
                />
              ) : displayedMessages.length > 0 ? (
                <EmptyState icon="touch_app" title="Select a row to read the record" />
              ) : null}
            {displayedMessages.length === 0 && (
              <EmptyState
                icon="search_off"
                title={hitFilter.trim() && rankedMessages.length > 0
                  ? 'No message matches that filter'
                  : searchActive ? 'No matching messages' : 'No messages in topic'}
                description={hitFilter.trim() && rankedMessages.length > 0
                  ? `The filter is applied to the ${rankedMessages.length} messages already fetched — clear it to see them all.`
                  : searchActive
                    ? ranCriteria?.direction === 'NEWEST'
                      ? 'Nothing matched in the records that were scanned. Widen the range, or scan further back.'
                      : 'Nothing matched in the range that was scanned. Widen the range, or continue scanning.'
                    : undefined}
              />
            )}
          </div>
        </div>
      )}

      {/* DDL Tab */}
      {activeTab === 'ddl' && (
        <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-outline-variant/60 bg-surface-container-high/60">
            <span className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Auto-Generated Definition</span>
            <Button variant="ghost" size="sm" icon="content_copy" onClick={() => copyToClipboard(data.ddl)}>Copy</Button>
          </div>
          <div className="h-72">
            <Editor
              height="100%"
              defaultLanguage="sql"
              theme="vs-dark"
              value={data.ddl}
              options={{ readOnly: true, fontSize: 12, fontFamily: 'JetBrains Mono', minimap: { enabled: false }, padding: { top: 16 }, scrollBeyondLastLine: false }}
            />
          </div>
        </div>
      )}

      {/* Partitions Tab */}
      {activeTab === 'partitions' && (
        <Table rowCount={Object.keys(data.topic.maxOffsets).length} className="text-sm">
            <thead>
              <tr className="bg-surface-container-high/60 border-b border-outline-variant/60 text-[10px] uppercase tracking-widest text-on-surface-variant">
                <th className="text-left px-5 py-3">Partition</th>
                <th className="text-right px-5 py-3">Start Offset</th>
                <th className="text-right px-5 py-3">End Offset</th>
                <th className="text-right px-5 py-3">Messages</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/40">
              {Object.keys(data.topic.maxOffsets).map(p => {
                const partition = Number(p);
                const start = data.topic.minOffsets[partition] ?? 0;
                const end = data.topic.maxOffsets[partition] ?? 0;
                const count = end - start;
                return (
                  <tr key={partition} className="hover:bg-surface-container-high/40 transition-colors">
                    <td className="px-5 py-3 font-mono text-on-surface">P{partition}</td>
                    <td className="px-5 py-3 text-right font-mono text-on-surface-variant">{start.toLocaleString()}</td>
                    <td className="px-5 py-3 text-right font-mono text-on-surface-variant">{end.toLocaleString()}</td>
                    <td className="px-5 py-3 text-right">
                      <span className="font-mono font-bold text-primary">{count.toLocaleString()}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr className="bg-surface-container-high/60 border-t border-outline-variant/60">
                <td colSpan={3} className="px-5 py-2.5 text-[10px] font-bold text-on-surface-variant uppercase tracking-widest">Total</td>
                <td className="px-5 py-2.5 text-right font-mono font-bold text-primary text-sm">
                  {data.topic.estimatedSize.toLocaleString()}
                </td>
              </tr>
            </tfoot>
        </Table>
      )}

      {/* Consumers Tab — monté seulement quand il est ouvert : la lecture des offsets committés
          coûte plusieurs allers-retours au coordinateur, et personne ne les paye en arrivant sur
          la page pour lire des messages. */}
      {activeTab === 'consumers' && <TopicConsumersPanel topic={name!} />}

      {/* Schema Tab */}
      {activeTab === 'schema' && (
        <Table rowCount={Object.entries(data.schema).length} className="text-sm">
            <thead>
              <tr className="bg-surface-container-high/60 border-b border-outline-variant/60">
                <th className="text-left px-5 py-3 text-[10px] font-bold text-on-surface-variant uppercase tracking-widest">Field</th>
                <th className="text-right px-5 py-3 text-[10px] font-bold text-on-surface-variant uppercase tracking-widest">Type</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/40">
              {Object.entries(data.schema).map(([field, type]) => (
                <tr key={field} className="hover:bg-surface-container-high/40 transition-colors">
                  <td className="px-5 py-3 font-medium text-on-surface">{field}</td>
                  <td className="px-5 py-3 text-right font-mono text-[11px] text-primary/80 uppercase">{type}</td>
                </tr>
              ))}
            </tbody>
        </Table>
      )}
    </div>
  );
};

export default TopicExplorer;
