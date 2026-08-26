// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';

// La forme vit dans api/types.ts, où check-api-types.py la résout contre le record Java.
// Réexportée ici parce que c'est d'ici que les pages l'importaient.
import type { AnomalyReport } from '../../api/types';
export type { AnomalyReport };

interface AnomalyTableProps {
  anomalies: AnomalyReport[];
}

const severityConfig: Record<AnomalyReport['severity'], { bg: string; text: string; border: string }> = {
  CRITICAL: { bg: 'bg-error/15', text: 'text-error', border: 'border-error/30' },
  MAJOR: { bg: 'bg-warning/15', text: 'text-warning', border: 'border-warning/30' },
  MINOR: { bg: 'bg-outline/15', text: 'text-on-surface-variant', border: 'border-outline-variant/30' },
};

const typeIcons: Record<AnomalyReport['type'], string> = {
  SEQUENCE: 'sort',
  TEMPORAL: 'schedule',
  STRUCTURAL: 'schema',
  CARDINALITY: 'functions',
  BUSINESS: 'business_center',
};

const AnomalyTable: React.FC<AnomalyTableProps> = ({ anomalies }) => {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (!anomalies || anomalies.length === 0) {
    return (
      <div className="flex items-center justify-center h-24 text-on-surface-variant text-sm">
        <span className="material-symbols-outlined mr-2 text-success">check_circle</span>
        No anomalies detected
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {anomalies.map(anomaly => {
        const sev = severityConfig[anomaly.severity] ?? severityConfig.MINOR;
        const isExpanded = expandedId === anomaly.id;

        return (
          <div
            key={anomaly.id}
            className={`border rounded-xl overflow-hidden ${sev.border} transition-all`}
          >
            {/* Row header */}
            <div
              className={`flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-primary/5 ${sev.bg}`}
              onClick={() => setExpandedId(isExpanded ? null : anomaly.id)}
            >
              <span className={`text-xs font-bold px-2 py-0.5 rounded-full border ${sev.bg} ${sev.text} ${sev.border}`}>
                {anomaly.severity}
              </span>
              <div className="flex items-center gap-1.5 text-on-surface-variant text-xs">
                <span className="material-symbols-outlined text-sm">{typeIcons[anomaly.type] ?? 'info'}</span>
                <span>{anomaly.type}</span>
              </div>
              <span className="text-xs font-mono text-on-surface px-2 py-0.5 bg-primary/10 rounded">
                {anomaly.topic}
              </span>
              <span className="flex-1 text-sm text-on-surface truncate">{anomaly.description}</span>
              <span className="material-symbols-outlined text-on-surface-variant text-base transition-transform" style={{
                transform: isExpanded ? 'rotate(180deg)' : 'rotate(0deg)'
              }}>
                expand_more
              </span>
            </div>

            {/* Expanded details */}
            {isExpanded && (
              <div className="px-4 pb-4 pt-3 space-y-3 bg-surface-container-low/30">
                {anomaly.fields && anomaly.fields.length > 0 && (
                  <div>
                    <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider mb-1">
                      Affected Fields
                    </p>
                    <div className="flex flex-wrap gap-1.5">
                      {anomaly.fields.map((f, i) => (
                        <span key={i} className="text-xs font-mono px-2 py-0.5 bg-primary/10 rounded text-primary/80">
                          {f}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                {anomaly.probableCause && (
                  <div>
                    <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider mb-1">
                      Probable Cause
                    </p>
                    <p className="text-xs text-on-surface">{anomaly.probableCause}</p>
                  </div>
                )}

                {anomaly.sqlSuggestion && (
                  <div>
                    <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider mb-1">
                      Flink SQL suggestion
                    </p>
                    <pre className="text-xs font-mono text-success bg-surface-container-low/60 rounded-lg p-3 overflow-auto whitespace-pre-wrap">
                      {anomaly.sqlSuggestion}
                    </pre>
                  </div>
                )}

                <div className="flex items-center gap-1 text-[10px] text-outline">
                  <span>ID:</span>
                  <span className="font-mono">{anomaly.id}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default AnomalyTable;
