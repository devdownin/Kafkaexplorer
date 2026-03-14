import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { 
  Waves, 
  Grid3X3, 
  Cpu, 
  BarChart3, 
  Search, 
  Filter,
  Eye,
  AlertTriangle,
  CheckCircle2,
  CircleDashed
} from 'lucide-react';
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
  const [hideEmpty] = useState(false);
  const [hideDlt, setHideDlt] = useState(true);

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
    <div className="flex-1 flex items-center justify-center">
      <div className="animate-spin rounded-full h-8 w-8 border-2 border-primary border-t-transparent" />
    </div>
  );

  if (error || !data) return (
    <div className="p-8 text-red-500 flex items-center gap-2">
      <AlertTriangle className="w-5 h-5" /> {error}
    </div>
  );

  const filteredTopics = data.topics.filter(topic => {
    const matchesSearch = topic.toLowerCase().includes(searchTerm.toLowerCase());
    const isEmpty = data.topicSizes[topic] === 0;
    const isDlt = topic.toLowerCase().endsWith('.dlt');
    
    if (hideEmpty && isEmpty) return false;
    if (hideDlt && isDlt) return false;
    return matchesSearch;
  });

  const kpis = [
    { label: 'Total Topics', value: data.topics.length, icon: Waves, color: 'text-primary' },
    { label: 'Message Count', value: data.totalMessages.toLocaleString(), icon: BarChart3, color: 'text-emerald-500' },
    { label: 'Flink Tables', value: data.tables.length, icon: Grid3X3, color: 'text-amber-500' },
    { label: 'Active Jobs', value: Object.keys(data.jobs).length, icon: Cpu, color: 'text-purple-500' },
  ];

  return (
    <div className="p-8 space-y-8 max-w-7xl mx-auto">
      {/* KPI Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
        {kpis.map((kpi) => (
          <div key={kpi.label} className="glass p-6 rounded-3xl group hover:scale-[1.02] transition-transform">
            <div className="flex justify-between items-start">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-widest text-slate-500 mb-1">{kpi.label}</p>
                <h3 className="text-2xl font-bold text-slate-100">{kpi.value}</h3>
              </div>
              <div className={`p-2 rounded-xl bg-black/20 ${kpi.color}`}>
                <kpi.icon className="w-5 h-5" />
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-12 gap-8">
        {/* Topics Table */}
        <div className="col-span-12 lg:col-span-8 space-y-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <h2 className="text-xl font-bold text-slate-100 flex items-center gap-2">
              <Waves className="text-primary w-5 h-5" /> Topics Explorer
            </h2>
            
            <div className="flex items-center gap-3">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500" />
                <input 
                  type="text" 
                  placeholder="Filter topics..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="bg-black/30 border border-white/5 rounded-xl pl-10 pr-4 py-2 text-xs outline-none focus:border-primary/30 w-full sm:w-48"
                />
              </div>
              <button 
                onClick={() => setHideDlt(!hideDlt)}
                className={`p-2 rounded-xl border transition-all ${hideDlt ? 'bg-primary/10 border-primary/20 text-primary' : 'bg-black/20 border-white/5 text-slate-500'}`}
                title="Toggle DLT Visibility"
              >
                <Filter className="w-4 h-4" />
              </button>
            </div>
          </div>

          <div className="glass rounded-3xl overflow-hidden border border-white/5">
            <div className="overflow-x-auto custom-scrollbar">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-white/[0.02] text-[10px] font-bold uppercase tracking-widest text-slate-500 border-b border-white/5">
                    <th className="px-6 py-4">Topic Name</th>
                    <th className="px-6 py-4 text-right">Messages</th>
                    <th className="px-6 py-4">Status</th>
                    <th className="px-6 py-4 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="text-sm divide-y divide-white/5">
                  {filteredTopics.map((topic) => (
                    <tr key={topic} className="group hover:bg-white/[0.02] transition-colors">
                      <td className="px-6 py-4 font-medium text-slate-200">{topic}</td>
                      <td className="px-6 py-4 text-right font-mono text-slate-400">
                        {data.topicSizes[topic]?.toLocaleString() || 0}
                      </td>
                      <td className="px-6 py-4">
                        {data.topicSizes[topic] === 0 ? (
                          <span className="flex items-center gap-1.5 text-[10px] font-bold text-slate-500 uppercase">
                            <CircleDashed className="w-3 h-3" /> Empty
                          </span>
                        ) : topic.toLowerCase().endsWith('.dlt') ? (
                          <span className="flex items-center gap-1.5 text-[10px] font-bold text-amber-500 uppercase">
                            <AlertTriangle className="w-3 h-3" /> DLT
                          </span>
                        ) : (
                          <span className="flex items-center gap-1.5 text-[10px] font-bold text-emerald-500 uppercase">
                            <CheckCircle2 className="w-3 h-3" /> Healthy
                          </span>
                        )}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <Link 
                          to={`/topic/${topic}`}
                          className="p-2 inline-block text-slate-500 hover:text-primary transition-colors"
                        >
                          <Eye className="w-4 h-4" />
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* Active Jobs Sidebar */}
        <div className="col-span-12 lg:col-span-4 space-y-6">
          <h2 className="text-xl font-bold text-slate-100 flex items-center gap-2">
            <Cpu className="text-primary w-5 h-5" /> Flink SQL Jobs
          </h2>
          
          <div className="space-y-4">
            {Object.entries(data.jobs).map(([id, job]: [string, any]) => (
              <div key={id} className="glass p-5 rounded-3xl space-y-3">
                <div className="flex justify-between items-start">
                  <div className="min-w-0">
                    <h4 className="text-sm font-bold truncate text-slate-200">{id.substring(0, 16)}...</h4>
                    <span className="text-[10px] font-mono text-slate-500 uppercase tracking-tighter">RUNNING</span>
                  </div>
                  <div className="w-2 h-2 rounded-full bg-primary animate-pulse shadow-[0_0_8px_rgba(0,209,255,0.5)]" />
                </div>
                <div className="bg-black/20 p-2 rounded-lg border border-white/5">
                  <code className="text-[10px] text-slate-400 line-clamp-2 italic">
                    {job.sql || 'Streaming Query'}
                  </code>
                </div>
              </div>
            ))}
            
            {Object.keys(data.jobs).length === 0 && (
              <div className="glass p-12 rounded-3xl border-dashed flex flex-col items-center text-center">
                <CircleDashed className="w-10 h-10 text-white/10 mb-3" />
                <p className="text-xs text-slate-500 font-medium">No active streaming jobs found.</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
