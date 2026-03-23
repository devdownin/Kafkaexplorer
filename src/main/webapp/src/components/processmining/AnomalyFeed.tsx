import React from 'react';
import { AnomalyReport } from './AnomalyTable';

export type AnomalyStatus = 'NEW' | 'RECURRENT' | 'RESOLVED';

export interface LiveAnomaly extends AnomalyReport {
  status: AnomalyStatus;
  detectedAt: number;
}

interface AnomalyFeedProps {
  anomalies: LiveAnomaly[];
}

const statusConfig: Record<AnomalyStatus, { bg: string; text: string; border: string; label: string }> = {
  NEW: { bg: 'bg-cyan-500/15', text: 'text-cyan-400', border: 'border-cyan-500/30', label: 'NEW' },
  RECURRENT: { bg: 'bg-amber-500/15', text: 'text-amber-400', border: 'border-amber-500/30', label: 'RECURRENT' },
  RESOLVED: { bg: 'bg-slate-500/10', text: 'text-slate-500', border: 'border-slate-600/30', label: 'RESOLVED' },
};

const severityDot: Record<string, string> = {
  CRITICAL: 'bg-red-500',
  MAJOR: 'bg-amber-500',
  MINOR: 'bg-slate-500',
};

const AnomalyFeed: React.FC<AnomalyFeedProps> = ({ anomalies }) => {
  if (!anomalies || anomalies.length === 0) {
    return (
      <div className="flex items-center justify-center h-24 text-slate-500 text-sm">
        <span className="material-symbols-outlined mr-2 text-emerald-500">check_circle</span>
        No anomalies in current window
      </div>
    );
  }

  // Sort: NEW first, then RECURRENT, then RESOLVED
  const sorted = [...anomalies].sort((a, b) => {
    const order = { NEW: 0, RECURRENT: 1, RESOLVED: 2 };
    const diff = order[a.status] - order[b.status];
    if (diff !== 0) return diff;
    return b.detectedAt - a.detectedAt;
  });

  return (
    <div className="space-y-2 max-h-96 overflow-y-auto">
      {sorted.map((anomaly, idx) => {
        const sc = statusConfig[anomaly.status];
        const isResolved = anomaly.status === 'RESOLVED';

        return (
          <div
            key={`${anomaly.id}-${idx}`}
            className={`flex items-start gap-3 px-3 py-2.5 rounded-xl border transition-all ${sc.bg} ${sc.border} ${
              isResolved ? 'opacity-60' : ''
            }`}
          >
            {/* Severity dot */}
            <span className={`mt-1.5 flex-shrink-0 h-2 w-2 rounded-full ${
              severityDot[anomaly.severity] ?? 'bg-slate-500'
            }`} />

            {/* Content */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-0.5">
                <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${sc.bg} ${sc.text} border ${sc.border}`}>
                  {sc.label}
                </span>
                <span className="text-xs font-mono text-slate-400">{anomaly.topic}</span>
                <span className="text-xs text-slate-600">{anomaly.type}</span>
                <span className="ml-auto text-[10px] text-slate-600">
                  {new Date(anomaly.detectedAt).toLocaleTimeString()}
                </span>
              </div>
              <p className={`text-xs text-slate-300 ${isResolved ? 'line-through text-slate-500' : ''}`}>
                {anomaly.description}
              </p>
              {anomaly.probableCause && !isResolved && (
                <p className="text-[11px] text-slate-500 mt-0.5 truncate">
                  Cause: {anomaly.probableCause}
                </p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default AnomalyFeed;
