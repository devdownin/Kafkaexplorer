import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';

interface DashboardData {
  topics: string[];
  topicSizes: Record<string, number>;
  totalMessages: number;
  tables: string[];
  jobs: Record<string, any>;
  health: boolean;
}

const Dashboard: React.FC = () => {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    const fetchData = async () => {
      try {
        const response = await axios.get('/api/dashboard');
        setData(response.data);
        setLoading(false);
      } catch (err) {
        setError('Failed to fetch dashboard data');
        setLoading(false);
      }
    };
    fetchData();
  }, []);

  if (loading) return (
    <div className="flex-1 flex items-center justify-center p-12">
      <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
    </div>
  );

  if (error || !data) return (
    <div className="p-8 text-red-500 flex items-center gap-2">
      <span className="material-symbols-outlined">warning</span> {error}
    </div>
  );

  const filteredTopics = data.topics.filter(topic =>
    topic.toLowerCase().includes(searchTerm.toLowerCase())
  ).slice(0, 10); // Limiting for the redesign preview, but usually paginated

  const kpis = [
    { label: 'Total Topics', value: data.topics.length.toString(), icon: 'format_list_bulleted', color: 'text-primary', trend: '+3 this week' },
    { label: 'Message Count', value: formatCount(data.totalMessages), icon: 'bolt', color: 'text-primary', trend: 'Active Ingest' },
    { label: 'Flink Tables', value: data.tables.length.toString(), icon: 'database', color: 'text-slate-400', trend: 'Virtual Views' },
    { label: 'Active Jobs', value: Object.keys(data.jobs).length.toString(), icon: 'sync', color: 'text-primary', trend: '100% Health' },
  ];

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

      {/* Main Layout Grid */}
      <div className="grid grid-cols-12 gap-6">
        {/* Topics Explorer Section */}
        <div className="col-span-12 lg:col-span-8 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold flex items-center gap-2">
              <span className="material-symbols-outlined text-primary">format_list_bulleted</span>
              Topics Explorer
            </h2>
            <div className="flex items-center gap-2">
              <div className="bg-white dark:bg-background-dark border border-slate-200 dark:border-primary/20 rounded-lg px-3 py-1 flex items-center">
                <span className="material-symbols-outlined text-sm text-slate-400 mr-2">filter_list</span>
                <input 
                  className="bg-transparent border-none focus:ring-0 text-xs w-32 p-0"
                  placeholder="Prefix search..."
                  type="text"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                />
              </div>
              <button className="bg-primary hover:bg-primary/80 text-background-dark text-xs font-bold px-3 py-1.5 rounded-lg transition-colors">New Topic</button>
            </div>
          </div>
          <div className="bg-white dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-xl overflow-hidden">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-50 dark:bg-primary/10 text-xs font-bold text-slate-500 dark:text-primary/70 uppercase tracking-wider border-b border-slate-200 dark:border-primary/10">
                  <th className="px-4 py-3">Topic Name</th>
                  <th className="px-4 py-3">Size/Messages</th>
                  <th className="px-4 py-3">State</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="text-sm divide-y divide-slate-100 dark:divide-primary/5">
                {filteredTopics.map((topic) => (
                  <tr key={topic} className="hover:bg-slate-50 dark:hover:bg-primary/5 transition-colors">
                    <td className="px-4 py-3 font-medium">{topic}</td>
                    <td className="px-4 py-3 text-slate-500 dark:text-slate-400">
                      {data.topicSizes[topic]?.toLocaleString() || 0}
                    </td>
                    <td className="px-4 py-3">
                      {data.topicSizes[topic] === 0 ? (
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-500/20 text-slate-500 dark:text-slate-400 uppercase">Empty</span>
                      ) : topic.toLowerCase().endsWith('.dlt') ? (
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500/20 text-amber-500 uppercase">DLT</span>
                      ) : (
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/20 text-emerald-500 uppercase">Healthy</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link to={`/topic/${topic}`} className="text-slate-400 hover:text-primary">
                        <span className="material-symbols-outlined text-lg">visibility</span>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="px-4 py-3 bg-slate-50 dark:bg-primary/5 border-t border-slate-200 dark:border-primary/10 flex items-center justify-between">
              <p className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-bold tracking-wider">
                Showing {filteredTopics.length} of {data.topics.length} topics
              </p>
              <div className="flex gap-2">
                <button className="p-1 rounded bg-white dark:bg-background-dark border border-slate-200 dark:border-primary/20 hover:text-primary">
                  <span className="material-symbols-outlined text-sm">chevron_left</span>
                </button>
                <button className="p-1 rounded bg-white dark:bg-background-dark border border-slate-200 dark:border-primary/20 hover:text-primary">
                  <span className="material-symbols-outlined text-sm">chevron_right</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Active Streaming Jobs Section */}
        <div className="col-span-12 lg:col-span-4 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold flex items-center gap-2">
              <span className="material-symbols-outlined text-primary">data_usage</span>
              Flink SQL Jobs
            </h2>
          </div>
          <div className="space-y-3">
            {Object.entries(data.jobs).map(([id, _job]: [string, any]) => (
              <div key={id} className="bg-white dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-xl p-4 flex flex-col gap-3">
                <div className="flex items-start justify-between">
                  <div className="min-w-0">
                    <h4 className="text-sm font-bold truncate">{id.substring(0, 20)}...</h4>
                    <p className="text-[10px] text-slate-500 dark:text-slate-400 font-mono mt-0.5">ID: {id.substring(0, 12)}</p>
                  </div>
                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-primary/20 text-primary uppercase">Running</span>
                </div>
                <div className="flex items-center justify-between mt-1">
                  <div className="flex gap-4">
                    <div>
                      <p className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-bold">Parallelism</p>
                      <p className="text-xs font-medium">1</p>
                    </div>
                  </div>
                  <button className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-red-500/30 text-red-500 hover:bg-red-500/10 transition-colors text-xs font-bold uppercase tracking-wider">
                    <span className="material-symbols-outlined text-sm">cancel</span> Kill
                  </button>
                </div>
              </div>
            ))}
            {Object.keys(data.jobs).length === 0 && (
              <div className="p-12 border-2 border-dashed border-slate-200 dark:border-primary/10 rounded-xl flex flex-col items-center text-center text-slate-500">
                <span className="material-symbols-outlined text-3xl mb-2 opacity-20">cloud_off</span>
                <p className="text-xs font-medium uppercase tracking-widest">No active jobs</p>
              </div>
            )}
          </div>
          <button className="w-full py-2.5 bg-primary/10 border border-primary/20 rounded-xl text-primary text-xs font-bold uppercase tracking-widest hover:bg-primary/20 transition-colors mt-4">
            View All Jobs
          </button>
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
