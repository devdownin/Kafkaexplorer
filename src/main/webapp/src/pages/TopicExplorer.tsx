import React, { useState, useEffect, useMemo } from 'react';
import { useParams, useLocation, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import '../monaco-setup';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import { Button, Badge, Stat, EmptyState, StatGridSkeleton, TableSkeleton, Table } from '../components/ui';
import { buildTraceLinkForKey } from './streamFlow';
import TopicSearchPanel from '../components/topic/TopicSearchPanel';
import {
  NO_HIGHLIGHT,
  buildSearchBody,
  buildSearchQuery,
  coverageOf,
  criteriaFromQuery,
  emptyCriteria,
  highlightFor,
  highlightedHeader,
  revealsHeaders,
  splitForHighlight,
  type ScanAction,
  type SearchCoverage,
  type SearchHighlight,
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
  highlight: SearchHighlight;
  /** Le header comparé par la recherche, à désigner parmi les autres. */
  highlightHeader?: string | null;
  /** Vrai quand le match s'est joué dans les headers : ils ne peuvent plus rester en infobulle. */
  revealHeaders?: boolean;
}> = ({
  message, index, onCopy, onFieldClick, selectedFields, highlight, highlightHeader, revealHeaders,
}) => {
  const sample = message.value ?? '';
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
        {message.truncated && (
          <span className="text-warning" title={`Value truncated (${message.valueBytes} chars)`}>
            truncated
          </span>
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
  const location = useLocation();
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
  const [coverage, setCoverage] = useState<SearchCoverage | null>(null);
  /** Le critère de la passe affichée : le formulaire peut avoir bougé depuis. */
  const [ranCriteria, setRanCriteria] = useState<TopicSearchCriteria | null>(null);
  const [hits, setHits] = useState<TopicMessage[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searchActive, setSearchActive] = useState(false);
  const [stopped, setStopped] = useState(false);
  /** La passe en vol, pour pouvoir l'abandonner : un scan dure jusqu'à dix secondes. */
  const abortRef = React.useRef<AbortController | null>(null);
  /** Numéro de passe : ce qui revient d'une passe remplacée ne doit pas atterrir sur la suivante. */
  const runIdRef = React.useRef(0);
  /** La query string que la page a écrite elle-même, pour ne pas la relire comme un ordre. */
  const lastAppliedSearch = React.useRef<string | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

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
      setCoverage(prev => coverageOf(response.data, prev, resume, pass.maxScan));
      setHits(prev => resume ? [...prev, ...response.data.hits] : response.data.hits);
      setRanCriteria(active);
      setSearchActive(true);
      // L'URL décrit désormais la recherche affichée : un lien collé dans un ticket la rejoue.
      const search = buildSearchQuery(active);
      lastAppliedSearch.current = search;
      navigate({ pathname: location.pathname, search }, { replace: true });
    } catch (e) {
      if (runIdRef.current !== runId) return;
      // Une passe abandonnée n'a rien à dire : ni erreur, ni résultat. `stopped` le signale.
      if (axios.isCancel(e) || controller.signal.aborted) return;
      const message = axios.isAxiosError(e)
        ? (e.response?.data as { message?: string } | undefined)?.message ?? e.message
        : 'Search failed';
      setSearchError(message);
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
    setCriteria(preset);
    void runSearch({}, preset);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- réagit au seul critère porté par l'URL
  }, [location.search]);

  const clearSearch = () => {
    abortRef.current?.abort();
    runIdRef.current++;
    setSearching(false);
    setSearchActive(false);
    setSearchResult(null);
    setCoverage(null);
    setRanCriteria(null);
    setHits([]);
    setSearchError(null);
    setStopped(false);
    setCriteria(emptyCriteria);
    // L'URL annonçait la recherche affichée : elle ne doit plus décrire ce qui n'est plus là.
    lastAppliedSearch.current = '';
    navigate({ pathname: location.pathname, search: '' }, { replace: true });
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
  // Ce qui est marqué décrit la passe affichée, pas le formulaire : un critère édité après coup
  // désignerait des correspondances que la recherche affichée n'a jamais cherchées.
  const highlight = searchActive && ranCriteria ? highlightFor(ranCriteria) : NO_HIGHLIGHT;
  const headerTarget = searchActive && ranCriteria ? highlightedHeader(ranCriteria) : null;
  const showHeaders = searchActive && ranCriteria ? revealsHeaders(ranCriteria) : false;

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
            onSearch={() => runSearch()}
            onContinue={continueSearch}
            onCancel={cancelSearch}
            onCopyLink={copySearchLink}
            onClear={clearSearch}
            searching={searching}
            active={searchActive}
            coverage={coverage}
            ranCriteria={ranCriteria}
            warnings={searchResult?.warnings ?? []}
            error={searchError}
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
                highlightHeader={headerTarget}
                revealHeaders={showHeaders}
              />
            ))}
            {displayedMessages.length === 0 && (
              <EmptyState
                icon="search_off"
                title={searchActive ? 'No matching messages' : 'No messages in topic'}
                description={searchActive
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
