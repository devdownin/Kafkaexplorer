// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react';
import type { ProcessModel } from '../../api/types';
import {
  describeScope,
  describeUnavailable,
  formatDuration,
  minorityEnds,
  share,
  skewedEdges,
  slowestEdge,
} from '../../pages/processModel';

/**
 * Le processus mesuré, à l'écran.
 *
 * Il était calculé et seul le modèle le voyait : l'opérateur lisait un récit à propos d'un graphe de
 * successions directes qu'il ne pouvait pas consulter, et n'avait d'autre choix que de le croire.
 * C'est l'inverse de ce que fait le reste de l'application — le tableau de preuves de Stream Flow,
 * la note de couverture, l'évidence attachée à chaque KPI proposé : ce qui est mesuré s'affiche,
 * pour qu'un récit puisse être vérifié.
 *
 * C'est aussi tout ce qu'il y a à montrer quand aucun LLM n'est configuré. Les transitions, les
 * variantes et les latences sont du comptage ; seule leur lecture demandait un modèle.
 *
 * Aucune règle d'appréciation ici : les verdicts viennent du serveur, les phrases de
 * `pages/processModel.ts`, et ce fichier ne fait que les disposer.
 */

const Figure: React.FC<{ label: string; value: string; hint?: string }> = ({ label, value, hint }) => (
  <div className="min-w-0">
    <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider">{label}</p>
    <p className="text-sm text-on-surface font-mono tabular-nums truncate" title={hint ?? value}>{value}</p>
  </div>
);

const Section: React.FC<{ title: string; hint?: string; children: React.ReactNode }> = ({
  title, hint, children,
}) => (
  <div>
    <p className="text-[10px] font-bold text-on-surface-variant uppercase tracking-wider mb-1.5">
      {title}
    </p>
    {hint && <p className="text-[11px] text-on-surface-variant mb-1.5 leading-snug">{hint}</p>}
    {children}
  </div>
);

export const ProcessModelPanel: React.FC<{ model: ProcessModel | null | undefined }> = ({ model }) => {
  if (!model) return null;

  if (!model.available) {
    return (
      <div className="rounded-xl border border-outline-variant/60 bg-surface-container/40 p-4">
        <p className="text-xs font-semibold text-on-surface">Measured process</p>
        <p className="text-xs text-on-surface-variant mt-1 leading-snug">
          {describeUnavailable(model)}
        </p>
        {model.eventsWithoutCase > 0 && (
          <p className="text-[11px] text-on-surface-variant mt-1.5">
            {model.eventsWithoutCase.toLocaleString()} record(s) were read and digested all the same
            — they are simply outside the log.
          </p>
        )}
      </div>
    );
  }

  const slowest = slowestEdge(model);
  const skewed = skewedEdges(model);
  const otherEnds = minorityEnds(model);

  return (
    <div className="rounded-xl border border-outline-variant/60 bg-surface-container/40 p-4 space-y-4">
      <div>
        <div className="flex items-center gap-2">
          <span aria-hidden="true" className="material-symbols-outlined text-lg text-primary">
            query_stats
          </span>
          <p className="text-xs font-semibold text-on-surface">Measured process</p>
        </div>
        <p className="text-xs text-on-surface-variant mt-1">{describeScope(model)}</p>
        <p className="text-[11px] text-on-surface-variant mt-1 leading-snug">
          Counted over every record read, not over the sample shown to the model — so the narrative
          above can be checked against it.
        </p>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Figure label="Cases" value={model.cases.toLocaleString()} />
        <Figure label="Events" value={model.events.toLocaleString()} />
        <Figure label="Variants" value={`${model.variants.length}${model.variantsOmitted > 0 ? `+${model.variantsOmitted}` : ''}`} />
        {slowest && (
          <Figure
            label="Slowest hop (p95)"
            value={formatDuration(slowest.p95Ms)}
            hint={`${slowest.from} → ${slowest.to}`}
          />
        )}
      </div>

      {model.edges.length > 0 && (
        <Section
          title={`Transitions (${model.edges.length}${model.edgesOmitted > 0 ? ` of ${model.edges.length + model.edgesOmitted}` : ''})`}
        >
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-[10px] uppercase tracking-wider text-on-surface-variant">
                  <th className="text-left font-medium py-1 pr-3">From → To</th>
                  <th className="text-right font-medium py-1 px-2">Cases</th>
                  <th className="text-right font-medium py-1 px-2">p50</th>
                  <th className="text-right font-medium py-1 px-2">p95</th>
                  <th className="text-right font-medium py-1 pl-2">Max</th>
                </tr>
              </thead>
              <tbody>
                {model.edges.map(e => (
                  <tr key={`${e.from}→${e.to}`} className="border-t border-outline-variant/40">
                    <td className="py-1 pr-3 text-on-surface break-words">
                      <span className="font-mono">{e.from}</span>
                      <span aria-hidden="true" className="text-on-surface-variant"> → </span>
                      <span className="font-mono">{e.to}</span>
                      {e.outOfOrderCount > 0 && (
                        <span
                          className="ml-1.5 text-[10px] text-warning"
                          title={`${e.outOfOrderCount} occurrence(s) produced in the opposite order to their business timestamps`}
                        >
                          clock skew
                        </span>
                      )}
                    </td>
                    <td className="py-1 px-2 text-right font-mono tabular-nums text-on-surface-variant">
                      {e.cases.toLocaleString()}
                    </td>
                    <td className="py-1 px-2 text-right font-mono tabular-nums text-on-surface-variant">
                      {formatDuration(e.p50Ms)}
                    </td>
                    <td className="py-1 px-2 text-right font-mono tabular-nums text-on-surface">
                      {formatDuration(e.p95Ms)}
                    </td>
                    <td className="py-1 pl-2 text-right font-mono tabular-nums text-on-surface-variant">
                      {formatDuration(e.maxMs)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      )}

      {model.variants.length > 0 && (
        <Section
          title="Variants"
          hint="Distinct end-to-end paths. The most frequent and the rarest are both shown — a deviation four cases in nine hundred took is usually the finding."
        >
          <ul className="space-y-1.5">
            {model.variants.map(v => (
              <li key={v.path.join('→')} className="text-xs">
                <div className="flex items-baseline gap-2">
                  <span className="font-mono tabular-nums text-on-surface w-16 flex-shrink-0">
                    {share(v.cases, model.cases)}
                  </span>
                  <span className="text-on-surface-variant break-words">
                    {v.path.join(' → ')}
                  </span>
                </div>
                <p className="text-[10px] text-outline ml-[4.5rem]">
                  {v.cases.toLocaleString()} case(s) · e.g. <span className="font-mono">{v.example}</span>
                </p>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {otherEnds.length > 0 && (
        <Section
          title="Where cases ended"
          hint="A distribution, not a verdict: which activity ought to end this process is a business fact the application does not have."
        >
          <ul className="text-xs text-on-surface-variant space-y-0.5">
            {model.ends.map(e => (
              <li key={e.activity} className="flex items-baseline gap-2">
                <span className="font-mono tabular-nums w-16 flex-shrink-0 text-on-surface">
                  {share(e.cases, model.cases)}
                </span>
                <span className="font-mono break-words">{e.activity}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {model.repeats.length > 0 && (
        <Section
          title="Repeated steps"
          hint="The same case seen twice on one activity — a redelivery, a retry or a legitimate rework loop. Which one it is depends on the business."
        >
          <ul className="text-xs text-on-surface-variant space-y-0.5">
            {model.repeats.map(r => (
              <li key={r.activity}>
                <span className="font-mono text-on-surface">{r.activity}</span>
                {' — '}
                {r.casesAffected.toLocaleString()} case(s), up to {r.maxOccurrencesInOneCase} times for one
              </li>
            ))}
          </ul>
        </Section>
      )}

      {(model.notes.length > 0 || skewed.length > 0) && (
        <Section title="Limits of this measurement">
          <ul className="space-y-1">
            {skewed.length > 0 && (
              <li className="text-[11px] text-on-surface-variant leading-snug">
                {skewed.length} transition(s) were produced in the opposite order to their business
                timestamps — a skewed producer clock or a back-dated event, not a process defect.
              </li>
            )}
            {model.notes.map((note, i) => (
              <li key={i} className="text-[11px] text-on-surface-variant leading-snug">{note}</li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
};

export default ProcessModelPanel;
