import React, { useState } from 'react';

export interface FieldInfo {
  path: string;
  sampleValues: string[];
  inferredType: string;
  semanticRole: string;
  confidence: number;
  reasoning: string;
}

export interface TopicProfile {
  name: string;
  format: string;
  fields: FieldInfo[];
  candidateCorrelationKeys: string[];
  candidateTimestamps: string[];
  candidateStatuses: string[];
}

export interface UnificationEntry {
  canonicalName: string;
  mappings: Record<string, string>;
  confidence: number;
  conflicts: string[];
}

export interface SchemaUnificationProposal {
  correlationId: UnificationEntry | null;
  timestamp: UnificationEntry | null;
  status: UnificationEntry | null;
  amount: UnificationEntry | null;
  warnings: string[];
}

export interface FieldProfileResult {
  topics: TopicProfile[];
  unificationProposal: SchemaUnificationProposal | null;
  warnings: string[];
}

interface SchemaValidationPanelProps {
  result: FieldProfileResult;
  onValidate: (corrections: Record<string, Record<string, string>>) => void;
  loading?: boolean;
}

type FieldType = 'correlationId' | 'timestamp' | 'status' | 'amount';

const FIELD_LABELS: Record<FieldType, string> = {
  correlationId: 'Correlation ID',
  timestamp: 'Timestamp',
  status: 'Status',
  amount: 'Amount',
};

const confidenceBadge = (conf: number): React.ReactNode => {
  if (conf >= 0.9) {
    return (
      <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-success/20 text-success">
        <span className="material-symbols-outlined text-xs">check_circle</span>
        {Math.round(conf * 100)}%
      </span>
    );
  }
  if (conf >= 0.7) {
    return (
      <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-warning/20 text-warning">
        <span className="material-symbols-outlined text-xs">warning</span>
        {Math.round(conf * 100)}%
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-error/20 text-error">
      <span className="material-symbols-outlined text-xs">help</span>
      {Math.round(conf * 100)}%
    </span>
  );
};

const SchemaValidationPanel: React.FC<SchemaValidationPanelProps> = ({
  result,
  onValidate,
  loading,
}) => {
  const proposal = result.unificationProposal;

  // Editable corrections: fieldType -> topic -> jsonpath
  const [corrections, setCorrections] = useState<Record<FieldType, Record<string, string>>>(() => {
    const init: Record<FieldType, Record<string, string>> = {
      correlationId: {},
      timestamp: {},
      status: {},
      amount: {},
    };
    if (proposal) {
      for (const ft of ['correlationId', 'timestamp', 'status', 'amount'] as FieldType[]) {
        const entry = proposal[ft];
        if (entry?.mappings) {
          init[ft] = { ...entry.mappings };
        }
      }
    }
    return init;
  });

  const updateCorrection = (field: FieldType, topic: string, value: string) => {
    setCorrections(prev => ({
      ...prev,
      [field]: { ...prev[field], [topic]: value },
    }));
  };

  const handleValidate = () => {
    // Only pass non-empty corrections
    const cleaned: Record<string, Record<string, string>> = {};
    for (const [ft, mapping] of Object.entries(corrections)) {
      const nonEmpty = Object.fromEntries(
        Object.entries(mapping).filter(([, v]) => v.trim().length > 0)
      );
      if (Object.keys(nonEmpty).length > 0) {
        cleaned[ft] = nonEmpty;
      }
    }
    onValidate(cleaned);
  };

  const renderMappingEditor = (ft: FieldType) => {
    const entry = proposal?.[ft];
    if (!entry) return null;

    const topics = Object.keys(corrections[ft]);
    if (topics.length === 0) return null;

    return (
      <div key={ft} className="border border-primary/20 rounded-xl overflow-hidden">
        <div className="flex items-center justify-between px-4 py-2.5 bg-primary/5 border-b border-primary/10">
          <span className="text-sm font-semibold text-on-surface">{FIELD_LABELS[ft]}</span>
          {entry && confidenceBadge(entry.confidence)}
        </div>
        <div className="divide-y divide-primary/10">
          {topics.map(topic => (
            <div key={topic} className="flex items-center gap-3 px-4 py-2.5">
              <span className="text-xs font-mono text-on-surface-variant w-40 truncate" title={topic}>
                {topic}
              </span>
              <span className="material-symbols-outlined text-outline text-base">arrow_forward</span>
              <input
                type="text"
                value={corrections[ft][topic] ?? ''}
                onChange={e => updateCorrection(ft, topic, e.target.value)}
                placeholder="$.fieldPath"
                className="flex-1 bg-background-dark border border-primary/20 rounded-lg px-2.5 py-1.5 text-xs font-mono text-on-surface focus:border-primary/50 outline-none"
              />
            </div>
          ))}
        </div>
        {entry.conflicts && entry.conflicts.length > 0 && (
          <div className="px-4 py-2 bg-warning/10 border-t border-warning/20">
            <p className="text-xs text-warning font-medium">Conflicts detected:</p>
            {entry.conflicts.map((c, i) => (
              <p key={i} className="text-xs text-warning/80 mt-0.5">{c}</p>
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-on-surface mb-1">Validate Schema Mapping</h2>
        <p className="text-sm text-on-surface-variant">
          Review and correct the field mappings detected by Claude. These mappings will be used
          to correlate messages across topics.
        </p>
      </div>

      {/* Warnings */}
      {((result.warnings?.length ?? 0) > 0 || (proposal?.warnings?.length ?? 0) > 0) && (
        <div className="bg-warning/10 border border-warning/30 rounded-xl p-4 space-y-1">
          <p className="text-xs font-bold text-warning uppercase tracking-wider">Warnings</p>
          {[...(result.warnings ?? []), ...(proposal?.warnings ?? [])].map((w, i) => (
            <p key={i} className="text-xs text-warning">{w}</p>
          ))}
        </div>
      )}

      {/* Topic profiles summary */}
      {result.topics && result.topics.length > 0 && (
        <div>
          <p className="text-xs font-bold text-on-surface0 uppercase tracking-wider mb-2">
            Profiled Topics ({result.topics.length})
          </p>
          <div className="grid gap-2">
            {result.topics.map(tp => (
              <div
                key={tp.name}
                className="flex items-center justify-between px-3 py-2 bg-primary/5 border border-primary/10 rounded-lg"
              >
                <span className="text-xs font-mono text-on-surface">{tp.name}</span>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-on-surface0">{tp.format}</span>
                  <span className="text-xs text-on-surface0">{tp.fields?.length ?? 0} fields</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Mapping editors */}
      {proposal ? (
        <div className="space-y-4">
          <p className="text-xs font-bold text-on-surface0 uppercase tracking-wider">
            Field Mappings — Edit JSONPath if needed
          </p>
          {(['correlationId', 'timestamp', 'status', 'amount'] as FieldType[]).map(renderMappingEditor)}
        </div>
      ) : (
        <div className="text-sm text-on-surface0 text-center py-4">
          No field mapping proposal available. You can proceed without mappings.
        </div>
      )}

      <div className="flex gap-3 pt-2">
        <button
          onClick={handleValidate}
          disabled={loading}
          className="flex-1 flex items-center justify-center gap-2 py-3 px-6 bg-primary text-on-primary rounded-xl font-semibold text-sm hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading ? (
            <>
              <span className="material-symbols-outlined text-base animate-spin">progress_activity</span>
              Processing...
            </>
          ) : (
            <>
              <span className="material-symbols-outlined text-base">check_circle</span>
              Validate and Launch Analysis
            </>
          )}
        </button>
      </div>
    </div>
  );
};

export default SchemaValidationPanel;
