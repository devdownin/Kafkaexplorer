import React, { useState, useEffect, useMemo } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import '../monaco-setup';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import { Button, Badge, Stat, EmptyState, StatGridSkeleton, TableSkeleton, Table } from '../components/ui';
import TopicSearchPanel, {
  emptyCriteria,
  splitOnMatches,
  type TopicMessage,
  type TopicSearchCriteria,
  type TopicSearchResponse,
} from '../components/topic/TopicSearchPanel';

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

// ── Interactive JSON renderer ─────────────────────────────────────────────
const JsonNode: React.FC<{
  value: unknown;
  depth: number;
  keyName?: string;
  fieldPath?: string;  // full dot-notation path to this node (e.g. "customer.name")
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
}> = ({ value, depth, keyName, fieldPath, onFieldClick, selectedFields }) => {
  const indent = '  '.repeat(depth);
  // Use fieldPath for selection/click if available, otherwise fall back to keyName
  const clickPath = fieldPath ?? keyName;
  const isSelected = clickPath !== undefined && selectedFields?.includes(clickPath);

  const keyEl = keyName !== undefined && onFieldClick && clickPath !== undefined ? (
    <button
      onClick={() => onFieldClick(clickPath)}
      title={isSelected ? 'Remove from SELECT' : 'Add to SELECT'}
      className={`font-mono text-[11px] font-semibold transition-colors rounded px-0.5 -mx-0.5 ${
        isSelected
          ? 'text-primary bg-primary/20 line-through'
          : 'text-warning hover:text-primary hover:bg-primary/10 cursor-pointer'
      }`}
    >
      "{keyName}"
    </button>
  ) : keyName !== undefined ? (
    <span className="text-warning font-mono text-[11px]">"{keyName}"</span>
  ) : null;

  if (value === null) {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">null</span></span>;
  }
  if (typeof value === 'boolean') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-secondary font-mono text-[11px]">{String(value)}</span></span>;
  }
  if (typeof value === 'number') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-success font-mono text-[11px]">{value}</span></span>;
  }
  if (typeof value === 'string') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface-variant">: </span></>}<span className="text-primary font-mono text-[11px]">"{value}"</span></span>;
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
              <JsonNode value={item} depth={depth + 1} />
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
                  onFieldClick={onFieldClick}
                  selectedFields={selectedFields}
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
}> = ({ xml, onFieldClick, selectedFields }) => {
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
    const indentPx = depth * 12;

    const tagBtn = onFieldClick ? (
      <button
        onClick={() => onFieldClick(path)}
        title={isSelected ? 'Remove from SELECT' : 'Add to SELECT'}
        className={`transition-colors rounded px-0.5 -mx-0.5 ${
          isSelected
            ? 'text-primary bg-primary/20 line-through'
            : 'text-success hover:text-primary hover:bg-primary/10 cursor-pointer'
        }`}
      >
        {tag}
      </button>
    ) : <span className="text-success">{tag}</span>;

    if (isLeaf) {
      return (
        <div key={path} style={{ paddingLeft: `${indentPx}px` }} className="font-mono text-[11px]">
          <span className="text-on-surface-variant">{'<'}</span>{tagBtn}<span className="text-on-surface-variant">{'>'}</span>
          <span className="text-on-surface">{el.textContent?.trim()}</span>
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

/** Renders text with every occurrence of `highlight` marked. Odd indexes are the matches. */
const Highlighted: React.FC<{ text: string; highlight: string; caseSensitive: boolean }> = ({
  text, highlight, caseSensitive,
}) => {
  if (!highlight) return <>{text}</>;
  const parts = splitOnMatches(text, highlight, caseSensitive);
  return (
    <>
      {parts.map((part, i) => (i % 2 === 1
        ? <mark key={i} className="bg-warning/40 text-on-surface rounded-sm">{part}</mark>
        : <React.Fragment key={i}>{part}</React.Fragment>
      ))}
    </>
  );
};

const formatTimestamp = (ms: number): string => {
  if (!ms || ms < 0) return '—';
  return new Date(ms).toISOString().replace('T', ' ').replace('Z', '');
};

// ── MessageCard ────────────────────────────────────────────────────────────
const MessageCard: React.FC<{
  message: TopicMessage;
  index: number;
  onCopy: (s: string) => void;
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
  highlight?: string;
  caseSensitive?: boolean;
}> = ({ message, index, onCopy, onFieldClick, selectedFields, highlight, caseSensitive }) => {
  const sample = message.value ?? '';
  const [expanded, setExpanded] = useState(index < 3);
  // Raw view is what highlighting can mark up, so a card opens raw as soon as there is
  // something to highlight — the user should see *why* the record matched.
  const [raw, setRaw] = useState(Boolean(highlight));
  // Cards are keyed by list position, so when filtering shifts a different message into this slot
  // the instance is reused — reset the expand state to the default for the new content instead of
  // bleeding the previous message's state.
  useEffect(() => { setExpanded(index < 3); }, [sample, index]);
  useEffect(() => { setRaw(Boolean(highlight)); }, [highlight, sample]);

  let parsed: unknown = null;
  let isJson = false;
  let isXml = false;
  try {
    parsed = JSON.parse(sample);
    // Only structured JSON (object/array) counts as JSON — a bare number/string/boolean that
    // happens to parse is shown as plain text rather than mislabelled with a JSON badge.
    isJson = parsed !== null && typeof parsed === 'object';
  } catch {
    isXml = sample.trimStart().startsWith('<');
  }

  const formatted = isJson ? JSON.stringify(parsed, null, 2) : sample;
  const lines = formatted.split('\n');
  const needsCollapse = lines.length > 8;

  return (
    <div className="border-b border-outline-variant/40 last:border-b-0 group">
      {/* Record coordinates: without them a hit is a wall of text with no location */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 pt-2.5 text-[10px] font-mono text-on-surface-variant">
        <span className="text-outline">#{index + 1}</span>
        <span title="Partition">p{message.partition}</span>
        <span title="Offset">@{message.offset.toLocaleString()}</span>
        <span title="Record timestamp">{formatTimestamp(message.timestamp)}</span>
        {message.key !== null && message.key !== undefined && (
          <span className="text-primary truncate max-w-[16rem]" title={`Key: ${message.key}`}>
            key={message.key}
          </span>
        )}
        {message.headers && Object.keys(message.headers).length > 0 && (
          <span title={Object.entries(message.headers).map(([k, v]) => `${k}: ${v}`).join('\n')}>
            {Object.keys(message.headers).length} header{Object.keys(message.headers).length > 1 ? 's' : ''}
          </span>
        )}
        {message.truncated && (
          <span className="text-warning" title={`Value truncated (${message.valueBytes} chars)`}>
            truncated
          </span>
        )}
      </div>
      <div className="flex items-start gap-3 px-4 pb-4 pt-1 hover:bg-surface-container-high/40 transition-colors">
        <div className="flex-1 min-w-0 overflow-x-auto">
          {raw ? (
            <pre className={`font-mono text-[11px] text-on-surface whitespace-pre-wrap break-all leading-relaxed ${!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}`}>
              <Highlighted text={formatted} highlight={highlight ?? ''} caseSensitive={Boolean(caseSensitive)} />
            </pre>
          ) : isJson && parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (
            <div className={`leading-relaxed ${!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}`}>
              <JsonNode
                value={parsed}
                depth={0}
                onFieldClick={onFieldClick}
                selectedFields={selectedFields}
              />
            </div>
          ) : isXml ? (
            <div className={!expanded && needsCollapse ? 'max-h-24 overflow-hidden' : ''}>
              <XmlViewer xml={sample} onFieldClick={onFieldClick} selectedFields={selectedFields} />
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
};

const TopicExplorer: React.FC = () => {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const { toast } = useToast();
  const [data, setData] = useState<TopicDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [readMode, setReadMode] = useState('earliest-offset');
  const [activeTab, setActiveTab] = useState<'samples' | 'ddl' | 'schema' | 'partitions'>('samples');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);

  // Server-side search state. `hits` accumulates across passes so "continue scanning"
  // appends instead of replacing what the user is already reading.
  const [criteria, setCriteria] = useState<TopicSearchCriteria>(emptyCriteria);
  const [searchResult, setSearchResult] = useState<TopicSearchResponse | null>(null);
  const [hits, setHits] = useState<TopicMessage[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searchActive, setSearchActive] = useState(false);

  const toggleField = (field: string) => {
    setSelectedFields(prev =>
      prev.includes(field) ? prev.filter(f => f !== field) : [...prev, field]
    );
  };

  useEffect(() => {
    // Guard against out-of-order responses: toggling read mode quickly fires several requests,
    // and without this a slower one could overwrite the newer result with stale data.
    let active = true;
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

  const runSearch = async (resume: boolean) => {
    setSearching(true);
    setSearchError(null);
    try {
      // FIELD et HEADER portent tous deux leur cible dans `field` (chemin / nom de header) ;
      // KEY compare `value` sans cible.
      const fieldScoped = criteria.mode === 'FIELD' || criteria.mode === 'HEADER';
      const valueScoped = fieldScoped || criteria.mode === 'KEY';
      const body = {
        mode: criteria.mode,
        query: criteria.query,
        caseSensitive: criteria.caseSensitive,
        searchKey: criteria.searchKey,
        searchHeaders: criteria.searchHeaders,
        keyPartitioning: criteria.keyPartitioning,
        field: fieldScoped ? criteria.field : null,
        operator: valueScoped ? criteria.operator : null,
        value: valueScoped ? criteria.value : null,
        from: criteria.sinceMinutes > 0 ? 'TIMESTAMP' : 'EARLIEST',
        sinceMinutes: criteria.sinceMinutes > 0 ? criteria.sinceMinutes : null,
        // Resuming continues exactly where the previous pass stopped.
        cursor: resume ? searchResult?.nextCursor ?? null : null,
      };
      const response = await axios.post<TopicSearchResponse>(
        `/api/topic/${encodeURIComponent(name ?? '')}/search`, body);
      setSearchResult(response.data);
      setHits(prev => resume ? [...prev, ...response.data.hits] : response.data.hits);
      setSearchActive(true);
    } catch (e) {
      const message = axios.isAxiosError(e)
        ? (e.response?.data as { message?: string } | undefined)?.message ?? e.message
        : 'Search failed';
      setSearchError(message);
      if (!resume) {
        setHits([]);
        setSearchResult(null);
      }
    } finally {
      setSearching(false);
    }
  };

  const clearSearch = () => {
    setSearchActive(false);
    setSearchResult(null);
    setHits([]);
    setSearchError(null);
    setCriteria(emptyCriteria);
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    toast('Copied to clipboard', 'success');
  };

  const openInEditor = () => {
    const cols = selectedFields.length > 0 ? selectedFields.join(', ') : '*';
    navigate(`/query?sql=${encodeURIComponent(`SELECT ${cols} FROM "${name}" LIMIT 50`)}`);
  };

  if (loading && !data) return (
    <div className="p-4 md:p-6 max-w-7xl mx-auto space-y-6">
      <div className="skeleton-shimmer h-8 w-72" />
      <StatGridSkeleton count={3} columns="grid-cols-1 sm:grid-cols-3" />
      <TableSkeleton rows={6} columns={2} />
    </div>
  );
  if (!data) return <ErrorBanner message="Failed to load topic" onRetry={fetchTopicDetails} />;

  // The list shows search hits when a search is active, the sampled messages otherwise.
  const displayedMessages = searchActive ? hits : data.samples;
  // Only a plain text search maps to a literal that can be marked up in the raw view.
  const highlight = searchActive && criteria.mode === 'CONTAINS' ? criteria.query : '';

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
            criteria={criteria}
            onChange={setCriteria}
            onSearch={() => runSearch(false)}
            onLoadMore={() => runSearch(true)}
            onClear={clearSearch}
            searching={searching}
            active={searchActive}
            result={searchResult}
            error={searchError}
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

          <div className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
            {displayedMessages.map((message, i) => (
              <MessageCard
                key={`${message.partition}-${message.offset}-${i}`}
                message={message}
                index={i}
                onCopy={copyToClipboard}
                onFieldClick={toggleField}
                selectedFields={selectedFields}
                highlight={highlight}
                caseSensitive={criteria.caseSensitive}
              />
            ))}
            {displayedMessages.length === 0 && (
              <EmptyState
                icon="search_off"
                title={searchActive ? 'No matching messages' : 'No messages in topic'}
                description={searchActive
                  ? 'Nothing matched in the range that was scanned. Widen the range, or continue scanning.'
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
