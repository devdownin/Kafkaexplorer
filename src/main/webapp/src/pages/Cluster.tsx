import React, { useState, useEffect } from 'react';
import axios from 'axios';

const Cluster: React.FC = () => {
  const [configs, setConfigs] = useState<Map<string, string>>(new Map());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchConfigs = async () => {
      try {
        const res = await axios.get<Record<string, string>>('/api/cluster/configs');
        setConfigs(new Map(Object.entries(res.data)));
      } catch (err) {
        console.error('Failed to fetch broker configs', err);
      } finally {
        setLoading(false);
      }
    };
    fetchConfigs();
  }, []);

  const getConfig = (key: string, defaultValue: string = '—') => {
    return configs.get(key) || defaultValue;
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="p-8 min-h-screen bg-surface-container-low">
      <header className="mb-10 flex justify-between items-end">
        <div>
          <h1 className="text-[30px] font-bold tracking-tight text-on-background">Cluster Configuration</h1>
          <p className="text-on-surface-variant text-sm mt-1">Advanced broker-level parameters and infrastructure tuning.</p>
        </div>
        <div className="flex gap-3">
          <button className="px-4 py-2 bg-surface-container border border-outline text-primary text-[12px] font-bold uppercase tracking-widest hover:bg-primary/10 transition-colors">Export YAML</button>
          <button className="px-4 py-2 bg-primary text-on-primary text-[12px] font-bold uppercase tracking-widest shadow-[0_0_15px_rgba(37,244,244,0.3)]">Save Changes</button>
        </div>
      </header>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* 1. Gestion des offsets (ULTRA critique) */}
        <section className="bg-surface-container border border-error/20 p-6 rounded relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-2 bg-error/10 text-error text-[10px] font-mono font-bold tracking-widest uppercase border-l border-b border-error/20">Critical Path</div>
          <h2 className="text-xl font-bold text-on-background flex items-center gap-2 mb-6">
            <span className="material-symbols-outlined text-error">warning</span>
            Gestion des offsets
          </h2>
          <div className="space-y-4">
            {[
              { key: 'offsets.topic.replication.factor', label: 'Impact on partition availability during broker failure' },
              { key: 'offsets.topic.num.partitions', label: 'Scalability of internal offset management' },
              { key: 'offsets.topic.segment.bytes', label: 'Size of offset log segments before rotation' },
              { key: 'offsets.retention.minutes', label: 'How long offsets are stored after last commit' },
            ].map((item) => (
              <div key={item.key} className="grid grid-cols-12 gap-4 items-center group/row py-2 border-b border-outline-variant hover:bg-primary/5 transition-colors px-2 rounded">
                <div className="col-span-6">
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[10px] text-on-surface-variant uppercase mt-1">{item.label}</p>
                </div>
                <div className="col-span-2 text-right font-bold text-on-background">{getConfig(item.key)}</div>
                <div className="col-span-4 text-[11px] text-on-surface-variant text-right">
                  {item.key === 'offsets.topic.replication.factor' ? 'Must be ≤ active brokers' : 'Broker Default'}
                </div>
              </div>
            ))}
            
            <div className="mt-4 p-4 bg-error-container border border-error/30 rounded">
              <div className="flex items-start gap-3">
                <span className="material-symbols-outlined text-error text-[20px]">error</span>
                <div>
                  <span className="text-[11px] font-bold text-error uppercase tracking-widest">CONFIGURATION WARNING</span>
                  <p className="text-[12px] text-on-background mt-1 leading-relaxed">
                    If <span className="font-mono text-error">offsets.retention.minutes</span> is misconfigured too low, inactive consumers may lose their position and restart at zero (earliest), causing significant data duplication or inconsistency.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* 2. Gestion des consumer groups */}
        <section className="bg-surface-container border border-outline p-6 rounded">
          <h2 className="text-xl font-bold text-on-background flex items-center gap-2 mb-6">
            <span className="material-symbols-outlined text-primary">groups</span>
            Consumer Groups & Coordination
          </h2>
          <div className="space-y-3">
            {[
              { key: 'group.initial.rebalance.delay.ms', label: 'Delay before triggering rebalance storms.' },
              { key: 'group.min.session.timeout.ms', label: 'Lower bound for client session timeouts.' },
              { key: 'group.max.session.timeout.ms', label: 'Upper bound to prevent ghost members.' },
              { key: 'group.max.size', label: 'Prevents oversized groups impacting cluster.' },
            ].map(item => (
              <div key={item.key} className="flex justify-between items-center p-3 bg-surface-container-low rounded border border-outline-variant">
                <div>
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[11px] text-on-surface-variant mt-1">{item.label}</p>
                </div>
                <div className="text-right">
                  <span className="text-sm font-bold">{getConfig(item.key)} {item.key.endsWith('.ms') ? 'ms' : ''}</span>
                </div>
              </div>
            ))}
            <div className="mt-2 text-[11px] text-on-surface-variant italic">Impact: Optimized values prevent "rebalance storms" when many consumers join/leave simultaneously.</div>
          </div>
        </section>

        {/* 3. Log & rétention */}
        <section className="bg-surface-container border border-outline p-6 rounded xl:col-span-2">
          <h2 className="text-xl font-bold text-on-background flex items-center gap-2 mb-6 uppercase tracking-tight">
            <span className="material-symbols-outlined text-primary">history</span>
            Log & rétention des messages
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {[
              { key: 'log.retention.ms', label: 'Time-based data expiration. Impacts historical replay window.' },
              { key: 'log.retention.bytes', label: 'Unlimited size-based retention. Prevents early cleanup by volume.' },
              { key: 'log.segment.bytes', label: 'Max segment size (1 GiB) before rolling a new file.' },
              { key: 'log.cleanup.policy', label: 'Policy to handle log rotation. Delete vs Compact impact disk usage.' },
              { key: 'log.min.cleanable.dirty.ratio', label: 'Log compaction frequency. High values save CPU, low values save disk.' },
              { key: 'log.segment.ms', label: 'Max time before log segment is rolled even if not full.' },
            ].map(item => (
              <div key={item.key} className="p-4 bg-surface-container-low border-l-2 border-primary rounded">
                <div className="text-[10px] font-bold text-primary uppercase tracking-widest mb-1">{item.key}</div>
                <div className="text-lg font-bold mb-2 flex items-center gap-2">
                  {getConfig(item.key)}
                  {item.key === 'log.cleanup.policy' && getConfig(item.key) === 'delete' && (
                    <span className="text-[10px] px-2 py-0.5 bg-primary/10 text-primary border border-primary/20 rounded">DEFAULT</span>
                  )}
                </div>
                <p className="text-[11px] text-on-surface-variant">{item.label}</p>
              </div>
            ))}
          </div>
        </section>

        {/* 4. Réplication & durabilité */}
        <section className="bg-surface-container border border-outline p-6 rounded">
          <h2 className="text-xl font-bold text-on-background flex items-center gap-2 mb-6">
            <span className="material-symbols-outlined text-primary">verified_user</span>
            Réplication & durabilité
          </h2>
          <div className="space-y-4">
            {[
              { key: 'min.insync.replicas', label: 'Minimum replicas required for ACK' },
              { key: 'default.replication.factor', label: 'Fallback factor for auto-created topics' },
              { key: 'unclean.leader.election.enable', label: 'Prioritize availability over data loss' },
            ].map(item => (
              <div key={item.key} className="p-4 border border-outline-variant bg-surface-container-lowest flex justify-between items-center group hover:border-primary/50 transition-all">
                <div>
                  <div className="text-[12px] font-mono text-primary">{item.key}</div>
                  <div className="text-[10px] text-on-surface-variant mt-1">{item.label}</div>
                </div>
                <div className={item.key === 'unclean.leader.election.enable' && getConfig(item.key) === 'false' ? 'text-sm font-bold text-error uppercase' : 'text-2xl font-bold text-on-background'}>
                  {getConfig(item.key)}
                </div>
              </div>
            ))}
            <div className="mt-2 flex items-start gap-2 text-[11px] text-primary/70">
              <span className="material-symbols-outlined text-[16px]">info</span>
              <span>High durability config: Prevents data loss but increases latency and may reduce availability during multi-broker failures.</span>
            </div>
          </div>
        </section>

        {/* 5. Performance I/O & fetch */}
        <section className="bg-surface-container border border-outline p-6 rounded">
          <h2 className="text-xl font-bold text-on-background flex items-center gap-2 mb-6">
            <span className="material-symbols-outlined text-primary">speed</span>
            Performance I/O & fetch
          </h2>
          <div className="grid grid-cols-2 gap-4">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">num.network.threads</span>
              <div className="bg-surface-container-low p-2 border border-outline rounded flex justify-between">
                <span className="font-bold">{getConfig('num.network.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/50">settings_ethernet</span>
              </div>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">num.io.threads</span>
              <div className="bg-surface-container-low p-2 border border-outline rounded flex justify-between">
                <span className="font-bold">{getConfig('num.io.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/50">storage</span>
              </div>
            </div>
            <div className="col-span-2">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">queued.max.requests</span>
              <div className="bg-surface-container-low p-2 border border-outline rounded flex justify-between mt-1">
                <span className="font-bold">{getConfig('queued.max.requests')}</span>
                <span className="text-[10px] text-on-surface-variant">Latency buffer</span>
              </div>
            </div>
            <div className="col-span-2 space-y-2">
              {['socket.receive.buffer.bytes', 'socket.send.buffer.bytes', 'socket.request.max.bytes'].map(key => (
                <div key={key} className="flex justify-between items-center text-[12px] border-b border-outline-variant pb-1 last:border-0">
                  <span className="font-mono text-primary/80">{key}</span>
                  <span className="font-bold uppercase tracking-tight">{getConfig(key)}</span>
                </div>
              ))}
            </div>
          </div>
        </section>
      </div>

      {/* FAB Tooltip Simulation */}
      <div className="fixed bottom-8 right-8 group">
        <button className="w-14 h-14 bg-primary text-on-primary rounded-full shadow-[0_0_20px_rgba(37,244,244,0.4)] flex items-center justify-center hover:scale-110 active:scale-95 transition-all">
          <span className="material-symbols-outlined text-[28px]">smart_toy</span>
        </button>
        <div className="absolute bottom-full right-0 mb-4 w-64 bg-surface-container-highest border border-primary/20 p-4 rounded-lg shadow-xl opacity-0 translate-y-2 group-hover:opacity-100 group-hover:translate-y-0 transition-all pointer-events-none">
          <div className="text-[10px] font-bold text-primary uppercase mb-2">AI Config Auditor</div>
          <p className="text-[12px] text-on-background">
            I've detected that <span className="text-primary">min.insync.replicas</span> is set to {getConfig('min.insync.replicas')}. This is optimal for durability in your cluster.
          </p>
        </div>
      </div>
    </div>
  );
};

export default Cluster;
