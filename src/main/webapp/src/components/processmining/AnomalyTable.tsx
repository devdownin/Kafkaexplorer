import React, { useState } from 'react';

export interface AnomalyReport {
  id: string;
  topic: string;
  type: 'SEQUENCE' | 'TEMPORAL' | 'STRUCTURAL' | 'CARDINALITY' | 'BUSINESS';
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR';
  fields: string[];
  description: string;
  probableCause: string;
  ksqlSuggestion: string;
}

interface AnomalyTableProps {
  anomalies: AnomalyReport[];
}

const severityConfig: Record<AnomalyReport['severity'], { bg: string; text: string; border: string }> = {
  CRITICAL: { bg: 'bg-red-500/15', text: 'text-red-400', border: 'border-red-500/30' },
  MAJOR: { bg: 'bg-amber-500/15', text: 'text-amber-400', border: 'border-amber-500/30' },
  MINOR: { bg: 'bg-slate-500/15', text: 'text-slate-400', border: 'border-slate-500/30' },
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
      <div className="flex items-center justify-center h-24 text-slate-500 text-sm">
        <span className="material-symbols-outlined mr-2 text-emerald-500">check_circle</span>
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
              <div className="flex items-center gap-1.5 text-slate-400 text-xs">
                <span className="material-symbols-outlined text-sm">{typeIcons[anomaly.type] ?? 'info'}</span>
                <span>{anomaly.type}</span>
              </div>
              <span className="text-xs font-mono text-slate-300 px-2 py-0.5 bg-primary/10 rounded">
                {anomaly.topic}
              </span>
              <span className="flex-1 text-sm text-slate-200 truncate">{anomaly.description}</span>
              <span className="material-symbols-outlined text-slate-500 text-base transition-transform" style={{
                transform: isExpanded ? 'rotate(180deg)' : 'rotate(0deg)'
              }}>
                expand_more
              </span>
            </div>

            {/* Expanded details */}
            {isExpanded && (
              <div className="px-4 pb-4 pt-3 space-y-3 bg-slate-900/30">
                {anomaly.fields && anomaly.fields.length > 0 && (
                  <div>
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1">
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

                <div>
                  <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1">
                    Probable Cause
                  </p>
                  <p className="text-xs text-slate-300">{anomaly.probableCause}</p>
                </div>

                {anomaly.ksqlSuggestion && (
                  <div>
                    <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1">
                      KSQL / Flink SQL Suggestion
                    </p>
                    <pre className="text-xs font-mono text-emerald-400 bg-slate-900/60 rounded-lg p-3 overflow-auto whitespace-pre-wrap">
                      {anomaly.ksqlSuggestion}
                    </pre>
                  </div>
                )}

                <div className="flex items-center gap-1 text-[10px] text-slate-600">
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
