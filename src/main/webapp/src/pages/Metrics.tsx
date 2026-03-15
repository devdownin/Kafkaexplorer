import React, { useState, useEffect } from 'react';
import axios from 'axios';
import Editor from '@monaco-editor/react';
import { Plus, Trash2, Edit2, Play, AlertCircle, CheckCircle2, RefreshCw } from 'lucide-react';

interface MetricConfig {
  id: string;
  name: string;
  type: string;
  sql: string;
  description: string;
  warningThreshold: number | null;
  criticalThreshold: number | null;
  lastValue: number | null;
  lastUpdateTime: number | null;
  errorMessage: string | null;
}

const Metrics: React.FC = () => {
  const [metrics, setMetrics] = useState<MetricConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingMetric, setEditingMetric] = useState<Partial<MetricConfig>>({
    name: '',
    type: 'GAUGE',
    sql: 'SELECT COUNT(*) as metric_value FROM my_table',
    description: ''
  });

  const fetchMetrics = async () => {
    try {
      const response = await axios.get('/api/metrics');
      setMetrics(response.data);
      setLoading(false);
    } catch (err) {
      console.error('Failed to fetch metrics', err);
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();
    const interval = setInterval(fetchMetrics, 10000);
    return () => clearInterval(interval);
  }, []);

  const handleSave = async () => {
    try {
      await axios.post('/api/metrics', editingMetric);
      setIsModalOpen(false);
      fetchMetrics();
    } catch (err) {
      console.error('Failed to save metric', err);
    }
  };

  const handleDelete = async (id: string) => {
    if (confirm('Are you sure you want to delete this metric?')) {
      try {
        await axios.delete(`/api/metrics/${id}`);
        fetchMetrics();
      } catch (err) {
        console.error('Failed to delete metric', err);
      }
    }
  };

  const openEditModal = (metric?: MetricConfig) => {
    if (metric) {
      setEditingMetric(metric);
    } else {
      setEditingMetric({
        name: '',
        type: 'GAUGE',
        sql: 'SELECT COUNT(*) as metric_value FROM my_table',
        description: ''
      });
    }
    setIsModalOpen(true);
  };

  const metricTypes = ['GAUGE', 'COUNTER', 'HISTOGRAM'];

  const examples = [
    { name: 'Simple Count', sql: 'SELECT COUNT(*) as metric_value FROM my_table' },
    { name: 'Average Value', sql: 'SELECT AVG(price) as metric_value FROM orders' },
    { name: 'Error Rate', sql: "SELECT COUNT(*) * 100.0 / (SELECT COUNT(*) FROM logs) as metric_value FROM logs WHERE level = 'ERROR'" },
    { name: 'Histogram Window', sql: "SELECT COUNT(*) as metric_value FROM TABLE(TUMBLE(TABLE events, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) GROUP BY window_start, window_end" }
  ];

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Business Metrics</h1>
          <p className="text-slate-500 dark:text-slate-400 text-sm mt-1">
            Define Prometheus metrics using Flink SQL queries.
          </p>
        </div>
        <button
          onClick={() => openEditModal()}
          className="flex items-center gap-2 bg-primary hover:bg-primary/80 text-background-dark px-4 py-2 rounded-lg font-bold transition-colors"
        >
          <Plus size={18} /> Add Metric
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {metrics.map((metric) => (
          <div key={metric.id} className="bg-white dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-xl p-5 flex flex-col md:flex-row gap-6">
            <div className="flex-1 space-y-2">
              <div className="flex items-center gap-3">
                <h3 className="text-lg font-bold">{metric.name}</h3>
                <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-primary/20 text-primary uppercase">{metric.type}</span>
              </div>
              <p className="text-sm text-slate-500 dark:text-slate-400">{metric.description}</p>
              <div className="bg-slate-50 dark:bg-background-dark/50 p-3 rounded-lg font-mono text-xs text-slate-600 dark:text-primary/80 overflow-x-auto whitespace-pre">
                {metric.sql}
              </div>
            </div>

            <div className="w-full md:w-64 flex flex-col justify-between border-l border-slate-100 dark:border-primary/5 md:pl-6">
              <div>
                <p className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-bold tracking-wider mb-1">Last Value</p>
                <div className="flex items-baseline gap-2">
                  <span className="text-3xl font-bold">
                    {metric.lastValue !== null ? metric.lastValue.toLocaleString() : 'N/A'}
                  </span>
                  {metric.errorMessage ? (
                    <div className="group relative">
                      <AlertCircle className="text-red-500" size={16} />
                      <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block w-48 bg-red-500 text-white text-[10px] p-2 rounded shadow-lg z-10">
                        {metric.errorMessage}
                      </div>
                    </div>
                  ) : (
                    <CheckCircle2 className="text-emerald-500" size={16} />
                  )}
                </div>
                <p className="text-[10px] text-slate-400 mt-1 flex items-center gap-1">
                  <RefreshCw size={10} className={loading ? 'animate-spin' : ''} />
                  {metric.lastUpdateTime ? new Date(metric.lastUpdateTime).toLocaleTimeString() : 'Never'}
                </p>
              </div>

              <div className="flex gap-2 mt-4">
                <button
                  onClick={() => openEditModal(metric)}
                  className="p-2 text-slate-400 hover:text-primary hover:bg-primary/10 rounded-lg transition-colors"
                >
                  <Edit2 size={18} />
                </button>
                <button
                  onClick={() => handleDelete(metric.id)}
                  className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-500/10 rounded-lg transition-colors"
                >
                  <Trash2 size={18} />
                </button>
              </div>
            </div>
          </div>
        ))}

        {metrics.length === 0 && !loading && (
          <div className="p-12 border-2 border-dashed border-slate-200 dark:border-primary/10 rounded-xl flex flex-col items-center text-center text-slate-500">
            <Plus size={48} className="mb-4 opacity-20" />
            <p className="font-bold uppercase tracking-widest text-sm">No metrics defined</p>
            <p className="text-xs mt-2">Add your first business metric to start monitoring.</p>
          </div>
        )}
      </div>

      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-background-dark/80 backdrop-blur-sm">
          <div className="bg-white dark:bg-background-dark border border-slate-200 dark:border-primary/20 rounded-2xl w-full max-w-3xl max-h-[90vh] overflow-hidden flex flex-col">
            <div className="p-6 border-b border-slate-200 dark:border-primary/10 flex items-center justify-between">
              <h2 className="text-xl font-bold">{editingMetric.id ? 'Edit Metric' : 'Add New Metric'}</h2>
              <button onClick={() => setIsModalOpen(false)} className="text-slate-400 hover:text-white">
                <span className="material-symbols-outlined">close</span>
              </button>
            </div>

            <div className="p-6 overflow-y-auto space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-1">
                  <label className="text-[10px] uppercase font-bold text-slate-500">Metric Name</label>
                  <input
                    type="text"
                    value={editingMetric.name}
                    onChange={(e) => setEditingMetric({ ...editingMetric, name: e.target.value })}
                    className="w-full bg-slate-50 dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary outline-none"
                    placeholder="e.g. orders_total"
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-[10px] uppercase font-bold text-slate-500">Type</label>
                  <select
                    value={editingMetric.type}
                    onChange={(e) => setEditingMetric({ ...editingMetric, type: e.target.value })}
                    className="w-full bg-slate-50 dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary outline-none"
                  >
                    {metricTypes.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
              </div>

              <div className="space-y-1">
                <label className="text-[10px] uppercase font-bold text-slate-500">Description</label>
                <input
                  type="text"
                  value={editingMetric.description}
                  onChange={(e) => setEditingMetric({ ...editingMetric, description: e.target.value })}
                  className="w-full bg-slate-50 dark:bg-primary/5 border border-slate-200 dark:border-primary/10 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary outline-none"
                  placeholder="What does this metric represent?"
                />
              </div>

              <div className="space-y-1">
                <div className="flex items-center justify-between">
                  <label className="text-[10px] uppercase font-bold text-slate-500">Flink SQL Query</label>
                  <div className="flex gap-2">
                    {examples.map(ex => (
                      <button
                        key={ex.name}
                        onClick={() => setEditingMetric({ ...editingMetric, sql: ex.sql })}
                        className="text-[10px] text-primary hover:underline"
                      >
                        {ex.name}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="h-48 border border-slate-200 dark:border-primary/10 rounded-lg overflow-hidden">
                  <Editor
                    height="100%"
                    defaultLanguage="sql"
                    theme="vs-dark"
                    value={editingMetric.sql}
                    onChange={(val) => setEditingMetric({ ...editingMetric, sql: val || '' })}
                    options={{
                      minimap: { enabled: false },
                      fontSize: 12,
                      scrollBeyondLastLine: false,
                    }}
                  />
                </div>
              </div>
            </div>

            <div className="p-6 border-t border-slate-200 dark:border-primary/10 flex justify-end gap-3">
              <button
                onClick={() => setIsModalOpen(false)}
                className="px-4 py-2 text-sm font-bold text-slate-500 hover:text-slate-700 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleSave}
                className="bg-primary hover:bg-primary/80 text-background-dark px-6 py-2 rounded-lg font-bold transition-colors flex items-center gap-2"
              >
                <Play size={16} /> Save & Activate
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Metrics;
