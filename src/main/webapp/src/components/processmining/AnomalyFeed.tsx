// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

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
  NEW: { bg: 'bg-primary/15', text: 'text-primary', border: 'border-primary/30', label: 'NEW' },
  RECURRENT: { bg: 'bg-warning/15', text: 'text-warning', border: 'border-warning/30', label: 'RECURRENT' },
  RESOLVED: { bg: 'bg-outline/10', text: 'text-on-surface-variant', border: 'border-outline-variant/30', label: 'RESOLVED' },
};

const severityDot: Record<string, string> = {
  CRITICAL: 'bg-error',
  MAJOR: 'bg-warning',
  MINOR: 'bg-outline',
};

const AnomalyFeed: React.FC<AnomalyFeedProps> = ({ anomalies }) => {
  if (!anomalies || anomalies.length === 0) {
    return (
      <div className="flex items-center justify-center h-24 text-on-surface-variant text-sm">
        <span className="material-symbols-outlined mr-2 text-success">check_circle</span>
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
              severityDot[anomaly.severity] ?? 'bg-outline'
            }`} />

            {/* Content */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-0.5">
                <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${sc.bg} ${sc.text} border ${sc.border}`}>
                  {sc.label}
                </span>
                <span className="text-xs font-mono text-on-surface-variant">{anomaly.topic}</span>
                <span className="text-xs text-outline">{anomaly.type}</span>
                <span className="ml-auto text-[10px] text-outline">
                  {new Date(anomaly.detectedAt).toLocaleTimeString()}
                </span>
              </div>
              <p className={`text-xs text-on-surface ${isResolved ? 'line-through text-on-surface-variant' : ''}`}>
                {anomaly.description}
              </p>
              {anomaly.probableCause && !isResolved && (
                <p className="text-[11px] text-on-surface-variant mt-0.5 truncate">
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
