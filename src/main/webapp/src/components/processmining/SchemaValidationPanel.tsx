// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import { cleanCorrections, mappingProblems, pathProblem } from './schemaMapping';

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
  /** Label of the model that produced the proposal — the provider is a choice, not always Claude. */
  providerLabel?: string;
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
  providerLabel,
}) => {
  const proposal = result.unificationProposal;
  /*
   * Every topic that was profiled, not just the ones the model proposed a path for. The editor used
   * to render a role's rows from the proposal alone and return null when there were none — so a
   * correlation id the model missed on one topic (or on all of them) was invisible, and the panel
   * whose whole purpose is correcting the mapping could only ever adjust what the model had already
   * found. Supplying a path it missed is the correction most worth making.
   */
  const profiledTopics = React.useMemo(
    () => (result.topics ?? []).map(t => t.name).filter(Boolean),
    [result.topics]);

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

  const problems = mappingProblems(corrections);
  const problemCount = Object.values(problems)
    .reduce((n, byTopic) => n + Object.keys(byTopic).length, 0);

  const handleValidate = (e: React.FormEvent) => {
    e.preventDefault();
    // A path that cannot match anything is refused here rather than sailing through to an analysis
    // whose only symptom is an empty correlation — the failure furthest from its cause.
    if (problemCount > 0) {
      const [role, byTopic] = Object.entries(problems)[0];
      document.getElementById(`mapping-${role}-${Object.keys(byTopic)[0]}`)?.focus();
      return;
    }
    onValidate(cleanCorrections(corrections));
  };

  const renderMappingEditor = (ft: FieldType) => {
    const entry = proposal?.[ft];
    // Union of what the model proposed and what was profiled, so a topic it said nothing about
    // still gets a row to type into. Proposed topics first — they are the ones being reviewed.
    const proposed = Object.keys(corrections[ft]);
    const topics = [...proposed, ...profiledTopics.filter(t => !proposed.includes(t))];
    if (topics.length === 0) return null;

    return (
      <div key={ft} className="border border-outline-variant rounded-xl overflow-hidden">
        <div className="flex items-center justify-between px-4 py-2.5 bg-primary/5 border-b border-outline-variant/60">
          <span className="text-sm font-semibold text-on-surface">{FIELD_LABELS[ft]}</span>
          {entry
            ? confidenceBadge(entry.confidence)
            : (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-outline/10 text-on-surface-variant">
                NOT DETECTED
              </span>
            )}
        </div>
        <div className="divide-y divide-primary/10">
          {topics.map(topic => {
            const id = `mapping-${ft}-${topic}`;
            const value = corrections[ft][topic] ?? '';
            const problem = value.trim() ? pathProblem(value) : null;
            return (
              <div key={topic} className="px-4 py-2.5">
                <div className="flex items-center gap-3">
                  {/* A real label, not a placeholder: the topic name is the only thing that says
                      what this box is for, and a placeholder disappears the moment you type. */}
                  <label
                    htmlFor={id}
                    className="text-xs font-mono text-on-surface-variant w-40 truncate shrink-0"
                    title={topic}
                  >
                    {topic}
                  </label>
                  <span aria-hidden="true" className="material-symbols-outlined text-outline text-base">arrow_forward</span>
                  <input
                    id={id}
                    type="text"
                    value={value}
                    onChange={e => updateCorrection(ft, topic, e.target.value)}
                    placeholder="$.fieldPath"
                    aria-invalid={problem ? true : undefined}
                    aria-describedby={problem ? `${id}-error` : undefined}
                    className={`flex-1 min-w-0 bg-surface-container-low border rounded-md px-2.5 py-1.5 text-xs font-mono text-on-surface outline-none ${
                      problem ? 'border-error focus:border-error' : 'border-outline-variant focus:border-primary/50'
                    }`}
                  />
                </div>
                {problem && (
                  <p id={`${id}-error`} className="text-[11px] text-error mt-1 ml-[calc(10rem+1.75rem)]">
                    {problem}
                  </p>
                )}
              </div>
            );
          })}
        </div>
        {entry?.conflicts && entry.conflicts.length > 0 && (
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
    <form className="space-y-6" onSubmit={handleValidate}>
      <div>
        <h2 className="text-lg font-semibold text-on-surface mb-1">Validate Schema Mapping</h2>
        {/* Named "Claude" whatever the runtime, on a page whose banner two blocks up names the
            provider actually configured — which by default is not Anthropic at all. The proposal
            comes from whichever model is configured. */}
        <p className="text-sm text-on-surface-variant">
          Review and correct the field mappings proposed by {providerLabel ?? 'the configured model'}.
          These mappings are what correlates messages across topics — a path left blank means that
          topic contributes nothing to the correlation.
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
          <p className="text-xs font-bold text-on-surface-variant uppercase tracking-wider mb-2">
            Profiled Topics ({result.topics.length})
          </p>
          <div className="grid gap-2">
            {result.topics.map(tp => (
              <div
                key={tp.name}
                className="flex items-center justify-between px-3 py-2 bg-primary/5 border border-outline-variant/60 rounded-lg"
              >
                <span className="text-xs font-mono text-on-surface">{tp.name}</span>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-on-surface-variant">{tp.format}</span>
                  <span className="text-xs text-on-surface-variant">{tp.fields?.length ?? 0} fields</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Mapping editors. Driven by the topics that were profiled rather than by the proposal:
          a model that proposed nothing is the case where supplying the mapping by hand matters
          most, and it used to be the one case where the form offered nowhere to type it. */}
      {profiledTopics.length > 0 ? (
        <div className="space-y-4">
          <p className="text-xs font-bold text-on-surface-variant uppercase tracking-wider">
            Field Mappings — Edit JSONPath if needed
          </p>
          {!proposal && (
            <p className="text-xs text-on-surface-variant">
              The model proposed no mapping. Any path you fill in is used as-is; leaving them all
              blank runs the analysis without correlation.
            </p>
          )}
          {(['correlationId', 'timestamp', 'status', 'amount'] as FieldType[]).map(renderMappingEditor)}
        </div>
      ) : (
        <div className="text-sm text-on-surface-variant text-center py-4">
          No topic was profiled, so there is nothing to map. You can proceed without mappings.
        </div>
      )}

      <div className="space-y-2 pt-2">
        {problemCount > 0 && (
          <p className="text-xs text-error" role="status">
            {problemCount} field path{problemCount > 1 ? 's cannot' : ' cannot'} match anything —
            correct {problemCount > 1 ? 'them' : 'it'} before continuing.
          </p>
        )}
        <button
          type="submit"
          disabled={loading}
          className="w-full flex items-center justify-center gap-2 py-3 px-6 bg-primary text-on-primary rounded-xl font-semibold text-sm hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading ? (
            <>
              <span aria-hidden="true" className="material-symbols-outlined text-base animate-spin">progress_activity</span>
              Processing...
            </>
          ) : (
            <>
              <span aria-hidden="true" className="material-symbols-outlined text-base">check_circle</span>
              Validate and Launch Analysis
            </>
          )}
        </button>
      </div>
    </form>
  );
};

export default SchemaValidationPanel;
