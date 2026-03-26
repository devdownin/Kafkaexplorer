import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useToast } from '../components/Toast';
import LoadingSpinner from '../components/LoadingSpinner';
import ErrorBanner from '../components/ErrorBanner';

const PAGE_SIZES = [10, 25, 50, 100];
type SortKey = 'name' | 'size' | 'state' | 'lastMessage';
type SortDir = 'asc' | 'desc';

interface DashboardData {
  topics: string[];
  topicSizes: Record<string, number>;
  totalMessages: number;
  tables: string[];
  jobs: Record<string, any>;
  health: boolean;
  topicLastMessages: Record<string, number | null>;
}

const Dashboard: React.FC = () => {
  const { toast } = useToast();
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

  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await axios.get('/api/dashboard');
      setData(response.data);
    } catch (err) {
      setError('Failed to fetch dashboard data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  if (loading) return <LoadingSpinner />;
  if (error || !data) return <ErrorBanner message={error ?? 'Failed to load dashboard'} onRetry={fetchData} />;

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
    setKillingJob(jobId);
    try {
      await axios.post(`/api/query/cancel/${jobId}`);
      toast('Job cancelled', 'success');
      const response = await axios.get('/api/dashboard');
      setData(response.data);
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

  const SortIcon = ({ k }: { k: SortKey }) =>
    sortKey !== k ? (
      <span className="material-symbols-outlined text-sm opacity-30">unfold_more</span>
    ) : sortDir === 'asc' ? (
      <span className="material-symbols-outlined text-sm text-primary">arrow_upward</span>
    ) : (
      <span className="material-symbols-outlined text-sm text-primary">arrow_downward</span>
    );

  // Page number list (show max 7 buttons)
  const pageNumbers = (() => {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i);
    if (page < 4) return [0, 1, 2, 3, 4, -1, totalPages - 1];
    if (page > totalPages - 5) return [0, -1, totalPages - 5, totalPages - 4, totalPages - 3, totalPages - 2, totalPages - 1];
    return [0, -1, page - 1, page, page + 1, -2, totalPages - 1];
  })();

  const topicCountKey = 'dashboard.lastTopicCount';
  const prevCount = Number(localStorage.getItem(topicCountKey) ?? data.topics.length);
  const topicDiff = data.topics.length - prevCount;
  localStorage.setItem(topicCountKey, String(data.topics.length));
  const topicTrend = topicDiff > 0 ? `+${topicDiff} since last visit`
                   : topicDiff < 0 ? `${topicDiff} since last visit`
                   : 'No change since last visit';

  const activeJobCount = Object.keys(data.jobs).length;
  const kpis = [
    { label: 'Total Topics', value: data.topics.length.toString(), icon: 'format_list_bulleted', color: topicDiff !== 0 ? 'text-primary' : 'text-slate-500', trend: topicTrend },
    { label: 'Message Count', value: formatCount(data.totalMessages), icon: 'bolt', color: 'text-primary', trend: data.totalMessages > 0 ? 'Active Ingest' : 'No Activity' },
    { label: 'Flink Tables', value: data.tables.length.toString(), icon: 'database', color: 'text-slate-400', trend: data.tables.length > 0 ? `${data.tables.length} registered` : 'None registered' },
    { label: 'Active Jobs', value: activeJobCount.toString(), icon: 'sync', color: data.health ? 'text-emerald-500' : 'text-red-500', trend: data.health ? '100% Health' : 'Degraded' },
  ];

  function formatLastMessage(ts: number | null | undefined): string {
    if (!ts) return '—';
    const now = Date.now();
    const diff = now - ts;
    if (diff < 60_000) return '< 1 min ago';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return new Date(ts).toLocaleDateString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  }

  function formatCount(num: number) {
    if (num >= 1000000000) return (num / 1000000000).toFixed(1) + 'B';
    if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toString();
  }

  return (
    <div className="flex-1 overflow-y-auto p-6 space-y-6">
      {/* KPI Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {kpis.map((kpi) => (
          <div key={kpi.label} className="bg-white dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-xl p-5">
            <p className="text-slate-500 dark:text-slate-400 text-sm font-medium">{kpi.label}</p>
            <h3 className="text-3xl font-bold mt-1">{kpi.value}</h3>
            <div className={`mt-2 flex items-center text-xs font-medium ${kpi.color}`}>
              <span className="material-symbols-outlined text-xs mr-1">{kpi.icon}</span> {kpi.trend}
            </div>
          </div>
        ))}
      </div>

      {/* Topics — full width */}
      <div className="space-y-3">
        {/* Toolbar */}
        <div className="flex items-center justify-between gap-4">
          <h2 className="text-xl font-bold flex items-center gap-2 shrink-0">
            <span className="material-symbols-outlined text-primary">format_list_bulleted</span>
            Topics
            <span className="text-sm font-normal text-slate-500">({filteredTopics.length})</span>
          </h2>
          <div className="flex items-center gap-2 flex-1 justify-end">
            {/* Search */}
            <div className="flex items-center gap-2 bg-background-dark border border-primary/20 rounded-lg px-3 py-1.5 flex-1 max-w-xs focus-within:border-primary/40 transition-colors">
              <span className="material-symbols-outlined text-slate-400 text-base">search</span>
              <input
                className="bg-transparent border-none focus:ring-0 text-sm w-full p-0 outline-none placeholder:text-slate-600"
                placeholder="Filter topics..."
                type="text"
                value={searchTerm}
                onChange={e => handleSearch(e.target.value)}
              />
              {searchTerm && (
                <button onClick={() => handleSearch('')} className="text-slate-500 hover:text-slate-300">
                  <span className="material-symbols-outlined text-base">close</span>
                </button>
              )}
            </div>
            {/* Switches */}
            <div className="flex items-center gap-3 shrink-0">
              {([
                { label: 'Hide empty', value: hideEmpty, set: setHideEmpty },
                { label: 'Hide DLT',   value: hideDlt,   set: setHideDlt   },
              ] as const).map(sw => (
                <label key={sw.label} className="flex items-center gap-1.5 cursor-pointer select-none group">
                  <span className="text-[10px] font-bold uppercase text-slate-500 group-hover:text-slate-300 transition-colors whitespace-nowrap">
                    {sw.label}
                  </span>
                  <button
                    role="switch"
                    aria-checked={sw.value}
                    onClick={() => { sw.set(!sw.value); setPage(0); }}
                    className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${sw.value ? 'bg-primary' : 'bg-slate-700'}`}
                  >
                    <span className={`inline-block h-3 w-3 transform rounded-full bg-background-dark transition-transform ${sw.value ? 'translate-x-5' : 'translate-x-1'}`} />
                  </button>
                </label>
              ))}
            </div>

            {/* Page size */}
            <div className="flex items-center gap-1.5 text-xs text-slate-500">
              <span>Show</span>
              <select
                value={pageSize}
                onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
                className="bg-background-dark border border-primary/20 rounded-lg px-2 py-1.5 text-slate-200 text-xs outline-none focus:border-primary/40"
              >
                {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
              <span>per page</span>
            </div>
          </div>
        </div>

        {/* Table */}
        <div className="bg-primary/5 border border-primary/10 rounded-xl overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-primary/10 text-[10px] font-bold text-slate-500 uppercase tracking-wider border-b border-primary/10">
                <th className="px-4 py-3 w-1/2">
                  <button onClick={() => toggleSort('name')} className="flex items-center gap-1 hover:text-slate-200 transition-colors">
                    Topic Name <SortIcon k="name" />
                  </button>
                </th>
                <th className="px-4 py-3">
                  <button onClick={() => toggleSort('size')} className="flex items-center gap-1 hover:text-slate-200 transition-colors">
                    Messages <SortIcon k="size" />
                  </button>
                </th>
                <th className="px-4 py-3">
                  <button onClick={() => toggleSort('state')} className="flex items-center gap-1 hover:text-slate-200 transition-colors">
                    State <SortIcon k="state" />
                  </button>
                </th>
                <th className="px-4 py-3">
                  <button onClick={() => toggleSort('lastMessage')} className="flex items-center gap-1 hover:text-slate-200 transition-colors">
                    Last Message <SortIcon k="lastMessage" />
                  </button>
                </th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm divide-y divide-primary/5">
              {pagedTopics.map(topic => (
                <tr key={topic}
                  className="hover:bg-primary/5 transition-colors group cursor-pointer"
                  onDoubleClick={() => navigate(`/topic/${topic}`)}
                >
                  <td className="px-4 py-3 font-mono font-medium text-slate-200">{topic}</td>
                  <td className="px-4 py-3 text-slate-400 tabular-nums">
                    {(data.topicSizes[topic] ?? 0).toLocaleString()}
                  </td>
                  <td className="px-4 py-3">
                    {getState(topic) === 'empty' && (
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-500/20 text-slate-400 uppercase">Empty</span>
                    )}
                    {getState(topic) === 'dlt' && (
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500/20 text-amber-400 uppercase">DLT</span>
                    )}
                    {getState(topic) === 'healthy' && (
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/20 text-emerald-400 uppercase">Healthy</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-400 text-xs tabular-nums" title={data.topicLastMessages?.[topic] ? new Date(data.topicLastMessages[topic]!).toLocaleString() : undefined}>
                    {formatLastMessage(data.topicLastMessages?.[topic])}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Link to={`/topic/${topic}`} className="text-slate-500 hover:text-primary transition-colors" title="Explore topic">
                      <span className="material-symbols-outlined text-lg">visibility</span>
                    </Link>
                  </td>
                </tr>
              ))}
              {pagedTopics.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center text-slate-600 text-sm">
                    {searchTerm ? `No topics match "${searchTerm}"` : 'No topics found'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          {/* Pagination footer */}
          <div className="px-4 py-3 bg-primary/5 border-t border-primary/10 flex items-center justify-between">
            <p className="text-[10px] text-slate-500 uppercase font-bold tracking-wider">
              {filteredTopics.length === 0
                ? 'No results'
                : `${page * pageSize + 1}–${Math.min((page + 1) * pageSize, filteredTopics.length)} of ${filteredTopics.length}`}
            </p>

            <div className="flex items-center gap-1">
              {/* First */}
              <button
                onClick={() => setPage(0)}
                disabled={page === 0}
                className="p-1 rounded border border-primary/10 text-slate-500 hover:text-primary hover:border-primary/30 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                title="First page"
              >
                <span className="material-symbols-outlined text-sm">first_page</span>
              </button>
              {/* Prev */}
              <button
                onClick={() => setPage(p => p - 1)}
                disabled={page === 0}
                className="p-1 rounded border border-primary/10 text-slate-500 hover:text-primary hover:border-primary/30 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
              >
                <span className="material-symbols-outlined text-sm">chevron_left</span>
              </button>

              {/* Page numbers */}
              <div className="flex items-center gap-1 mx-1">
                {pageNumbers.map((n, i) =>
                  n < 0 ? (
                    <span key={`ellipsis-${i}`} className="w-7 text-center text-slate-600 text-xs">…</span>
                  ) : (
                    <button
                      key={n}
                      onClick={() => setPage(n)}
                      className={`w-7 h-7 text-xs rounded transition-colors ${
                        n === page
                          ? 'bg-primary text-background-dark font-bold'
                          : 'border border-primary/10 text-slate-400 hover:text-primary hover:border-primary/30'
                      }`}
                    >
                      {n + 1}
                    </button>
                  )
                )}
              </div>

              {/* Next */}
              <button
                onClick={() => setPage(p => p + 1)}
                disabled={page >= totalPages - 1}
                className="p-1 rounded border border-primary/10 text-slate-500 hover:text-primary hover:border-primary/30 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
              >
                <span className="material-symbols-outlined text-sm">chevron_right</span>
              </button>
              {/* Last */}
              <button
                onClick={() => setPage(totalPages - 1)}
                disabled={page >= totalPages - 1}
                className="p-1 rounded border border-primary/10 text-slate-500 hover:text-primary hover:border-primary/30 disabled:opacity-25 disabled:cursor-not-allowed transition-colors"
                title="Last page"
              >
                <span className="material-symbols-outlined text-sm">last_page</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Flink Jobs — below topics */}
      <div className="space-y-3">
        <h2 className="text-xl font-bold flex items-center gap-2">
          <span className="material-symbols-outlined text-primary">data_usage</span>
          Flink SQL Jobs
          <span className="text-sm font-normal text-slate-500">({Object.keys(data.jobs).length})</span>
        </h2>
        {Object.keys(data.jobs).length === 0 ? (
          <div className="p-10 border border-dashed border-primary/10 rounded-xl flex flex-col items-center text-center text-slate-600">
            <span className="material-symbols-outlined text-3xl mb-2 opacity-30">cloud_off</span>
            <p className="text-xs font-medium uppercase tracking-widest">No active jobs</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {Object.entries(data.jobs).map(([id, sql]: [string, any]) => (
              <div key={id} className="bg-primary/5 border border-primary/10 rounded-xl p-4 flex flex-col gap-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-mono text-slate-300 truncate">{typeof sql === 'string' ? sql : id}</p>
                    <p className="text-[10px] text-slate-600 font-mono mt-0.5">ID: {id.substring(0, 16)}</p>
                  </div>
                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-primary/20 text-primary uppercase shrink-0">Running</span>
                </div>
                <button
                  onClick={() => killJob(id)}
                  disabled={killingJob === id}
                  className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border border-red-500/30 text-red-400 hover:bg-red-500/10 disabled:opacity-50 transition-colors text-xs font-bold uppercase tracking-wider w-full"
                >
                  {killingJob === id
                    ? <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                    : <span className="material-symbols-outlined text-sm">cancel</span>}
                  {killingJob === id ? 'Killing...' : 'Kill Job'}
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default Dashboard;
