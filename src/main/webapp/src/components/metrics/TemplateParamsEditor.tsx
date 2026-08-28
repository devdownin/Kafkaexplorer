// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * L'éditeur de paramètres d'un gabarit — ce qui s'affiche à la place de l'éditeur SQL quand la
 * métrique n'est pas du SQL brut.
 *
 * Sorti de `Metrics.tsx` comme la carte, et pour la même raison : la page faisait 2 272 lignes,
 * dont un tiers ne décrivait pas la page mais deux surfaces qu'elle contient. Rien n'est réécrit,
 * c'est le composant tel qu'il était.
 *
 * La validation n'est pas ici : elle vit dans `metricsEditor.ts` et la page la rend, parce que
 * c'est la page qui décide quand un formulaire est soumettable. Ce fichier ne fait que poser les
 * champs qu'un gabarit demande.
 */

import React from 'react';
import { Field, Input, NumberInput, Select, Textarea, TopicInput } from '../ui';
import {
  DELTA_OPERATIONS, EXECUTION_MODES, LATENCY_WINDOWS, REFRESH_INTERVALS,
  SCAN_MAX_ROWS_DEFAULT, SCAN_TIMEOUT_MS_DEFAULT, defaultReadMode, paramStr,
} from '../../pages/metricsEditor';

// ── Template parameter editor (shown in place of the raw SQL editor) ─────────
const ParamSql: React.FC<{
  label: string; hint?: string; value: string; placeholder: string;
  onChange: (v: string) => void;
}> = ({ label, hint, value, placeholder, onChange }) => (
  <Field label={label} description={hint}>
    {p => (
      <Textarea
        {...p}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        rows={4}
        spellCheck={false}
        className="font-mono text-[12px]"
      />
    )}
  </Field>
);

/** Étiquette Prometheus libre : un nom de topic, donc suggéré depuis le catalogue. */
const ParamTopic: React.FC<{
  label: string; value: string; placeholder: string; onChange: (v: string) => void;
}> = ({ label, value, placeholder, onChange }) => (
  <Field label={label}>
    {p => <TopicInput {...p} value={value} onChange={onChange} placeholder={placeholder} />}
  </Field>
);

/**
 * Ce que chaque côté lit, pour les deux gabarits qui lancent deux requêtes.
 *
 * Ces trois réglages décidaient déjà ce que la métrique mesure — quelle part de chaque topic est
 * lue, par quel bout, et combien de temps un côté peut prendre — et n'étaient sur aucun
 * formulaire : seul un POST écrit à la main pouvait les changer. Le gabarit CONSUMER_TIME_LAG,
 * juste au-dessus, énonce son budget depuis toujours.
 */
const ScanParams: React.FC<{
  templateType: string;
  params: Record<string, unknown>;
  setParam: (key: string, value: string) => void;
}> = ({ templateType, params, setParam }) => {
  const p = (k: string) => paramStr(params, k);
  const readMode = p('readMode') || defaultReadMode(templateType);
  const maxRows = Number(p('maxRowsPerSide')) || SCAN_MAX_ROWS_DEFAULT;
  const timeoutMs = Number(p('timeoutMs')) || SCAN_TIMEOUT_MS_DEFAULT;
  const windowMs = p('windowMs');
  const isLatency = templateType === 'TOPIC_TRANSIT_LATENCY';
  const windowed = isLatency && windowMs !== '' && windowMs !== '0';
  return (
    <div className="border-t border-outline-variant/60 pt-4 space-y-3">
      <p className="text-[11px] text-on-surface-variant leading-snug">
        What each side reads. The two queries run one after the other, so the two figures are a
        query apart rather than one instant.
      </p>
      {isLatency && (
        <Field
          label="Window"
          description="A row cap over two topics of different throughputs reads two different stretches of time, so the pairs that survive are an accident of those throughputs — and the match rate is depressed by that as much as by a real loss. A window reads both sides from one instant."
        >
          {f => (
            <Select {...f} value={windowMs} onChange={e => setParam('windowMs', e.target.value)}>
              {LATENCY_WINDOWS.map(w => (
                <option key={w.value} value={w.value} className="bg-[#12151a] text-on-surface">{w.label}</option>
              ))}
            </Select>
          )}
        </Field>
      )}
      {!windowed && (
      <Field
        label="Read from"
        description={
          isLatency
            ? 'A latency is a question about now: read from the earliest offset it reports the average of the oldest records the row cap allowed, and never moves again.'
            : 'A count must see the whole topic, so this normally starts at the earliest offset.'
        }
      >
        {f => (
          <Select {...f} value={readMode} onChange={e => setParam('readMode', e.target.value)}>
            <option value="latest-offset" className="bg-[#12151a] text-on-surface">The most recent records</option>
            <option value="earliest-offset" className="bg-[#12151a] text-on-surface">The earliest offset onwards</option>
          </Select>
        )}
      </Field>
      )}
      <div className="grid grid-cols-2 gap-3">
        <Field label="Max rows / side" description="Read no further; the summary says what was covered.">
          {f => (
            <NumberInput {...f} value={maxRows} fallback={SCAN_MAX_ROWS_DEFAULT} min={1} max={1_000_000}
              onChange={v => setParam('maxRowsPerSide', String(v))} />
          )}
        </Field>
        <Field label="Timeout / side (ms)" description="Each side has its own, so a refresh can cost twice it.">
          {f => (
            <NumberInput {...f} value={timeoutMs} fallback={SCAN_TIMEOUT_MS_DEFAULT} min={1_000} max={600_000}
              onChange={v => setParam('timeoutMs', String(v))} />
          )}
        </Field>
      </div>
      <Field
        label="Refresh at most"
        description="A two-query metric reads two topics; asking that of the broker every cycle because a single-row gauge beside it wants that cadence is what makes the refresh loop expensive. This can only slow it down — the loop\u2019s own tick is the floor."
      >
        {f => (
          <Select {...f} value={p('refreshIntervalMs')} onChange={e => setParam('refreshIntervalMs', e.target.value)}>
            {REFRESH_INTERVALS.map(r => (
              <option key={r.value} value={r.value} className="bg-[#12151a] text-on-surface">{r.label}</option>
            ))}
          </Select>
        )}
      </Field>
    </div>
  );
};

export const TemplateParamsEditor: React.FC<{
  templateType: string;
  params: Record<string, unknown>;
  executionMode: string;
  table: string;
  setParam: (key: string, value: string) => void;
  setExecutionMode: (mode: string) => void;
}> = ({ templateType, params, executionMode, table, setParam, setExecutionMode }) => {
  const p = (k: string) => paramStr(params, k);
  return (
    <div className="h-full overflow-y-auto p-5 space-y-4">
      {templateType === 'CONSUMER_TIME_LAG' ? (
        <>
          <ParamTopic label="Topic" value={p('topic')} onChange={v => setParam('topic', v)} placeholder="demo.payments" />
          <Field
            label="Consumer group"
            description="Named, never resolved to “the worst one”: that choice would move between refreshes and the series would change subject without saying so."
          >
            {f => (
              <Input {...f} value={p('group')} onChange={e => setParam('group', e.target.value)}
                placeholder="payments-api" spellCheck={false} className="font-mono text-[12px]" />
            )}
          </Field>
          <Field
            label="Across partitions"
            description="The worst partition is what an alert is set on; a mean hides it behind the healthy ones."
          >
            {f => (
              <Select {...f} value={p('aggregation') || 'MAX'} onChange={e => setParam('aggregation', e.target.value)}>
                <option value="MAX" className="bg-[#12151a] text-on-surface">Worst partition (MAX)</option>
                <option value="AVG" className="bg-[#12151a] text-on-surface">Mean over partitions (AVG)</option>
              </Select>
            )}
          </Field>
        </>
      ) : templateType === 'TOPIC_COUNT_DELTA' ? (
        <>
          <ParamSql label="Left query — metric_value" value={p('leftSql')} onChange={v => setParam('leftSql', v)}
            hint="Bounded query returning a single metric_value."
            placeholder={`SELECT COUNT(*) AS metric_value\nFROM ${table}`} />
          <ParamSql label="Right query — metric_value" value={p('rightSql')} onChange={v => setParam('rightSql', v)}
            hint="Compared against the left query."
            placeholder={`SELECT COUNT(*) AS metric_value\nFROM other_table`} />
          <Field label="Operation">
            {f => (
              <Select {...f} value={p('operation') || 'LEFT_MINUS_RIGHT'} onChange={e => setParam('operation', e.target.value)}>
                {DELTA_OPERATIONS.map(o => (
                  <option key={o.value} value={o.value} className="bg-[#12151a] text-on-surface">{o.label}</option>
                ))}
              </Select>
            )}
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <ParamTopic label="Left topic"  value={p('leftTopic')}  onChange={v => setParam('leftTopic', v)}  placeholder="demo.orders.1.received" />
            <ParamTopic label="Right topic" value={p('rightTopic')} onChange={v => setParam('rightTopic', v)} placeholder="demo.orders.2.validated" />
          </div>
          <Field
            label="Count by"
            description="Offsets ask the log how much it holds — no record is read, there is no scan ceiling, and both sides come out of one call, so they describe the same instant. They count what was produced, so a transaction marker counts and a compacted record still counts."
          >
            {f => (
              <Select {...f} value={p('countBy') || 'AUTO'} onChange={e => setParam('countBy', e.target.value)}>
                <option value="AUTO" className="bg-[#12151a] text-on-surface">Automatic — offsets for a plain whole-topic count</option>
                <option value="OFFSETS" className="bg-[#12151a] text-on-surface">The log's offsets (no records read)</option>
                <option value="RECORDS" className="bg-[#12151a] text-on-surface">Records returned by the two queries</option>
              </Select>
            )}
          </Field>
          <Field
            label="Compare"
            description="A lifetime total loses its sensitivity as history accumulates: on topics running for months, a total outage that started an hour ago is a fraction of a percent."
          >
            {f => (
              <Select {...f} value={p('window') || 'TOTAL'} onChange={e => setParam('window', e.target.value)}>
                <option value="TOTAL" className="bg-[#12151a] text-on-surface">The totals</option>
                <option value="SINCE_LAST_REFRESH" className="bg-[#12151a] text-on-surface">What each side produced since the last refresh</option>
              </Select>
            )}
          </Field>
          <ScanParams templateType={templateType} params={params} setParam={setParam} />
        </>
      ) : (
        <>
          <ParamSql label="Source query — match_key, event_time" value={p('sourceSql')} onChange={v => setParam('sourceSql', v)}
            hint="Emit one row per source event with a match_key and an event_time (ISO-8601 or epoch)."
            placeholder={`SELECT order_id AS match_key,\n       created_at AS event_time\nFROM ${table}`} />
          <ParamSql label="Target query — match_key, event_time" value={p('targetSql')} onChange={v => setParam('targetSql', v)}
            hint="Downstream events, matched on match_key; latency = target − source."
            placeholder={`SELECT order_id AS match_key,\n       processed_at AS event_time\nFROM target_table`} />
          <div className="grid grid-cols-2 gap-3">
            <ParamTopic label="Source topic (label)" value={p('sourceTopic')} onChange={v => setParam('sourceTopic', v)} placeholder="optional" />
            <ParamTopic label="Target topic (label)" value={p('targetTopic')} onChange={v => setParam('targetTopic', v)} placeholder="optional" />
          </div>
          <ScanParams templateType={templateType} params={params} setParam={setParam} />
        </>
      )}

      <div className="border-t border-outline-variant/60 pt-4">
        <Field
          label="Execution Mode"
          description={EXECUTION_MODES.find(m => m.value === (executionMode || 'TEMPLATE_BOUNDED_SCAN'))?.note}
        >
          {f => (
            <Select {...f} value={executionMode || 'TEMPLATE_BOUNDED_SCAN'} onChange={e => setExecutionMode(e.target.value)}>
              {EXECUTION_MODES.map(m => (
                <option key={m.value} value={m.value} className="bg-[#12151a] text-on-surface">{m.label}</option>
              ))}
            </Select>
          )}
        </Field>
      </div>
    </div>
  );
};
