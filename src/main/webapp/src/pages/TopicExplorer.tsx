import React, { useState, useEffect, useMemo } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import { useToast } from '../components/Toast';
import LoadingSpinner from '../components/LoadingSpinner';
import ErrorBanner from '../components/ErrorBanner';

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
  samples: string[];
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
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-on-surface0 font-mono text-[11px]">null</span></span>;
  }
  if (typeof value === 'boolean') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-secondary font-mono text-[11px]">{String(value)}</span></span>;
  }
  if (typeof value === 'number') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-success font-mono text-[11px]">{value}</span></span>;
  }
  if (typeof value === 'string') {
    return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-primary font-mono text-[11px]">"{value}"</span></span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">[]</span></span>;
    return (
      <span>
        {keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}
        <span className="text-on-surface-variant font-mono text-[11px]">{'['}</span>
        <div>
          {value.slice(0, 3).map((item, i) => (
            <div key={i} className="font-mono text-[11px]">
              {indent + '  '}
              <JsonNode value={item} depth={depth + 1} />
              {i < Math.min(value.length, 3) - 1 && <span className="text-on-surface0">,</span>}
            </div>
          ))}
          {value.length > 3 && <div className="text-on-surface0 font-mono text-[11px]">{indent}  ... ({value.length} items)</div>}
        </div>
        <span className="text-on-surface-variant font-mono text-[11px]">{indent}{']'}</span>
      </span>
    );
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return <span>{keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}<span className="text-on-surface-variant font-mono text-[11px]">{'{}'}</span></span>;
    return (
      <span>
        {keyEl && <>{keyEl}<span className="text-on-surface0">: </span></>}
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
                {i < entries.length - 1 && <span className="text-on-surface0">,</span>}
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
          <span className="text-on-surface0">{'<'}</span>{tagBtn}<span className="text-on-surface0">{'>'}</span>
          <span className="text-on-surface">{el.textContent?.trim()}</span>
          <span className="text-on-surface0">{`</${tag}>`}</span>
        </div>
      );
    }

    return (
      <div key={path} style={{ paddingLeft: `${indentPx}px` }} className="font-mono text-[11px]">
        <span className="text-on-surface0">{'<'}</span>{tagBtn}<span className="text-on-surface0">{'>'}</span>
        {childEls.map((child, i) => (
          <React.Fragment key={i}>{renderElement(child, path, depth + 1)}</React.Fragment>
        ))}
        <div style={{ paddingLeft: 0 }}><span className="text-on-surface0">{`</${tag}>`}</span></div>
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

// ── SampleCard ─────────────────────────────────────────────────────────────
const SampleCard: React.FC<{
  sample: string;
  index: number;
  onCopy: (s: string) => void;
  onFieldClick?: (field: string) => void;
  selectedFields?: string[];
}> = ({ sample, index, onCopy, onFieldClick, selectedFields }) => {
  const [expanded, setExpanded] = useState(index < 3);
  // Cards are keyed by list position, so when filtering shifts a different message into this slot
  // the instance is reused — reset the expand state to the default for the new content instead of
  // bleeding the previous message's state.
  useEffect(() => { setExpanded(index < 3); }, [sample, index]);

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
    <div className="border-b border-primary/5 last:border-b-0 group">
      <div className="flex items-start gap-3 p-4 hover:bg-primary/5 transition-colors">
        <span className="text-[10px] font-mono text-outline mt-0.5 w-6 shrink-0">{index + 1}</span>
        <div className="flex-1 min-w-0 overflow-x-auto">
          {isJson && parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (
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
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
          {isJson && <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-primary/10 text-primary">JSON</span>}
          {isXml && <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-success/10 text-success">XML</span>}
          <button
            onClick={() => onCopy(formatted)}
            className="p-1.5 text-on-surface0 hover:text-primary hover:bg-primary/10 rounded transition-colors"
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
  const [sampleFilter, setSampleFilter] = useState('');
  const [activeTab, setActiveTab] = useState<'samples' | 'ddl' | 'schema' | 'partitions'>('samples');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);

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

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    toast('Copied to clipboard', 'success');
  };

  const openInEditor = () => {
    const cols = selectedFields.length > 0 ? selectedFields.join(', ') : '*';
    navigate(`/query?sql=${encodeURIComponent(`SELECT ${cols} FROM "${name}" LIMIT 50`)}`);
  };

  if (loading && !data) return <LoadingSpinner />;
  if (!data) return <ErrorBanner message="Failed to load topic" onRetry={fetchTopicDetails} />;

  const filteredSamples = data.samples.filter(s =>
    !sampleFilter || s.toLowerCase().includes(sampleFilter.toLowerCase())
  );

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-3">
        <Link to="/" className="flex items-center gap-1.5 text-[10px] font-bold text-on-surface0 hover:text-primary uppercase tracking-widest transition-colors w-fit">
          <span className="material-symbols-outlined text-sm">chevron_left</span> Dashboard
        </Link>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold font-mono text-on-surface">{data.topic.name}</h1>
            {data.topic.name.toLowerCase().endsWith('.dlt') && (
              <span className="px-2 py-0.5 rounded border border-warning/30 bg-warning/10 text-warning text-[10px] font-bold uppercase">
                Dead Letter Topic
              </span>
            )}
            <span className="px-2 py-0.5 rounded border border-primary/20 bg-primary/10 text-primary text-[10px] font-bold uppercase">
              {data.format}
            </span>
          </div>
          <button
            onClick={openInEditor}
            className="flex items-center gap-2 px-4 py-2 bg-primary text-background-dark font-bold text-sm rounded-lg hover:brightness-110 transition-all"
          >
            <span className="material-symbols-outlined text-lg">terminal</span>
            {selectedFields.length > 0 ? `SELECT ${selectedFields.length} field${selectedFields.length > 1 ? 's' : ''}` : 'Query this topic'}
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-on-surface0 uppercase tracking-widest mb-1">Partitions</p>
          <p className="text-3xl font-bold text-on-surface">{data.topic.partitions}</p>
        </div>
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-on-surface0 uppercase tracking-widest mb-1">Messages (Approx)</p>
          <p className="text-3xl font-bold text-primary">{data.topic.estimatedSize.toLocaleString()}</p>
        </div>
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-on-surface0 uppercase tracking-widest mb-1">Read Mode</p>
          <div className="flex gap-1 mt-1">
            {['earliest-offset', 'latest-offset'].map(mode => (
              <button
                key={mode}
                onClick={() => setReadMode(mode)}
                className={`px-2 py-1 text-[10px] font-bold rounded transition-colors ${readMode === mode ? 'bg-primary/20 text-primary' : 'bg-surface-container text-on-surface0 hover:bg-surface-container-high'}`}
              >
                {mode === 'earliest-offset' ? 'EARLIEST' : 'LATEST'}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-primary/10">
        {([
          { key: 'samples', label: `Messages (${data.samples.length})`, icon: 'mail' },
          { key: 'ddl', label: 'Flink DDL', icon: 'code' },
          { key: 'schema', label: `Schema (${Object.keys(data.schema).length} fields)`, icon: 'list_alt' },
          { key: 'partitions', label: `Partitions (${data.topic.partitions})`, icon: 'device_hub' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex items-center gap-2 px-4 py-2.5 text-sm font-bold uppercase tracking-wider border-b-2 -mb-px transition-colors ${
              activeTab === tab.key
                ? 'border-primary text-primary'
                : 'border-transparent text-on-surface0 hover:text-on-surface'
            }`}
          >
            <span className="material-symbols-outlined text-base">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </div>

      {/* Samples Tab */}
      {activeTab === 'samples' && (
        <div className="space-y-3">
          <div className="flex items-center gap-3">
            <div className="flex-1 flex items-center gap-2 bg-primary/5 border border-primary/20 rounded-lg px-3 py-2">
              <span className="material-symbols-outlined text-on-surface0 text-lg">search</span>
              <input
                type="text"
                value={sampleFilter}
                onChange={e => setSampleFilter(e.target.value)}
                placeholder="Filter messages..."
                className="flex-1 bg-transparent border-none focus:ring-0 text-sm text-on-surface placeholder:text-outline outline-none"
              />
              {sampleFilter && (
                <button onClick={() => setSampleFilter('')} className="text-on-surface0 hover:text-on-surface">
                  <span className="material-symbols-outlined text-base">close</span>
                </button>
              )}
            </div>
            <span className="text-xs text-on-surface0 shrink-0">{filteredSamples.length} of {data.samples.length}</span>
          </div>

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
                className="text-[10px] text-on-surface0 hover:text-on-surface shrink-0"
              >
                Clear all
              </button>
            </div>
          )}

          <div className="rounded-xl border border-primary/10 overflow-hidden">
            {filteredSamples.map((sample, i) => (
              <SampleCard
                key={i}
                sample={sample}
                index={i}
                onCopy={copyToClipboard}
                onFieldClick={toggleField}
                selectedFields={selectedFields}
              />
            ))}
            {filteredSamples.length === 0 && (
              <div className="p-16 text-center text-outline space-y-2">
                <span className="material-symbols-outlined text-4xl block opacity-30">search_off</span>
                <p className="text-xs uppercase tracking-widest">
                  {sampleFilter ? 'No messages match your filter' : 'No messages in topic'}
                </p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* DDL Tab */}
      {activeTab === 'ddl' && (
        <div className="rounded-xl border border-primary/10 overflow-hidden">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-primary/10 bg-primary/5">
            <span className="text-[10px] font-bold text-on-surface0 uppercase tracking-widest">Auto-Generated Definition</span>
            <button
              onClick={() => copyToClipboard(data.ddl)}
              className="flex items-center gap-1.5 text-xs text-on-surface0 hover:text-primary transition-colors"
            >
              <span className="material-symbols-outlined text-base">content_copy</span>
              Copy
            </button>
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
        <div className="rounded-xl border border-primary/10 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-primary/5 border-b border-primary/10 text-[10px] uppercase tracking-widest text-on-surface0">
                <th className="text-left px-5 py-3">Partition</th>
                <th className="text-right px-5 py-3">Start Offset</th>
                <th className="text-right px-5 py-3">End Offset</th>
                <th className="text-right px-5 py-3">Messages</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-primary/5">
              {Object.keys(data.topic.maxOffsets).map(p => {
                const partition = Number(p);
                const start = data.topic.minOffsets[partition] ?? 0;
                const end = data.topic.maxOffsets[partition] ?? 0;
                const count = end - start;
                return (
                  <tr key={partition} className="hover:bg-primary/5 transition-colors">
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
              <tr className="bg-primary/5 border-t border-primary/10">
                <td colSpan={3} className="px-5 py-2.5 text-[10px] font-bold text-on-surface0 uppercase tracking-widest">Total</td>
                <td className="px-5 py-2.5 text-right font-mono font-bold text-primary text-sm">
                  {data.topic.estimatedSize.toLocaleString()}
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      {/* Schema Tab */}
      {activeTab === 'schema' && (
        <div className="rounded-xl border border-primary/10 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-primary/5 border-b border-primary/10">
                <th className="text-left px-5 py-3 text-[10px] font-bold text-on-surface0 uppercase tracking-widest">Field</th>
                <th className="text-right px-5 py-3 text-[10px] font-bold text-on-surface0 uppercase tracking-widest">Type</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-primary/5">
              {Object.entries(data.schema).map(([field, type]) => (
                <tr key={field} className="hover:bg-primary/5 transition-colors">
                  <td className="px-5 py-3 font-medium text-on-surface">{field}</td>
                  <td className="px-5 py-3 text-right font-mono text-[11px] text-primary/80 uppercase">{type}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default TopicExplorer;
