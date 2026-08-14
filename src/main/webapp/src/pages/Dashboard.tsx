// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useToast } from '../components/Toast';
import ErrorBanner from '../components/ErrorBanner';
import {
  PageHeader, Stat, Card, Badge, Button, EmptyState,
  Table, TableHead, TableBody, TableRow, Th, Td,
  Input, Select, useConfirm, StatGridSkeleton, TableSkeleton, type BadgeTone,
} from '../components/ui';

const PAGE_SIZES = [10, 25, 50, 100];
const DASHBOARD_REFRESH_MS = 5000;
const TOPIC_COUNT_KEY = 'dashboard.lastTopicCount';
type SortKey = 'name' | 'size' | 'state' | 'lastMessage';
type SortDir = 'asc' | 'desc';

interface DashboardData {
  topics: string[];
  topicSizes: Record<string, number>;
  totalMessages: number;
  tables: string[];
  jobs: Array<{
    queryId: string;
    flinkJobId: string;
    statementType: string;
    status: string;
    sql: string;
    startedAt: number;
    endedAt: number | null;
    cancelRequested: boolean;
  }>;
  health: boolean;
  topicLastMessages: Record<string, number | null>;
}

/**
 * En-tête de colonne triable. Défini au niveau du module : à l'intérieur du composant, chaque
 * rendu en créait un *type* neuf, que React démonte et remonte au lieu de le mettre à jour.
 */
const SortButton: React.FC<{
  k: SortKey;
  sortKey: SortKey;
  sortDir: SortDir;
  onToggle: (k: SortKey) => void;
  className?: string;
  children: React.ReactNode;
}> = ({ k, sortKey, sortDir, onToggle, className, children }) => (
  <button
    onClick={() => onToggle(k)}
    className={`flex items-center gap-1 hover:text-on-surface transition-colors ${className ?? ''}`}
  >
    {children}
    {sortKey !== k ? (
      <span className="material-symbols-outlined text-[15px] opacity-30">unfold_more</span>
    ) : sortDir === 'asc' ? (
      <span className="material-symbols-outlined text-[15px] text-primary">arrow_upward</span>
    ) : (
      <span className="material-symbols-outlined text-[15px] text-primary">arrow_downward</span>
    )}
  </button>
);

/**
 * « il y a 5 min » se calcule depuis un instant de référence *passé en paramètre* : appeler
 * `Date.now()` en plein rendu rend celui-ci impur, et fait dépendre l'affichage du moment où
 * React a choisi de re-rendre plutôt que de la fraîcheur des données.
 */
function formatLastMessage(ts: number | null | undefined, now: number): string {
  if (!ts) return '—';
  const diff = now - ts;
  if (diff < 60_000) return '< 1 min ago';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return new Date(ts).toLocaleDateString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

const Dashboard: React.FC = () => {
  const { toast } = useToast();
  const confirm = useConfirm();
  const navigate = useNavigate();
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [killingJob, setKillingJob] = useState<string | null>(null);
  const [hideEmpty, setHideEmpty] = useState(false);
  const [hideDlt, setHideDlt] = useState(false);
  /**
   * Compte des topics à la visite précédente, lu une seule fois. C'était une ref écrite dans un
   * effet et lue pendant le rendu — un état à initialisation paresseuse dit la même chose sans
   * lire une ref là où elle n'a pas encore de valeur. (Le relire à chaque rendu écrasait la
   * référence à chaque sondage de 5 s, et la tendance affichait toujours « No change ».)
   */
  const [previousVisitTopicCount] = useState<number | null>(() => {
    const stored = localStorage.getItem(TOPIC_COUNT_KEY);
    return stored !== null ? Number(stored) : null;
  });
  /** Instant de la dernière réponse : c'est lui qui date les « il y a 5 min », pas le rendu. */
  const [fetchedAt, setFetchedAt] = useState(() => Date.now());

  useEffect(() => {
    if (data) {
      localStorage.setItem(TOPIC_COUNT_KEY, String(data.topics.length));
    }
  }, [data]);

  const formatJobTime = (ms: number | null | undefined) =>
    ms ? new Date(ms).toLocaleString() : '—';

  const isTerminalStatus = (status: string) =>
    ['FINISHED', 'FAILED', 'CANCELED', 'CANCELLED'].includes(status.toUpperCase());

  const getJobBadgeTone = (status: string): BadgeTone => {
    const upper = status.toUpperCase();
    if (upper === 'RUNNING') return 'primary';
    if (['CANCELLING', 'CANCEL_REQUESTED'].includes(upper)) return 'warning';
    if (upper === 'FINISHED') return 'success';
    if (upper === 'FAILED') return 'error';
    return 'neutral';
  };

  /**
   * @param showSpinner remet la page en chargement *avant* l'appel — réservé à une relance
   *   manuelle. Le premier chargement ne le fait pas : la page démarre déjà en `loading`, et
   *   poser un état en plein corps d'effet déclenche un rendu en cascade avant la première peinture.
   * @param reportErrors faux pour le sondage de fond : un aller-retour raté en tâche de fond ne
   *   doit pas remplacer l'écran par une bannière d'erreur.
   */
  /** Le chargement lui-même : rien n'y est posé avant le premier `await`. */
  const loadDashboard = async (reportErrors: boolean) => {
    try {
      const response = await axios.get('/api/dashboard');
      setData(response.data);
      // `loadDashboard` ne tourne que depuis un effet ou un gestionnaire, jamais pendant le
      // rendu — ce que la règle, qui raisonne sur la portée du composant, ne peut pas voir.
      // eslint-disable-next-line react-hooks/purity -- appelé hors rendu
      setFetchedAt(Date.now());
      if (reportErrors) {
        setError(null);
      }
    } catch {
      if (reportErrors) {
        setError('Failed to fetch dashboard data');
      }
    } finally {
      setLoading(false);
    }
  };

  const fetchData = ({ showSpinner = false, reportErrors = true } = {}) => {
    if (showSpinner) {
      setLoading(true);
      setError(null);
    }
    return loadDashboard(reportErrors);
  };

  useEffect(() => {
    /*
     * Charger au montage *est* un effet, et il finit forcément par poser un état : la règle ne
     * peut pas être satisfaite en restructurant, seulement en confiant le chargement à une
     * bibliothèque de données ou à Suspense — une réarchitecture, pas une correction de lint.
     * L'exception est donc posée ici, à l'endroit qu'elle concerne, plutôt qu'éteinte partout.
     */
    // eslint-disable-next-line react-hooks/set-state-in-effect -- chargement initial
    void loadDashboard(true);

    const refresh = () => {
      if (document.visibilityState === 'visible') {
        void loadDashboard(false);
      }
    };

    const intervalId = window.setInterval(refresh, DASHBOARD_REFRESH_MS);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);

    return () => {
      window.clearInterval(intervalId);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, []);

  if (loading) return (
    <div className="p-4 md:p-6 space-y-6">
      <PageHeader title="Dashboard" description="Live overview of your Kafka cluster — topics, throughput and running Flink jobs." />
      <StatGridSkeleton count={4} />
      <div className="skeleton-shimmer h-5 w-28" />
      <TableSkeleton rows={8} columns={5} />
    </div>
  );
  if (error || !data) return <ErrorBanner message={error ?? 'Failed to load dashboard'} onRetry={() => void fetchData({ showSpinner: true })} />;

  const getState = (topic: string) =>
    data.topicSizes[topic] === 0 ? 'empty'
    : topic.toLowerCase().endsWith('.dlt') ? 'dlt'
    : 'healthy';

  const filteredTopics = data.topics
    .filter(t => t.toLowerCase().includes(searchTerm.toLowerCase()))
    .filter(t => !hideEmpty || (data.topicSizes[t] ?? 0) > 0)
    .filter(t => !hideDlt   || !t.toLowerCase().endsWith('.dlt'))
    .sort((a, b) => {
      let cmp = 0;
      if (sortKey === 'name') cmp = a.localeCompare(b);
      else if (sortKey === 'size') cmp = (data.topicSizes[a] ?? 0) - (data.topicSizes[b] ?? 0);
      else if (sortKey === 'state') cmp = getState(a).localeCompare(getState(b));
      else if (sortKey === 'lastMessage') {
        const ta = data.topicLastMessages?.[a] ?? null;
        const tb = data.topicLastMessages?.[b] ?? null;
        // null values always go to the end regardless of direction
        if (ta === null && tb === null) cmp = 0;
        else if (ta === null) return 1;
        else if (tb === null) return -1;
        else cmp = ta - tb;
      }
      return sortDir === 'asc' ? cmp : -cmp;
    });

  const totalPages = Math.max(1, Math.ceil(filteredTopics.length / pageSize));
  const pagedTopics = filteredTopics.slice(page * pageSize, (page + 1) * pageSize);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('asc'); }
    setPage(0);
  };

  const killJob = async (jobId: string) => {
    const ok = await confirm({
      title: 'Cancel this Flink job?',
      description: 'The running SQL statement will be cancelled. This cannot be undone.',
      confirmLabel: 'Kill job',
      tone: 'danger',
      icon: 'cancel',
    });
    if (!ok) return;
    setKillingJob(jobId);
    try {
      await axios.post(`/api/query/jobs/${jobId}/cancel`);
      toast('Job cancelled', 'success');
      void fetchData({ reportErrors: false });
    } catch {
      toast('Failed to cancel job', 'error');
    } finally {
      setKillingJob(null);
    }
  };

  const handleSearch = (term: string) => {
    setSearchTerm(term);
    setPage(0);
  };

  // Page number list (show max 7 buttons)
  const pageNumbers = (() => {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i);
    if (page < 4) return [0, 1, 2, 3, 4, -1, totalPages - 1];
    if (page > totalPages - 5) return [0, -1, totalPages - 5, totalPages - 4, totalPages - 3, totalPages - 2, totalPages - 1];
    return [0, -1, page - 1, page, page + 1, -2, totalPages - 1];
  })();

  const prevCount = previousVisitTopicCount ?? data.topics.length;
  const topicDiff = data.topics.length - prevCount;
  const topicTrend = topicDiff > 0 ? `+${topicDiff} since last visit`
                   : topicDiff < 0 ? `${topicDiff} since last visit`
                   : 'No change since last visit';

  const activeJobCount = data.jobs.length;

  function formatCount(num: number) {
    if (num >= 1000000000) return (num / 1000000000).toFixed(1) + 'B';
    if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toString();
  }

  const stateBadge = (state: string) =>
    state === 'empty' ? <Badge tone="neutral">Empty</Badge>
    : state === 'dlt' ? <Badge tone="warning" dot>DLT</Badge>
    : <Badge tone="success" dot>Healthy</Badge>;

  const pagerBtn = 'inline-flex items-center justify-center w-8 h-8 rounded-md border border-outline-variant text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high disabled:opacity-30 disabled:cursor-not-allowed transition-colors';

  return (
    <div className="p-4 md:p-6 space-y-6">
      <PageHeader
        title="Dashboard"
        description="Live overview of your Kafka cluster — topics, throughput and running Flink jobs."
        actions={
          <Button variant="secondary" icon="refresh" onClick={() => void fetchData({ showSpinner: true })}>Refresh</Button>
        }
      />

      {/* KPI Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Stat
          label="Total Topics" icon="format_list_bulleted"
          tone={topicDiff !== 0 ? 'primary' : 'none'}
          value={data.topics.length.toLocaleString()}
          hint={<span className={topicDiff !== 0 ? 'text-primary' : ''}>{topicTrend}</span>}
        />
        <Stat
          label="Message Count" icon="bolt" tone="primary"
          value={formatCount(data.totalMessages)}
          hint={data.totalMessages > 0 ? 'Active ingest' : 'No activity'}
        />
        <Stat
          label="Flink Tables" icon="database"
          value={data.tables.length.toLocaleString()}
          hint={data.tables.length > 0 ? `${data.tables.length} registered` : 'None registered'}
        />
        <Stat
          label="Active Jobs" icon="sync" tone={data.health ? 'success' : 'error'}
          value={activeJobCount.toLocaleString()}
          hint={
            <span className={`inline-flex items-center gap-1.5 ${data.health ? 'text-success' : 'text-error'}`}>
              <span className={`w-1.5 h-1.5 rounded-full ${data.health ? 'bg-success' : 'bg-error'}`} />
              {data.health ? 'Healthy' : 'Degraded'}
            </span>
          }
        />
      </div>

      {/* Topics */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[15px] font-semibold text-on-surface flex items-center gap-2 shrink-0">
            Topics
            <span className="text-[12px] font-normal text-on-surface-variant tabular-nums">({filteredTopics.length})</span>
          </h2>
          <div className="flex flex-wrap items-center gap-3 justify-end">
            <div className="relative w-full max-w-xs sm:w-64">
              <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none">search</span>
              <Input
                className="pl-9 pr-8 h-9"
                placeholder="Filter topics…"
                aria-label="Filter topics"
                value={searchTerm}
                onChange={e => handleSearch(e.target.value)}
              />
              {searchTerm && (
                <button onClick={() => handleSearch('')} aria-label="Clear filter" className="absolute right-2 top-1/2 -translate-y-1/2 text-outline hover:text-on-surface">
                  <span className="material-symbols-outlined text-[16px]">close</span>
                </button>
              )}
            </div>

            <div className="flex items-center gap-3 shrink-0">
              {([
                { label: 'Hide empty', value: hideEmpty, set: setHideEmpty },
                { label: 'Hide DLT',   value: hideDlt,   set: setHideDlt   },
              ] as const).map(sw => (
                <label key={sw.label} className="flex items-center gap-1.5 cursor-pointer select-none group">
                  <span className="text-[11px] font-medium text-on-surface-variant group-hover:text-on-surface transition-colors whitespace-nowrap">
                    {sw.label}
                  </span>
                  <button
                    role="switch"
                    aria-checked={sw.value}
                    aria-label={sw.label}
                    onClick={() => { sw.set(!sw.value); setPage(0); }}
                    className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${sw.value ? 'bg-primary' : 'bg-surface-container-highest'}`}
                  >
                    <span className={`inline-block h-3.5 w-3.5 transform rounded-full transition-transform ${sw.value ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant'}`} />
                  </button>
                </label>
              ))}
            </div>

            <div className="flex items-center gap-1.5 text-[12px] text-on-surface-variant">
              <span>Show</span>
              <Select
                aria-label="Rows per page"
                value={pageSize}
                onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
                className="h-9 w-auto"
              >
                {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
              </Select>
            </div>
          </div>
        </div>

        <Table>
          <TableHead>
            <tr>
              <Th className="w-1/2"><SortButton k="name" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Topic Name</SortButton></Th>
              <Th><SortButton k="size" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Messages</SortButton></Th>
              <Th><SortButton k="state" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>State</SortButton></Th>
              <Th><SortButton k="lastMessage" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Last Message</SortButton></Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </TableHead>
          <TableBody>
            {pagedTopics.map(topic => (
              <TableRow
                key={topic}
                className="group cursor-pointer"
                onDoubleClick={() => navigate(`/topic/${topic}`)}
              >
                <Td className="font-mono font-medium text-on-surface">{topic}</Td>
                <Td className="text-on-surface-variant tabular-nums">{(data.topicSizes[topic] ?? 0).toLocaleString()}</Td>
                <Td>{stateBadge(getState(topic))}</Td>
                <Td className="text-on-surface-variant tabular-nums" title={data.topicLastMessages?.[topic] ? new Date(data.topicLastMessages[topic]!).toLocaleString() : undefined}>
                  {formatLastMessage(data.topicLastMessages?.[topic], fetchedAt)}
                </Td>
                <Td className="text-right">
                  <Link to={`/topic/${topic}`} className="inline-flex text-on-surface-variant hover:text-primary transition-colors" title="Explore topic" aria-label={`Explore ${topic}`}>
                    <span className="material-symbols-outlined text-[19px]">visibility</span>
                  </Link>
                </Td>
              </TableRow>
            ))}
            {pagedTopics.length === 0 && (
              <tr>
                <td colSpan={5}>
                  <EmptyState
                    icon="search_off"
                    title={searchTerm ? 'No matching topics' : 'No topics found'}
                    description={searchTerm ? `Nothing matches “${searchTerm}”.` : 'This cluster has no topics yet.'}
                  />
                </td>
              </tr>
            )}
          </TableBody>
        </Table>

        {/* Pagination */}
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <p className="text-[12px] text-on-surface-variant tabular-nums">
            {filteredTopics.length === 0
              ? 'No results'
              : `${page * pageSize + 1}–${Math.min((page + 1) * pageSize, filteredTopics.length)} of ${filteredTopics.length}`}
          </p>
          <div className="flex items-center gap-1">
            <button onClick={() => setPage(0)} disabled={page === 0} className={pagerBtn} title="First page" aria-label="First page">
              <span className="material-symbols-outlined text-[18px]">first_page</span>
            </button>
            <button onClick={() => setPage(p => p - 1)} disabled={page === 0} className={pagerBtn} aria-label="Previous page">
              <span className="material-symbols-outlined text-[18px]">chevron_left</span>
            </button>
            <div className="flex items-center gap-1 mx-1">
              {pageNumbers.map((n, i) =>
                n < 0 ? (
                  <span key={`ellipsis-${i}`} className="w-8 text-center text-outline text-[12px]">…</span>
                ) : (
                  <button
                    key={n}
                    onClick={() => setPage(n)}
                    aria-label={`Page ${n + 1}`}
                    aria-current={n === page ? 'page' : undefined}
                    className={`w-8 h-8 text-[12px] rounded-md transition-colors tabular-nums ${
                      n === page
                        ? 'bg-primary text-on-primary font-semibold'
                        : 'border border-outline-variant text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high'
                    }`}
                  >
                    {n + 1}
                  </button>
                )
              )}
            </div>
            <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1} className={pagerBtn} aria-label="Next page">
              <span className="material-symbols-outlined text-[18px]">chevron_right</span>
            </button>
            <button onClick={() => setPage(totalPages - 1)} disabled={page >= totalPages - 1} className={pagerBtn} title="Last page" aria-label="Last page">
              <span className="material-symbols-outlined text-[18px]">last_page</span>
            </button>
          </div>
        </div>
      </section>

      {/* Flink Jobs */}
      <section className="space-y-3">
        <h2 className="text-[15px] font-semibold text-on-surface flex items-center gap-2">
          Flink SQL Jobs
          <span className="text-[12px] font-normal text-on-surface-variant tabular-nums">({data.jobs.length})</span>
        </h2>
        {data.jobs.length === 0 ? (
          <Card padding="none">
            <EmptyState icon="cloud_off" title="No active jobs" description="Long-running Flink SQL statements will appear here while they execute." />
          </Card>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {data.jobs.map(job => (
              <Card key={job.queryId} padding="none" className="p-4 flex flex-col gap-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <p className="text-[11px] uppercase tracking-[0.05em] text-on-surface-variant">{job.statementType.replace('_', ' ')}</p>
                    <p className="text-[12px] font-mono text-on-surface line-clamp-2 mt-0.5">{job.sql}</p>
                    <p className="text-[11px] text-outline font-mono mt-1">Query: {job.queryId.substring(0, 16)}</p>
                    <p className="text-[11px] text-outline font-mono">Flink: {job.flinkJobId.substring(0, 16)}</p>
                  </div>
                  <Badge tone={getJobBadgeTone(job.status)} dot>{job.status}</Badge>
                </div>
                <div className="grid grid-cols-2 gap-2 text-[11px] text-on-surface-variant font-mono">
                  <div>
                    <p className="uppercase tracking-[0.05em] text-outline">Started</p>
                    <p>{formatJobTime(job.startedAt)}</p>
                  </div>
                  <div>
                    <p className="uppercase tracking-[0.05em] text-outline">Ended</p>
                    <p>{formatJobTime(job.endedAt)}</p>
                  </div>
                </div>
                {job.cancelRequested && (
                  <p className="text-[11px] text-warning font-mono">Cancellation requested</p>
                )}
                <Button
                  variant="danger" size="sm" className="w-full"
                  onClick={() => killJob(job.queryId)}
                  loading={killingJob === job.queryId}
                  disabled={killingJob === job.queryId || isTerminalStatus(job.status)}
                  icon={killingJob === job.queryId ? undefined : 'cancel'}
                >
                  {killingJob === job.queryId ? 'Killing…' : 'Kill Job'}
                </Button>
              </Card>
            ))}
          </div>
        )}
      </section>
    </div>
  );
};

export default Dashboard;
