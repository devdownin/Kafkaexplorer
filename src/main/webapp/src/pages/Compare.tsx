import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';

// Parse JSON safely, return null on failure
function tryParse(s: string): Record<string, unknown> | null {
  try { return JSON.parse(s); } catch { return null; }
}

// Compare two parsed JSON objects field by field
function diffFields(a: Record<string, unknown> | null, b: Record<string, unknown> | null): Record<string, 'added' | 'removed' | 'changed' | 'same'> {
  const result: Record<string, 'added' | 'removed' | 'changed' | 'same'> = {};
  const keys = new Set([...Object.keys(a ?? {}), ...Object.keys(b ?? {})]);
  keys.forEach(k => {
    if (!(k in (a ?? {}))) result[k] = 'added';
    else if (!(k in (b ?? {}))) result[k] = 'removed';
    else if (JSON.stringify((a ?? {})[k]) !== JSON.stringify((b ?? {})[k])) result[k] = 'changed';
    else result[k] = 'same';
  });
  return result;
}

const MessageCard: React.FC<{
  sample: string;
  paired?: string;
  side: 'A' | 'B';
}> = ({ sample, paired, side }) => {
  const parsed = tryParse(sample);
  const pairedParsed = paired ? tryParse(paired) : null;
  const diff = parsed && pairedParsed ? diffFields(
    side === 'A' ? parsed : pairedParsed,
    side === 'A' ? pairedParsed : parsed
  ) : null;

  if (!parsed) {
    return (
      <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 font-mono text-xs text-slate-300">
        {sample}
      </div>
    );
  }

  const fieldColors: Record<string, string> = {
    added: 'bg-green-500/10 text-green-400',
    removed: 'bg-red-500/10 text-red-400',
    changed: side === 'A' ? 'bg-red-500/10 text-red-400' : 'bg-green-500/10 text-green-400',
    same: '',
  };

  const entries = Object.entries(parsed);

  return (
    <div className="rounded-lg border border-primary/10 bg-background-dark/50 p-3 hover:border-primary/30 transition-all">
      <div className="font-mono text-xs space-y-1">
        {entries.map(([k, v]) => {
          const status = diff?.[k] ?? 'same';
          return (
            <div key={k} className={`flex justify-between px-1 rounded gap-3 ${fieldColors[status]}`}>
              <span className="text-slate-500 shrink-0">{k}:</span>
              <span className="truncate text-right">{JSON.stringify(v)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

const Compare: React.FC = () => {
  const { toast } = useToast();
  const [topics, setTopics] = useState<string[]>([]);
  const [topicA, setTopicA] = useState('');
  const [topicB, setTopicB] = useState('');
  const [syncCursors, setSyncCursors] = useState(true);
  const [showDiffOnly, setShowDiffOnly] = useState(false);
  const [loading, setLoading] = useState(false);
  const [samplesA, setSamplesA] = useState<string[]>([]);
  const [samplesB, setSamplesB] = useState<string[]>([]);
  const [hasResult, setHasResult] = useState(false);

  useEffect(() => {
    axios.get<string[]>('/api/compare/topics')
      .then(res => {
        setTopics(res.data);
        if (res.data.length >= 2) {
          setTopicA(res.data[0]);
          setTopicB(res.data[1]);
        }
      })
      .catch(() => toast('Failed to load topics', 'error'));
  }, []);

  const runCompare = async () => {
    if (!topicA || !topicB) { toast('Select two topics to compare', 'info'); return; }
    setLoading(true);
    setHasResult(false);
    try {
      const [resA, resB] = await Promise.all([
        axios.get<{ samples: string[] }>(`/api/topic/${topicA}`),
        axios.get<{ samples: string[] }>(`/api/topic/${topicB}`),
      ]);
      setSamplesA(resA.data.samples ?? []);
      setSamplesB(resB.data.samples ?? []);
      setHasResult(true);
      toast(`Loaded ${resA.data.samples.length} + ${resB.data.samples.length} messages`, 'success');
    } catch {
      toast('Failed to fetch topic samples', 'error');
    } finally {
      setLoading(false);
    }
  };

  // Compute diff summary
  const diffCount = hasResult
    ? Math.min(samplesA.length, samplesB.length) === 0 ? 0
    : Array.from({ length: Math.min(samplesA.length, samplesB.length) }).filter((_, i) => {
        const a = tryParse(samplesA[i]);
        const b = tryParse(samplesB[i]);
        return JSON.stringify(a) !== JSON.stringify(b);
      }).length
    : 0;

  const displayA = showDiffOnly
    ? samplesA.filter((s, i) => {
        const b = samplesB[i];
        return b == null || JSON.stringify(tryParse(s)) !== JSON.stringify(tryParse(b));
      })
    : samplesA;

  const displayB = showDiffOnly
    ? samplesB.filter((_, i) => {
        const a = samplesA[i];
        return a == null || JSON.stringify(tryParse(samplesA[i])) !== JSON.stringify(tryParse(samplesB[i]));
      })
    : samplesB;

  return (
    <div className="flex-1 flex flex-col p-4 gap-4 overflow-hidden h-full">
      {/* Query bar */}
      <section className="flex flex-col">
        <div className="flex flex-col border border-primary/20 rounded-xl overflow-hidden bg-background-dark/30">
          <div className="flex bg-primary/5 px-4 py-2.5 border-b border-primary/10 items-center justify-between">
            <span className="text-[10px] font-mono uppercase tracking-widest text-slate-400">Shared Filter Context (optional)</span>
            <div className="flex items-center gap-2">
              <span className="text-slate-400 hover:text-primary cursor-pointer transition-all">
                <span className="material-symbols-outlined text-lg">format_align_left</span>
              </span>
            </div>
          </div>
          <textarea
            className="w-full bg-transparent border-none focus:ring-0 font-mono text-sm p-4 h-16 text-primary resize-none placeholder:text-slate-600"
            placeholder="-- Optional: filter applied to both topics&#10;SELECT * FROM TABLE WHERE event_type = 'ORDER_CREATED'"
          />
          <div className="flex justify-between items-center p-3 bg-primary/5 border-t border-primary/10">
            <div className="flex items-center gap-6">
              <label className="flex items-center gap-2 cursor-pointer">
                <span className="text-[10px] uppercase text-slate-500 font-bold">Sync Scroll</span>
                <button
                  onClick={() => setSyncCursors(!syncCursors)}
                  className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${syncCursors ? 'bg-primary' : 'bg-slate-700'}`}
                >
                  <span className={`inline-block h-3 w-3 transform rounded-full bg-background-dark transition-transform ${syncCursors ? 'translate-x-5' : 'translate-x-1'}`} />
                </button>
              </label>
              <label className="flex items-center gap-2 cursor-pointer">
                <span className="text-[10px] uppercase text-slate-500 font-bold">Diff Only</span>
                <button
                  onClick={() => setShowDiffOnly(!showDiffOnly)}
                  className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${showDiffOnly ? 'bg-primary' : 'bg-slate-700'}`}
                >
                  <span className={`inline-block h-3 w-3 transform rounded-full bg-background-dark transition-transform ${showDiffOnly ? 'translate-x-5' : 'translate-x-1'}`} />
                </button>
              </label>
            </div>
            <button
              onClick={runCompare}
              disabled={loading}
              className="flex items-center gap-2 px-5 py-2 bg-primary text-background-dark font-bold rounded-lg hover:brightness-110 disabled:opacity-50 transition-all text-sm"
            >
              {loading
                ? <span className="material-symbols-outlined text-lg animate-spin">refresh</span>
                : <span className="material-symbols-outlined text-lg">compare_arrows</span>}
              {loading ? 'LOADING...' : 'RUN COMPARE'}
            </button>
          </div>
        </div>
      </section>

      {/* Side-by-side */}
      <section className="flex-1 flex gap-4 overflow-hidden">
        {/* Topic A */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-dark/30 overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] font-bold text-primary uppercase">Topic A (Source)</span>
              <select
                value={topicA}
                onChange={e => setTopicA(e.target.value)}
                className="bg-slate-900 border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer outline-none"
              >
                {topics.map(t => <option key={t} value={t} className="bg-slate-900 text-slate-100">{t}</option>)}
              </select>
            </div>
            <span className="text-xs text-slate-500">{samplesA.length} msgs</span>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {!hasResult && (
              <div className="p-8 text-center text-slate-600 text-xs uppercase tracking-widest">Select topics and run compare</div>
            )}
            {displayA.map((s, i) => (
              <MessageCard key={i} sample={s} paired={samplesB[i]} side="A" />
            ))}
          </div>
        </div>

        {/* Topic B */}
        <div className="flex-1 flex flex-col border border-primary/20 rounded-xl bg-background-dark/30 overflow-hidden">
          <div className="p-3 border-b border-primary/10 flex items-center justify-between bg-primary/5">
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] font-bold text-primary uppercase">Topic B (Target)</span>
              <select
                value={topicB}
                onChange={e => setTopicB(e.target.value)}
                className="bg-slate-900 border-none text-slate-100 font-bold p-0 focus:ring-0 text-sm cursor-pointer outline-none"
              >
                {topics.map(t => <option key={t} value={t} className="bg-slate-900 text-slate-100">{t}</option>)}
              </select>
            </div>
            <span className="text-xs text-slate-500">{samplesB.length} msgs</span>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
            {!hasResult && (
              <div className="p-8 text-center text-slate-600 text-xs uppercase tracking-widest">Select topics and run compare</div>
            )}
            {displayB.map((s, i) => (
              <MessageCard key={i} sample={s} paired={samplesA[i]} side="B" />
            ))}
          </div>
        </div>
      </section>

      {/* Diff Summary Bar */}
      <footer className="h-10 border-t border-primary/20 flex items-center px-4 bg-primary/5 rounded-lg justify-between text-xs">
        <div className="flex gap-4">
          {hasResult ? (
            <>
              <span className="text-slate-400">Messages compared: <b className="text-primary">{Math.min(samplesA.length, samplesB.length)}</b></span>
              <span className="text-slate-400">Differences: <b className={diffCount > 0 ? 'text-amber-400' : 'text-emerald-400'}>{diffCount}</b></span>
            </>
          ) : (
            <span className="text-slate-600">No comparison run yet</span>
          )}
        </div>
        <div className="flex gap-4 items-center">
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full bg-green-500" />
            <span className="text-slate-400 text-[10px]">Added / Higher</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full bg-red-500" />
            <span className="text-slate-400 text-[10px]">Removed / Lower</span>
          </div>
          {hasResult && (
            <button
              onClick={() => {
                const report = { topicA, topicB, diffCount, samplesA, samplesB, exportedAt: new Date().toISOString() };
                const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url; a.download = `diff-${topicA}-vs-${topicB}.json`; a.click();
                URL.revokeObjectURL(url);
              }}
              className="flex items-center gap-1 border-l border-primary/20 pl-4 hover:text-primary text-slate-400 transition-colors"
            >
              <span className="material-symbols-outlined text-sm">download</span>
              <span>Export</span>
            </button>
          )}
        </div>
      </footer>
    </div>
  );
};

export default Compare;
