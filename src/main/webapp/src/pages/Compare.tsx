import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import { Button } from '../components/ui';

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
      <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3 font-mono text-[12px] text-on-surface">
        {sample}
      </div>
    );
  }

  const fieldColors: Record<string, string> = {
    added: 'bg-success/10 text-success',
    removed: 'bg-error/10 text-error',
    changed: side === 'A' ? 'bg-error/10 text-error' : 'bg-success/10 text-success',
    same: '',
  };

  const entries = Object.entries(parsed);

  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3 hover:border-outline transition-colors">
      <div className="font-mono text-[12px] space-y-1">
        {entries.map(([k, v]) => {
          const status = diff?.[k] ?? 'same';
          return (
            <div key={k} className={`flex justify-between px-1 rounded gap-3 ${fieldColors[status]}`}>
              <span className="text-on-surface-variant shrink-0">{k}:</span>
              <span className="truncate text-right">{JSON.stringify(v)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

const Toggle: React.FC<{ label: string; on: boolean; onClick: () => void }> = ({ label, on, onClick }) => (
  <label className="flex items-center gap-2 cursor-pointer select-none">
    <span className="text-[11px] text-on-surface-variant font-medium">{label}</span>
    <button
      role="switch" aria-checked={on} aria-label={label} onClick={onClick}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${on ? 'bg-primary' : 'bg-surface-container-highest'}`}
    >
      <span className={`inline-block h-3.5 w-3.5 transform rounded-full transition-transform ${on ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant'}`} />
    </button>
  </label>
);

const TopicPane: React.FC<{
  side: 'A' | 'B'; topic: string; setTopic: (t: string) => void; count: number;
  display: string[]; paired: string[]; topics: string[]; hasResult: boolean;
}> = ({ side, topic, setTopic, count, display, paired, topics, hasResult }) => (
  <div className="flex-1 flex flex-col rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
    <div className="p-3 border-b border-outline-variant/60 flex items-center justify-between bg-surface-container-high/60">
      <div className="flex flex-col gap-0.5 min-w-0">
        <span className="text-[11px] font-medium text-primary uppercase tracking-[0.05em]">Topic {side} ({side === 'A' ? 'Source' : 'Target'})</span>
        <select
          value={topic}
          onChange={e => setTopic(e.target.value)}
          aria-label={`Topic ${side}`}
          className="bg-transparent border-none text-on-surface font-medium p-0 focus:outline-none text-[13px] cursor-pointer max-w-full truncate"
        >
          {topics.map(t => <option key={t} value={t} className="bg-surface-container text-on-surface">{t}</option>)}
        </select>
      </div>
      <span className="text-[12px] text-on-surface-variant tabular-nums shrink-0">{count} msgs</span>
    </div>
    <div className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
      {!hasResult && (
        <div className="p-8 text-center text-outline text-[12px]">Select topics and run compare</div>
      )}
      {display.map((s, i) => (
        <MessageCard key={i} sample={s} paired={paired[i]} side={side} />
      ))}
    </div>
  </div>
);

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
        <div className="flex flex-col rounded-xl overflow-hidden bg-surface-container ring-1 ring-white/[0.045]">
          <div className="flex bg-surface-container-high/60 px-4 py-2.5 border-b border-outline-variant/60 items-center justify-between">
            <span className="text-[11px] uppercase tracking-[0.05em] text-on-surface-variant">Shared Filter Context (optional)</span>
          </div>
          <textarea
            aria-label="Shared filter SQL"
            className="w-full bg-transparent border-none focus:outline-none font-mono text-[13px] p-4 h-16 text-on-surface resize-none placeholder:text-outline"
            placeholder="-- Optional: filter applied to both topics&#10;SELECT * FROM TABLE WHERE event_type = 'ORDER_CREATED'"
          />
          <div className="flex justify-between items-center gap-3 p-3 bg-surface-container-high/40 border-t border-outline-variant/60">
            <div className="flex items-center gap-5">
              <Toggle label="Sync scroll" on={syncCursors} onClick={() => setSyncCursors(!syncCursors)} />
              <Toggle label="Diff only" on={showDiffOnly} onClick={() => setShowDiffOnly(!showDiffOnly)} />
            </div>
            <Button variant="primary" icon={loading ? undefined : 'compare_arrows'} loading={loading} onClick={runCompare} disabled={loading}>
              {loading ? 'Loading…' : 'Run compare'}
            </Button>
          </div>
        </div>
      </section>

      {/* Side-by-side */}
      <section className="flex-1 flex gap-4 overflow-hidden">
        <TopicPane side="A" topic={topicA} setTopic={setTopicA} count={samplesA.length} display={displayA} paired={samplesB} topics={topics} hasResult={hasResult} />
        <TopicPane side="B" topic={topicB} setTopic={setTopicB} count={samplesB.length} display={displayB} paired={samplesA} topics={topics} hasResult={hasResult} />
      </section>

      {/* Diff Summary Bar */}
      <footer className="h-11 border border-outline-variant/60 bg-surface-container rounded-xl flex items-center px-4 justify-between gap-3 text-[12px]">
        <div className="flex gap-4">
          {hasResult ? (
            <>
              <span className="text-on-surface-variant">Compared <b className="text-on-surface tabular-nums">{Math.min(samplesA.length, samplesB.length)}</b></span>
              <span className="text-on-surface-variant">Differences <b className={`tabular-nums ${diffCount > 0 ? 'text-warning' : 'text-success'}`}>{diffCount}</b></span>
            </>
          ) : (
            <span className="text-outline">No comparison run yet</span>
          )}
        </div>
        <div className="flex gap-4 items-center">
          <div className="flex items-center gap-1.5">
            <div className="w-1.5 h-1.5 rounded-full bg-success" />
            <span className="text-on-surface-variant text-[11px]">Added / Higher</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-1.5 h-1.5 rounded-full bg-error" />
            <span className="text-on-surface-variant text-[11px]">Removed / Lower</span>
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
              className="flex items-center gap-1 border-l border-outline-variant pl-4 hover:text-on-surface text-on-surface-variant transition-colors"
            >
              <span className="material-symbols-outlined text-[16px]">download</span>
              <span>Export</span>
            </button>
          )}
        </div>
      </footer>
    </div>
  );
};

export default Compare;
