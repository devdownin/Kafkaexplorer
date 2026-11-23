// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useState } from 'react';
import type { StrataInspection, StrataLead } from './strataInvestigation';

const steps = ['Offset evidence', 'Declared dependencies', 'Consumer positions', 'Manual tracing'] as const;

export default function StrataInvestigationPanel({ inspection, leads, onChooseTopic, onFocus,
  onEvidence, onSnapshot }: {
  inspection: StrataInspection | null;
  leads: readonly StrataLead[];
  onChooseTopic: (topic: string) => void;
  onFocus: () => void;
  onEvidence: () => void;
  onSnapshot: () => void;
}) {
  const [step, setStep] = useState(0);
  return <section aria-label="Strata guided investigation"
    className="rounded-xl border border-outline-variant bg-surface p-3 space-y-3 text-on-surface">
    <header>
      <h3 className="text-sm font-semibold">Guided investigation</h3>
      <p className="text-xs text-on-surface-variant">Four evidence checks, without additional automatic Kafka reads.</p>
    </header>
    {!inspection ? <p className="text-xs text-on-surface-variant">Select a topic to start an investigation.</p> : <>
      <p className="text-xs font-medium break-all">{inspection.topic}</p>
      <div role="group" aria-label="Investigation stages" className="grid grid-cols-4 gap-1">
        {steps.map((name, index) => <button type="button" key={name}
          aria-label={'Stage ' + (index + 1) + ': ' + name} aria-current={step === index ? 'step' : undefined}
          onClick={() => setStep(index)}
          className={'rounded-lg border px-1 py-2 text-[11px] focus-visible:outline-2 focus-visible:outline-primary ' +
            (step === index ? 'border-primary bg-primary-container text-on-primary-container' :
              'border-outline-variant text-on-surface-variant')}
          title={name}>{index + 1}</button>)}
      </div>
      <div className="rounded-lg bg-surface-container-low p-3 space-y-2" aria-live="polite">
        <h4 className="text-xs font-semibold">{steps[step]}</h4>
        {step === 0 && <>
          <p className="text-xs">Coverage: <strong>{inspection.evidence.status}</strong>
            {' · ' + inspection.evidence.measuredBuckets + '/' + inspection.evidence.totalBuckets + ' complete buckets'}</p>
          <p className="text-xs text-on-surface-variant">
            Selected production: {inspection.evidence.selected === null ? 'unknown' :
              inspection.evidence.selected.toLocaleString() + ' produced offsets'}.
            {inspection.interval ? ' Interval ' + new Date(inspection.interval.startMs).toLocaleTimeString() +
              '–' + new Date(inspection.interval.endMs).toLocaleTimeString() + '.' : ' Time interval unavailable.'}
          </p>
          {inspection.anomaly && <p className="text-xs text-amber-700 dark:text-amber-300">
            Candidate {inspection.anomaly.kind} versus four measured buckets; not an outage diagnosis.</p>}
          <button type="button" onClick={onFocus}
            className="rounded-lg border border-outline-variant px-2 py-1.5 text-xs">Focus this topic</button>
        </>}
        {step === 1 && <>
          <p className="text-xs">{inspection.incoming.length} declared incoming and
            {' ' + inspection.outgoing.length} declared outgoing Flink paths in the current view.</p>
          <ul className="space-y-1 text-xs text-on-surface-variant">
            {[...inspection.incoming, ...inspection.outgoing].slice(0, 5).map((edge, index) =>
              <li key={edge.from + '|' + edge.to + '|' + index} className="break-all">
                {edge.from} → {edge.to} <span className="opacity-70">({edge.via})</span>
              </li>)}
          </ul>
          {inspection.incoming.length + inspection.outgoing.length === 0 &&
            <p className="text-xs text-on-surface-variant">No declared path in scope; this does not establish absence of a dependency.</p>}
        </>}
        {step === 2 && (inspection.consumer ? <>
          <p className="text-xs">Selected consumer group: <strong className="break-all">{inspection.consumer.groupId}</strong></p>
          <p className="text-xs">Health: {inspection.consumer.health} · reported lag:
            {' ' + inspection.consumer.totalLag.toLocaleString()} offsets</p>
          <p className="text-xs text-on-surface-variant">Only the selected group is represented. Group scope and unknown commits
            must not be generalized to the whole topic.</p>
        </> : <p className="text-xs text-on-surface-variant">
          No consumer group selected or available. Open the Topic inspector to select one; lack of data is not zero lag.
        </p>)}
        {step === 3 && <>
          <p className="text-xs text-on-surface-variant">
            Key tracing requires explicit operator input in Evidence details. Chronological matches
            are not proof that one record caused another. Kafka record keys are excluded from exported snapshots.
          </p>
          <button type="button" onClick={onEvidence}
            className="rounded-lg border border-outline-variant px-2 py-1.5 text-xs">Go to trace controls</button>
        </>}
      </div>
      <div className="flex items-center justify-between gap-2">
        <button type="button" disabled={step === 0} onClick={() => setStep(s => Math.max(0, s - 1))}
          className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs disabled:opacity-40">Previous</button>
        <span className="text-[11px] text-on-surface-variant" aria-live="polite">{step + 1} of {steps.length}</span>
        <button type="button" disabled={step === steps.length - 1}
          onClick={() => setStep(s => Math.min(steps.length - 1, s + 1))}
          className="rounded-lg border border-outline-variant px-3 py-1.5 text-xs disabled:opacity-40">Next</button>
      </div>
    </>}
    <div className="border-t border-outline-variant pt-3 space-y-2">
      <h4 className="text-xs font-semibold">Signals to inspect</h4>
      <p className="text-[11px] text-on-surface-variant">Only measured offset deviations and selected-group lag.
        These signals do not establish congestion or causality.</p>
      {leads.length === 0 ? <p className="text-xs text-on-surface-variant">
        No qualified lead in this time bucket; missing measurements cannot be interpreted as healthy.</p> :
        <ul className="space-y-1 max-h-52 overflow-auto" aria-label="Evidence leads">
          {leads.slice(0, 12).map((lead, index) => <li key={lead.topic + '-' + lead.kind + '-' + index}>
            <button type="button" onClick={() => onChooseTopic(lead.topic)}
              className="w-full rounded-lg border border-outline-variant p-2 text-left hover:bg-surface-container-low focus-visible:outline-2 focus-visible:outline-primary">
              <span className="block text-xs font-medium break-all">{lead.topic}</span>
              <span className={'block text-xs ' + (lead.level === 'stalled' ?
                'text-error' : 'text-amber-700 dark:text-amber-300')}>{lead.label}</span>
              <span className="block text-[11px] text-on-surface-variant">{lead.source} · {lead.explanation}</span>
            </button>
          </li>)}
        </ul>}
    </div>
    <div className="border-t border-outline-variant pt-3 space-y-1">
      <button type="button" disabled={!inspection} onClick={onSnapshot}
        className="w-full rounded-lg bg-primary-container px-3 py-2 text-xs font-semibold text-on-primary-container disabled:opacity-40">
        Save investigation snapshot
      </button>
      <p className="text-[11px] text-on-surface-variant">
        Local JSON download of the selected topic, time slice, coverage, declared lineage and selected-group state.
        No Kafka record key, message payload, trace matches, credentials or background storage.
      </p>
    </div>
  </section>;
}
