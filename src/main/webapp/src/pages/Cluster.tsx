import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import { PageHeader, Button, CardSkeleton } from '../components/ui';

interface ReplicaState {
  replicaId: number;
  isLeader: boolean;
  logEndOffset: number;
  lag: number;
  lastFetchTimestampMs: number | null;
  lastCaughtUpTimestampMs: number | null;
}

interface KraftQuorum {
  leaderId: number;
  leaderEpoch: number;
  highWatermark: number;
  voters: ReplicaState[];
  observers: ReplicaState[];
}

interface GroupInfo {
  groupId: string;
  type: string; // CLASSIC | CONSUMER (KIP-848) | SHARE (KIP-932) | STREAMS | UNKNOWN
  state: string;
}

interface VersionRange {
  min: number;
  max: number;
}

interface ClusterDetails {
  clusterId?: string;
  controllerId?: number | null;
  brokerCount?: number;
  kraftQuorum?: KraftQuorum;
  groups?: GroupInfo[];
  finalizedFeatures?: Record<string, VersionRange>;
  supportedFeatures?: Record<string, VersionRange>;
}

const GROUP_TYPE_STYLES: Record<string, string> = {
  CONSUMER: 'bg-primary/10 text-primary border-primary/20',
  SHARE: 'bg-tertiary/10 text-tertiary border-tertiary/25',
  STREAMS: 'bg-primary/10 text-primary border-primary/20',
  CLASSIC: 'bg-surface-container-high text-on-surface-variant border-outline-variant/60',
};

const GROUP_STATE_STYLES: Record<string, string> = {
  STABLE: 'text-primary',
  EMPTY: 'text-on-surface-variant',
  DEAD: 'text-error',
  NOT_READY: 'text-error',
};

const formatAge = (ts: number | null | undefined): string =>
  ts && ts > 0 ? `${Math.max(0, Math.round((Date.now() - ts) / 1000))}s ago` : '—';

const Cluster: React.FC = () => {
  const { toast } = useToast();
  const [configs, setConfigs] = useState<Map<string, string>>(new Map());
  const [details, setDetails] = useState<ClusterDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchConfigs = async () => {
    setLoading(true);
    setError(null);
    try {
      // Quorum/details failure must not take down the configs view (e.g. Zookeeper clusters)
      const [configsRes, detailsRes] = await Promise.all([
        axios.get<Record<string, string>>('/api/cluster/configs'),
        axios.get<ClusterDetails>('/api/cluster').catch(() => null),
      ]);
      setConfigs(new Map(Object.entries(configsRes.data)));
      setDetails(detailsRes ? detailsRes.data : null);
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
        {/* 0. KRaft Controller Quorum (hidden on Zookeeper-based clusters) */}
        {details?.kraftQuorum && (
          <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl xl:col-span-2">
            <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
              <span className="material-symbols-outlined text-primary">account_tree</span>
              KRaft Controller Quorum
              <span className="text-[10px] px-2 py-0.5 bg-primary/10 text-primary border border-primary/20 rounded font-mono tracking-widest uppercase">KIP-595</span>
            </h2>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-5">
              {[
                { label: 'Cluster ID', value: details.clusterId ?? '—', mono: true },
                { label: 'Quorum Leader', value: `Node ${details.kraftQuorum.leaderId}`, mono: false },
                { label: 'Leader Epoch', value: String(details.kraftQuorum.leaderEpoch), mono: false },
                { label: 'High Watermark', value: details.kraftQuorum.highWatermark.toLocaleString(), mono: false },
              ].map(stat => (
                <div key={stat.label} className="p-4 bg-surface-container-high border-l-2 border-primary rounded-lg">
                  <div className="text-[10px] font-bold text-primary uppercase tracking-widest mb-1">{stat.label}</div>
                  <div className={`font-bold text-on-surface truncate ${stat.mono ? 'text-sm font-mono' : 'text-lg'}`} title={stat.value}>
                    {stat.value}
                  </div>
                </div>
              ))}
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left">
                <thead>
                  <tr className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest border-b border-outline-variant/60">
                    <th className="py-2 px-2">Role</th>
                    <th className="py-2 px-2">Replica</th>
                    <th className="py-2 px-2 text-right">Log End Offset</th>
                    <th className="py-2 px-2 text-right">Lag</th>
                    <th className="py-2 px-2 text-right">Last Fetch</th>
                    <th className="py-2 px-2 text-right">Last Caught Up</th>
                  </tr>
                </thead>
                <tbody>
                  {[
                    ...details.kraftQuorum.voters.map(r => ({ ...r, role: 'Voter' })),
                    ...details.kraftQuorum.observers.map(r => ({ ...r, role: 'Observer' })),
                  ].map(replica => (
                    <tr key={`${replica.role}-${replica.replicaId}`} className="border-b border-outline-variant/60 last:border-0 hover:bg-primary/5 transition-colors">
                      <td className="py-2.5 px-2">
                        <span className={`text-[10px] px-2 py-0.5 rounded border font-bold uppercase tracking-widest ${
                          replica.role === 'Voter'
                            ? 'bg-primary/10 text-primary border-primary/20'
                            : 'bg-surface-container-high text-on-surface-variant border-outline-variant/60'
                        }`}>
                          {replica.role}
                        </span>
                      </td>
                      <td className="py-2.5 px-2 font-bold text-on-surface">
                        Node {replica.replicaId}
                        {replica.isLeader && (
                          <span className="ml-2 text-[10px] px-2 py-0.5 bg-tertiary/10 text-tertiary border border-tertiary/25 rounded font-bold uppercase tracking-widest">Leader</span>
                        )}
                      </td>
                      <td className="py-2.5 px-2 text-right font-mono text-on-surface">{replica.logEndOffset.toLocaleString()}</td>
                      <td className={`py-2.5 px-2 text-right font-mono font-bold ${replica.lag > 0 ? 'text-error' : 'text-on-surface-variant'}`}>
                        {replica.lag.toLocaleString()}
                      </td>
                      <td className="py-2.5 px-2 text-right text-[12px] text-on-surface-variant">{formatAge(replica.lastFetchTimestampMs)}</td>
                      <td className="py-2.5 px-2 text-right text-[12px] text-on-surface-variant">{formatAge(replica.lastCaughtUpTimestampMs)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-[11px] text-on-surface-variant italic pt-3">
              Voters elect the metadata log leader; observers (brokers) replicate it. Sustained lag or a stale
              "last caught up" timestamp on a voter degrades controller failover.
            </p>
          </section>
        )}

        {/* 0b. Client groups — classic / consumer (KIP-848) / share (KIP-932) / streams */}
        {details?.groups && (
          <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl xl:col-span-2">
            <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
              <span className="material-symbols-outlined text-primary">groups</span>
              Client Groups
              <span className="text-[11px] font-normal text-on-surface-variant">
                {details.groups.length} group{details.groups.length === 1 ? '' : 's'} — consumer (KIP-848), share (KIP-932), classic, streams
              </span>
            </h2>
            {details.groups.length === 0 ? (
              <p className="text-[12px] text-on-surface-variant italic">
                No registered client groups. Groups appear once a consumer with group management
                (subscribe) connects — the explorer's own sampling consumers use manual partition
                assignment and never register.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest border-b border-outline-variant/60">
                      <th className="py-2 px-2">Group ID</th>
                      <th className="py-2 px-2">Type</th>
                      <th className="py-2 px-2">State</th>
                    </tr>
                  </thead>
                  <tbody>
                    {details.groups.map(group => (
                      <tr key={group.groupId} className="border-b border-outline-variant/60 last:border-0 hover:bg-primary/5 transition-colors">
                        <td className="py-2.5 px-2 font-mono text-[13px] text-on-surface">{group.groupId}</td>
                        <td className="py-2.5 px-2">
                          <span className={`text-[10px] px-2 py-0.5 rounded border font-bold uppercase tracking-widest ${
                            GROUP_TYPE_STYLES[group.type] ?? GROUP_TYPE_STYLES.CLASSIC
                          }`}>
                            {group.type}
                          </span>
                        </td>
                        <td className={`py-2.5 px-2 text-[12px] font-bold ${GROUP_STATE_STYLES[group.state] ?? 'text-on-surface'}`}>
                          {group.state}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        )}

        {/* 0c. Feature versions — finalized vs supported (Kafka 4 / KRaft feature flags) */}
        {details?.supportedFeatures && Object.keys(details.supportedFeatures).length > 0 && (
          <section className="bg-surface-container ring-1 ring-white/[0.045] p-6 rounded-xl xl:col-span-2">
            <h2 className="text-lg font-bold text-on-surface flex items-center gap-2 mb-5">
              <span className="material-symbols-outlined text-primary">flag</span>
              Feature Versions
              <span className="text-[11px] font-normal text-on-surface-variant">finalized cluster-wide vs supported by brokers</span>
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {Object.entries(details.supportedFeatures).sort(([a], [b]) => a.localeCompare(b)).map(([feature, supported]) => {
                const finalized = details.finalizedFeatures?.[feature];
                const lagging = !finalized || finalized.max < supported.max;
                return (
                  <div key={feature} className={`p-4 bg-surface-container-high border-l-2 rounded-lg ${lagging ? 'border-warning' : 'border-primary'}`}>
                    <div className="text-[10px] font-bold text-primary uppercase tracking-widest mb-1">{feature}</div>
                    <div className="text-lg font-bold text-on-surface mb-1 flex items-center gap-2">
                      {finalized ? `v${finalized.max}` : '—'}
                      <span className={`text-[10px] px-2 py-0.5 rounded border font-bold uppercase tracking-widest ${
                        lagging
                          ? 'bg-warning/10 text-warning border-warning/25'
                          : 'bg-primary/10 text-primary border-primary/20'
                      }`}>
                        {lagging ? 'Lagging' : 'Up to date'}
                      </span>
                    </div>
                    <p className="text-[11px] text-on-surface-variant">
                      Brokers support up to v{supported.max}
                      {lagging && feature === 'metadata.version' && ' — finalize with kafka-features.sh upgrade'}
                    </p>
                  </div>
                );
              })}
            </div>
          </section>
        )}

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
