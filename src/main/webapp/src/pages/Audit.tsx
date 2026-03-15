import React, { useState, useEffect } from 'react';
import axios from 'axios';

interface AuditReport {
  id: string;
  timestamp: string;
  status: string;
  results: Record<string, string>;
  details: string[];
}

const Audit: React.FC = () => {
  const [report, setReport] = useState<AuditReport | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchLatest();
  }, []);

  const fetchLatest = async () => {
    try {
      const response = await axios.get('/api/audit/latest');
      setReport(response.data);
    } catch (error) {
      console.error('Failed to fetch audit', error);
    }
  };

  const startAudit = async () => {
    setLoading(true);
    try {
      await axios.post('/api/audit/start');
      // Poll for status or just wait
      setTimeout(fetchLatest, 3000);
    } catch (error) {
      console.error('Failed to start audit', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex-1 p-6 space-y-6 overflow-y-auto h-full bg-background-dark">
      <div className="flex justify-between items-center">
        <h2 className="text-2xl font-bold text-slate-100">Cluster Health Audit</h2>
        <button
          onClick={startAudit}
          disabled={loading}
          className="px-6 py-2 bg-primary text-background-dark font-bold rounded-lg hover:brightness-110 disabled:opacity-50"
        >
          {loading ? 'AUDITING...' : 'RUN FULL SCAN'}
        </button>
      </div>

      {report ? (
        <div className="grid grid-cols-1 gap-6">
          <div className="bg-primary/5 border border-primary/20 rounded-xl p-6">
            <div className="flex justify-between items-start mb-6">
              <div>
                <p className="text-xs text-slate-400 uppercase tracking-widest font-bold">Latest Report</p>
                <h3 className="text-lg font-bold text-primary mt-1">{report.id}</h3>
                <p className="text-xs text-slate-500 mt-1">{new Date(report.timestamp).toLocaleString()}</p>
              </div>
              <span className="px-3 py-1 rounded-full bg-emerald-500/20 text-emerald-500 text-xs font-bold uppercase">
                {report.status}
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
               {Object.entries(report.results).map(([topic, status]) => (
                 <div key={topic} className="bg-neutral-dark p-4 rounded-lg border border-border-dark flex justify-between items-center">
                   <span className="text-sm font-medium text-slate-300">{topic}</span>
                   <span className={`text-[10px] font-bold uppercase ${status === 'HEALTHY' ? 'text-emerald-500' : 'text-amber-500'}`}>
                     {status}
                   </span>
                 </div>
               ))}
            </div>

            <div className="space-y-2">
              <h4 className="text-xs font-bold text-slate-400 uppercase tracking-widest">Findings</h4>
              <div className="bg-neutral-dark rounded-lg p-4 font-mono text-xs text-slate-400 space-y-1">
                {report.details.map((detail, i) => (
                  <div key={i} className="flex gap-2">
                    <span className="text-primary opacity-50">{i+1}.</span>
                    <span>{detail}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className="text-slate-500 italic">No audit reports found. Start a scan to analyze cluster health.</div>
      )}
    </div>
  );
};

export default Audit;
