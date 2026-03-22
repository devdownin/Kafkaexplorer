import React, { useState, useEffect } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import { useToast } from '../components/Toast';

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

// Try to pretty-print JSON, return original string on failure
function tryFormatJson(raw: string): { formatted: string; isJson: boolean } {
  try {
    const parsed = JSON.parse(raw);
    return { formatted: JSON.stringify(parsed, null, 2), isJson: true };
  } catch {
    return { formatted: raw, isJson: false };
  }
}

const SampleCard: React.FC<{ sample: string; index: number; onCopy: (s: string) => void }> = ({ sample, index, onCopy }) => {
  const [expanded, setExpanded] = useState(index < 3);
  const { formatted, isJson } = tryFormatJson(sample);
  const lines = formatted.split('\n');
  const preview = lines.slice(0, 4).join('\n') + (lines.length > 4 ? '\n  ...' : '');

  return (
    <div className="border-b border-primary/5 last:border-b-0 group">
      <div className="flex items-start gap-3 p-4 hover:bg-primary/5 transition-colors">
        <span className="text-[10px] font-mono text-slate-600 mt-0.5 w-6 shrink-0">{index + 1}</span>
        <div className="flex-1 min-w-0">
          <pre className="font-mono text-[11px] text-slate-300 overflow-x-auto whitespace-pre-wrap break-all leading-relaxed">
            {expanded ? formatted : preview}
          </pre>
          {lines.length > 4 && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-2 text-[10px] font-bold text-primary hover:underline"
            >
              {expanded ? 'Collapse' : `Show all ${lines.length} lines`}
            </button>
          )}
        </div>
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
          {isJson && (
            <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-primary/10 text-primary">JSON</span>
          )}
          <button
            onClick={() => onCopy(formatted)}
            className="p-1.5 text-slate-500 hover:text-primary hover:bg-primary/10 rounded transition-colors"
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

  useEffect(() => {
    fetchTopicDetails();
  }, [name, readMode]);

  const fetchTopicDetails = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/topic/${name}?readMode=${readMode}`);
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
    // Navigate to query editor with pre-filled SQL
    navigate(`/query?sql=${encodeURIComponent(`SELECT * FROM "${name}" LIMIT 50`)}`);
  };

  if (loading && !data) return (
    <div className="flex-1 flex items-center justify-center p-12">
      <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
    </div>
  );

  if (!data) return (
    <div className="p-8 text-red-400 flex items-center gap-2">
      <span className="material-symbols-outlined">warning</span> Topic not found
    </div>
  );

  const filteredSamples = data.samples.filter(s =>
    !sampleFilter || s.toLowerCase().includes(sampleFilter.toLowerCase())
  );

  return (
    <div className="p-6 max-w-7xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-3">
        <Link to="/" className="flex items-center gap-1.5 text-[10px] font-bold text-slate-500 hover:text-primary uppercase tracking-widest transition-colors w-fit">
          <span className="material-symbols-outlined text-sm">chevron_left</span> Dashboard
        </Link>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold font-mono text-slate-100">{data.topic.name}</h1>
            {data.topic.name.toLowerCase().endsWith('.dlt') && (
              <span className="px-2 py-0.5 rounded border border-amber-500/30 bg-amber-500/10 text-amber-400 text-[10px] font-bold uppercase">
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
            Query this topic
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Partitions</p>
          <p className="text-3xl font-bold text-slate-100">{data.topic.partitions}</p>
        </div>
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Messages (Approx)</p>
          <p className="text-3xl font-bold text-primary">{data.topic.estimatedSize.toLocaleString()}</p>
        </div>
        <div className="rounded-xl border border-primary/10 bg-primary/5 p-5">
          <p className="text-[10px] font-bold text-slate-500 uppercase tracking-widest mb-1">Read Mode</p>
          <div className="flex gap-1 mt-1">
            {['earliest-offset', 'latest-offset'].map(mode => (
              <button
                key={mode}
                onClick={() => setReadMode(mode)}
                className={`px-2 py-1 text-[10px] font-bold rounded transition-colors ${readMode === mode ? 'bg-primary/20 text-primary' : 'bg-slate-800 text-slate-500 hover:bg-slate-700'}`}
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
                : 'border-transparent text-slate-500 hover:text-slate-300'
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
              <span className="material-symbols-outlined text-slate-500 text-lg">search</span>
              <input
                type="text"
                value={sampleFilter}
                onChange={e => setSampleFilter(e.target.value)}
                placeholder="Filter messages..."
                className="flex-1 bg-transparent border-none focus:ring-0 text-sm text-slate-100 placeholder:text-slate-600 outline-none"
              />
              {sampleFilter && (
                <button onClick={() => setSampleFilter('')} className="text-slate-500 hover:text-slate-300">
                  <span className="material-symbols-outlined text-base">close</span>
                </button>
              )}
            </div>
            <span className="text-xs text-slate-500 shrink-0">{filteredSamples.length} of {data.samples.length}</span>
          </div>

          <div className="rounded-xl border border-primary/10 overflow-hidden">
            {filteredSamples.map((sample, i) => (
              <SampleCard key={i} sample={sample} index={i} onCopy={copyToClipboard} />
            ))}
            {filteredSamples.length === 0 && (
              <div className="p-16 text-center text-slate-600 space-y-2">
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
            <span className="text-[10px] font-bold text-slate-500 uppercase tracking-widest">Auto-Generated Definition</span>
            <button
              onClick={() => copyToClipboard(data.ddl)}
              className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-primary transition-colors"
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
              <tr className="bg-primary/5 border-b border-primary/10 text-[10px] uppercase tracking-widest text-slate-500">
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
                    <td className="px-5 py-3 font-mono text-slate-300">P{partition}</td>
                    <td className="px-5 py-3 text-right font-mono text-slate-400">{start.toLocaleString()}</td>
                    <td className="px-5 py-3 text-right font-mono text-slate-400">{end.toLocaleString()}</td>
                    <td className="px-5 py-3 text-right">
                      <span className="font-mono font-bold text-primary">{count.toLocaleString()}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr className="bg-primary/5 border-t border-primary/10">
                <td colSpan={3} className="px-5 py-2.5 text-[10px] font-bold text-slate-500 uppercase tracking-widest">Total</td>
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
                <th className="text-left px-5 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-widest">Field</th>
                <th className="text-right px-5 py-3 text-[10px] font-bold text-slate-500 uppercase tracking-widest">Type</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-primary/5">
              {Object.entries(data.schema).map(([field, type]) => (
                <tr key={field} className="hover:bg-primary/5 transition-colors">
                  <td className="px-5 py-3 font-medium text-slate-200">{field}</td>
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
