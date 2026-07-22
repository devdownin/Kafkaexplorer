import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import { PageHeader, Button, CardSkeleton } from '../components/ui';

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

  // eslint-disable-next-line react-hooks/exhaustive-deps -- fetch once on mount
  useEffect(() => { fetchConfigs(); }, []);

  const getConfig = (key: string, defaultValue = '—') => configs.get(key) || defaultValue;

  if (loading) return (
    <div className="p-8 space-y-6">
      <PageHeader title="Cluster Configuration" description="Advanced broker-level parameters and infrastructure tuning." />
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <CardSkeleton lines={5} />
        <CardSkeleton lines={5} />
        <CardSkeleton className="xl:col-span-2" lines={4} />
      </div>
    </div>
  );
  if (error) return <ErrorBanner message={error} onRetry={fetchConfigs} />;

  return (
    <div className="p-8 space-y-6">
      <PageHeader
        title="Cluster Configuration"
        description="Advanced broker-level parameters and infrastructure tuning."
        actions={
          <>
            <Button variant="outline" icon="download">Export YAML</Button>
            <Button variant="primary" icon="save">Save Changes</Button>
          </>
        }
      />

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* 1. Offset Management — Critical */}
        <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl relative overflow-hidden">
          <div className="absolute top-0 right-0 px-3 py-1.5 bg-error/10 text-error text-[10px] font-mono font-bold tracking-widest uppercase border-l border-b border-error/25">
            Critical Path
          </div>
          <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-error">warning</span>
            Offset Management
          </h2>
          <div className="space-y-1">
            {[
              { key: 'offsets.topic.replication.factor', label: 'Impact on partition availability during broker failure' },
              { key: 'offsets.topic.num.partitions',     label: 'Scalability of internal offset management' },
              { key: 'offsets.topic.segment.bytes',      label: 'Size of offset log segments before rotation' },
              { key: 'offsets.retention.minutes',        label: 'How long offsets are stored after last commit' },
            ].map(item => (
              <div key={item.key} className="grid grid-cols-12 gap-4 items-center py-2 border-b border-outline-variant/60 last:border-0 hover:bg-primary/5 transition-colors px-2 rounded">
                <div className="col-span-6">
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[10px] text-on-surface-variant uppercase mt-0.5">{item.label}</p>
                </div>
                <div className="col-span-2 text-right font-bold text-on-surface">{getConfig(item.key)}</div>
                <div className="col-span-4 text-[11px] text-on-surface-variant text-right">
                  {item.key === 'offsets.topic.replication.factor' ? 'Must be ≤ active brokers' : 'Broker Default'}
                </div>
              </div>
            ))}
            <div className="mt-4 p-4 bg-error/5 border border-error/25 rounded-lg">
              <div className="flex items-start gap-3">
                <span className="material-symbols-outlined text-error text-[20px] shrink-0">error</span>
                <div>
                  <span className="text-[11px] font-bold text-error uppercase tracking-widest">Configuration Warning</span>
                  <p className="text-[12px] text-on-surface mt-1 leading-relaxed">
                    If <span className="font-mono text-error">offsets.retention.minutes</span> is misconfigured too low,
                    inactive consumers may lose their position and restart at zero (earliest), causing data duplication.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* 2. Consumer Groups */}
        <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl">
          <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
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
              <div key={item.key} className="flex justify-between items-center p-3 bg-surface-container-high rounded-lg border border-outline-variant/60">
                <div>
                  <code className="text-primary text-[13px] font-mono">{item.key}</code>
                  <p className="text-[11px] text-on-surface-variant mt-0.5">{item.label}</p>
                </div>
                <span className="text-sm font-bold text-on-surface">
                  {getConfig(item.key)}{item.key.endsWith('.ms') ? ' ms' : ''}
                </span>
              </div>
            ))}
            <p className="text-[11px] text-on-surface-variant italic pt-1">
              Impact: Optimized values prevent "rebalance storms" when many consumers join/leave simultaneously.
            </p>
          </div>
        </section>

        {/* 3. Log & retention */}
        <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl xl:col-span-2">
          <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">history</span>
            Log & Message Retention
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
              <div key={item.key} className="p-4 bg-surface-container-high border-l-2 border-primary rounded-lg">
                <div className="text-[10px] font-bold text-primary uppercase tracking-widest mb-1">{item.key}</div>
                <div className="text-lg font-bold text-on-surface mb-2 flex items-center gap-2">
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

        {/* 4. Replication & Durability */}
        <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl">
          <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">verified_user</span>
            Replication & Durability
          </h2>
          <div className="space-y-2">
            {[
              { key: 'min.insync.replicas',              label: 'Minimum replicas required for ACK' },
              { key: 'default.replication.factor',       label: 'Fallback factor for auto-created topics' },
              { key: 'unclean.leader.election.enable',   label: 'Prioritize availability over data loss' },
            ].map(item => (
              <div key={item.key} className="p-4 border border-outline-variant/60 bg-surface-container-high rounded-lg flex justify-between items-center hover:border-primary/30 transition-colors">
                <div>
                  <div className="text-[12px] font-mono text-primary">{item.key}</div>
                  <div className="text-[10px] text-on-surface-variant mt-0.5">{item.label}</div>
                </div>
                <div className={
                  item.key === 'unclean.leader.election.enable' && getConfig(item.key) === 'false'
                    ? 'text-sm font-bold text-error uppercase'
                    : 'text-2xl font-bold text-on-surface'
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

        {/* 5. I/O & Fetch Performance */}
        <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl">
          <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
            <span className="material-symbols-outlined text-primary">speed</span>
            I/O & Fetch Performance
          </h2>
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">num.network.threads</span>
              <div className="bg-surface-container-high p-2.5 border border-outline-variant/60 rounded-lg flex justify-between items-center">
                <span className="font-bold text-on-surface">{getConfig('num.network.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/40">settings_ethernet</span>
              </div>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">num.io.threads</span>
              <div className="bg-surface-container-high p-2.5 border border-outline-variant/60 rounded-lg flex justify-between items-center">
                <span className="font-bold text-on-surface">{getConfig('num.io.threads')}</span>
                <span className="material-symbols-outlined text-sm text-primary/40">storage</span>
              </div>
            </div>
            <div className="col-span-2 flex flex-col gap-1">
              <span className="text-[10px] font-bold uppercase text-on-surface-variant">queued.max.requests</span>
              <div className="bg-surface-container-high p-2.5 border border-outline-variant/60 rounded-lg flex justify-between items-center">
                <span className="font-bold text-on-surface">{getConfig('queued.max.requests')}</span>
                <span className="text-[10px] text-on-surface-variant">Latency buffer</span>
              </div>
            </div>
            <div className="col-span-2 space-y-2 pt-1">
              {['socket.receive.buffer.bytes', 'socket.send.buffer.bytes', 'socket.request.max.bytes'].map(key => (
                <div key={key} className="flex justify-between items-center text-[12px] border-b border-outline-variant/60 pb-1.5 last:border-0">
                  <span className="font-mono text-primary/80">{key}</span>
                  <span className="font-bold text-on-surface uppercase tracking-tight">{getConfig(key)}</span>
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
