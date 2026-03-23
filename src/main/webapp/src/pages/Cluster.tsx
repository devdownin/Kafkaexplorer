import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import LoadingSpinner from '../components/LoadingSpinner';
import ErrorBanner from '../components/ErrorBanner';

const Cluster: React.FC = () => {
  const { toast } = useToast();
  const [configs, setConfigs] = useState<Map<string, string>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchConfigs = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await axios.get<Record<string, string>>('/api/cluster/configs');
      setConfigs(new Map(Object.entries(res.data)));
    } catch {
      toast('Failed to fetch broker configs', 'error');
      setError('Failed to load cluster configuration');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchConfigs(); }, []);

  const getConfig = (key: string, defaultValue = '—') => configs.get(key) || defaultValue;

  if (loading) return <LoadingSpinner />;
  if (error) return <ErrorBanner message={error} onRetry={fetchConfigs} />;

  return (
    <div className="p-8 space-y-6">
      <header className="flex justify-between items-end">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">Cluster Configuration</h1>
          <p className="text-slate-400 text-sm mt-1">Advanced broker-level parameters and infrastructure tuning.</p>
        </div>
        <div className="flex gap-3">
          <button className="px-4 py-2 rounded-lg border border-primary/30 text-primary text-xs font-bold uppercase tracking-widest hover:bg-primary/10 transition-colors">
            Export YAML
          </button>
          <button className="px-4 py-2 rounded-lg bg-primary text-background-dark text-xs font-bold uppercase tracking-widest hover:brightness-110 transition-all">
            Save Changes
          </button>
        </div>
      </header>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* 1. Gestion des offsets — Critical */}
        <section className="bg-primary/5 border border-red-500/20 p-6 rounded-xl relative overflow-hidden">
          <div className="absolute top-0 right-0 px-3 py-1.5 bg-red-500/10 text-red-400 text-[10px] font-mono font-bold tracking-widest uppercase border-l border-b border-red-500/20">
            Critical Path
          </div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-red-400">warning</span>
            Gestion des offsets
          </h2>
          <div className="space-y-1">
            {[
              { key: 'offsets.topic.replication.factor', label: 'Impact on partition availability during broker failure' },
              { key: 'offsets.topic.num.partitions',     label: 'Scalability of internal offset management' },
              { key: 'offsets.topic.segment.bytes',      label: 'Size of offset log segments before rotation' },
              { key: 'offsets.retention.minutes',        label: 'How long offsets are stored after last commit' },
            ].map(item => (
              <div key={item.key} className="grid grid-cols-12 gap-4 items-center py-2 border-b border-primary/10 last:border-0 hover:bg-primary/5 transition-colors px-2 rounded">
                <div className="col-span-6">
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[10px] text-slate-500 uppercase mt-0.5">{item.label}</p>
                </div>
                <div className="col-span-2 text-right font-bold text-slate-100">{getConfig(item.key)}</div>
                <div className="col-span-4 text-[11px] text-slate-500 text-right">
                  {item.key === 'offsets.topic.replication.factor' ? 'Must be ≤ active brokers' : 'Broker Default'}
                </div>
              </div>
            ))}
            <div className="mt-4 p-4 bg-red-500/5 border border-red-500/20 rounded-lg">
              <div className="flex items-start gap-3">
                <span className="material-symbols-outlined text-red-400 text-[20px] shrink-0">error</span>
                <div>
                  <span className="text-[11px] font-bold text-red-400 uppercase tracking-widest">Configuration Warning</span>
                  <p className="text-[12px] text-slate-300 mt-1 leading-relaxed">
                    If <span className="font-mono text-red-400">offsets.retention.minutes</span> is misconfigured too low,
                    inactive consumers may lose their position and restart at zero (earliest), causing data duplication.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* 2. Consumer Groups */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-xl">
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">groups</span>
            Consumer Groups & Coordination
          </h2>
          <div className="space-y-2">
            {[
              { key: 'group.initial.rebalance.delay.ms', label: 'Delay before triggering rebalance storms.' },
              { key: 'group.min.session.timeout.ms',     label: 'Lower bound for client session timeouts.' },
              { key: 'group.max.session.timeout.ms',     label: 'Upper bound to prevent ghost members.' },
              { key: 'group.max.size',                   label: 'Prevents oversized groups impacting cluster.' },
            ].map(item => (
              <div key={item.key} className="flex justify-between items-center p-3 bg-background-dark/50 rounded-lg border border-primary/10">
                <div>
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[11px] text-slate-500 mt-0.5">{item.label}</p>
                </div>
                <span className="text-sm font-bold text-slate-100">
                  {getConfig(item.key)}{item.key.endsWith('.ms') ? ' ms' : ''}
                </span>
              </div>
            ))}
            <p className="text-[11px] text-slate-500 italic pt-1">
              Impact: Optimized values prevent "rebalance storms" when many consumers join/leave simultaneously.
            </p>
          </div>
        </section>

        {/* 3. Log & rétention */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-xl xl:col-span-2">
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2 mb-5 uppercase tracking-tight">
            <span className="material-symbols-outlined text-primary">history</span>
            Log & rétention des messages
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {[
              { key: 'log.retention.ms',              label: 'Time-based data expiration. Impacts historical replay window.' },
              { key: 'log.retention.bytes',           label: 'Unlimited size-based retention. Prevents early cleanup by volume.' },
              { key: 'log.segment.bytes',             label: 'Max segment size (1 GiB) before rolling a new file.' },
              { key: 'log.cleanup.policy',            label: 'Policy to handle log rotation. Delete vs Compact impact disk usage.' },
              { key: 'log.min.cleanable.dirty.ratio', label: 'Log compaction frequency. High values save CPU, low values save disk.' },
              { key: 'log.segment.ms',                label: 'Max time before log segment is rolled even if not full.' },
            ].map(item => (
              <div key={item.key} className="p-4 bg-background-dark/50 border-l-2 border-primary rounded-lg">
                <div className="text-[10px] font-bold text-primary uppercase tracking-widest mb-1">{item.key}</div>
                <div className="text-lg font-bold text-slate-100 mb-2 flex items-center gap-2">
                  {getConfig(item.key)}
                  {item.key === 'log.cleanup.policy' && getConfig(item.key) === 'delete' && (
                    <span className="text-[10px] px-2 py-0.5 bg-primary/10 text-primary border border-primary/20 rounded">DEFAULT</span>
                  )}
                </div>
                <p className="text-[11px] text-slate-500">{item.label}</p>
              </div>
            ))}
          </div>
        </section>

        {/* 4. Réplication & durabilité */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-xl">
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">verified_user</span>
            Réplication & durabilité
          </h2>
          <div className="space-y-2">
            {[
              { key: 'min.insync.replicas',              label: 'Minimum replicas required for ACK' },
              { key: 'default.replication.factor',       label: 'Fallback factor for auto-created topics' },
              { key: 'unclean.leader.election.enable',   label: 'Prioritize availability over data loss' },
            ].map(item => (
              <div key={item.key} className="p-4 border border-primary/10 bg-background-dark/40 rounded-lg flex justify-between items-center hover:border-primary/30 transition-colors">
                <div>
                  <div className="text-[12px] font-mono text-primary">{item.key}</div>
                  <div className="text-[10px] text-slate-500 mt-0.5">{item.label}</div>
                </div>
                <div className={
                  item.key === 'unclean.leader.election.enable' && getConfig(item.key) === 'false'
                    ? 'text-sm font-bold text-red-400 uppercase'
                    : 'text-2xl font-bold text-slate-100'
                }>
                  {getConfig(item.key)}
                </div>
              </div>
            ))}
            <div className="flex items-start gap-2 text-[11px] text-primary/70 pt-1">
              <span className="material-symbols-outlined text-[16px] shrink-0">info</span>
              <span>High durability config: Prevents data loss but increases latency and may reduce availability during multi-broker failures.</span>
            </div>
          </div>
        </section>

        {/* 5. Performance I/O & fetch */}
        <section className="bg-primary/5 border border-primary/10 p-6 rounded-xl">
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">speed</span>
            Performance I/O & fetch
          </h2>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-slate-500">num.network.threads</span>
              <div className="bg-background-dark/50 p-2.5 border border-primary/10 rounded-lg flex justify-between items-center">
                <span className="font-bold text-slate-100">{getConfig('num.network.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/40">settings_ethernet</span>
              </div>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-slate-500">num.io.threads</span>
              <div className="bg-background-dark/50 p-2.5 border border-primary/10 rounded-lg flex justify-between items-center">
                <span className="font-bold text-slate-100">{getConfig('num.io.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/40">storage</span>
              </div>
            </div>
            <div className="col-span-2 flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-slate-500">queued.max.requests</span>
              <div className="bg-background-dark/50 p-2.5 border border-primary/10 rounded-lg flex justify-between items-center">
                <span className="font-bold text-slate-100">{getConfig('queued.max.requests')}</span>
                <span className="text-[10px] text-slate-500">Latency buffer</span>
              </div>
            </div>
            <div className="col-span-2 space-y-2 pt-1">
              {['socket.receive.buffer.bytes', 'socket.send.buffer.bytes', 'socket.request.max.bytes'].map(key => (
                <div key={key} className="flex justify-between items-center text-[12px] border-b border-primary/10 pb-1.5 last:border-0">
                  <span className="font-mono text-primary/80">{key}</span>
                  <span className="font-bold text-slate-100 uppercase tracking-tight">{getConfig(key)}</span>
                </div>
              ))}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
};

export default Cluster;
