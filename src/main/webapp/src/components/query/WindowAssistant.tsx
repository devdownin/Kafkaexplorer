// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

// Composant de la page SQL Editor, sorti de `QueryWorkbench.tsx` — voir `ResultsGrid.tsx`.
import React, { useMemo, useState } from 'react';
import { Button, Field, Input, NumberInput, Select, Tooltip } from '../ui';
import {
  buildWindowSql, windowCaveat, guessTimeColumn,
  type WindowKind, type WindowUnit,
} from '../../pages/windowSql';

export interface WindowAssistantProps {
  /** Table visée : celle que la requête cite, sinon la première du catalogue. */
  table: string;
  /** Colonnes connues de cette table, pour deviner la colonne temporelle. */
  tableSchema: Record<string, string> | undefined;
  open: boolean;
  onToggle: () => void;
  /** Reçoit le SQL généré ; la page décide où le poser (curseur, sélection, fin de l'onglet). */
  /**
   * Reçoit la requête générée ; elle remplace tout le contenu de l'éditeur — après confirmation
   * si l'onglet n'est pas vide, d'où la promesse.
   */
  onApply: (sql: string) => void | Promise<void>;
}

/**
 * Assistant de fenêtrage.
 *
 * Il **possède son propre état de formulaire**, qui vivait dans la page : rien d'autre ne le lit,
 * et le remonter n'apportait que douze props de plus. Il reste monté quand il est replié — c'est
 * lui qui rend le rail — précisément pour que ce formulaire survive au repli ; le sortir dans une
 * branche séparée le remettrait à zéro à chaque ouverture.
 */
export const WindowAssistant: React.FC<WindowAssistantProps> = ({
  table, tableSchema, open, onToggle, onApply,
}) => {
  const [windowType, setWindowType] = useState<WindowKind>('TUMBLE');
  const [windowSize, setWindowSize] = useState(5);
  const [windowSlide, setWindowSlide] = useState(1);
  const [windowUnit, setWindowUnit] = useState<WindowUnit>('MINUTE');
  const [windowTimeCol, setWindowTimeCol] = useState('');
  const [windowPartitionBy, setWindowPartitionBy] = useState('');

  // Pré-remplit la colonne temporelle depuis le schéma chargé, tant que l'utilisateur n'a rien
  // saisi. L'assistant écrivait `event_time` en dur — un nom que la plupart des topics n'ont pas.
  const guessedTimeCol = useMemo(() => guessTimeColumn(tableSchema), [tableSchema]);
  const effectiveTimeCol = windowTimeCol.trim() || guessedTimeCol || 'event_time';

  const windowSpec = useMemo(() => ({
    kind: windowType,
    table,
    timeColumn: effectiveTimeCol,
    size: windowSize,
    unit: windowUnit,
    slide: windowSlide,
    partitionBy: windowPartitionBy,
  }), [windowType, table, effectiveTimeCol, windowSize, windowUnit, windowSlide, windowPartitionBy]);

  // Replié : un rail qui le rouvre. Il prenait 288 px à l'éditeur en permanence pour un outil
  // occasionnel, et rien ne permettait de le replier.
  if (!open) {
    return (
      <div className="w-10 border-l border-outline-variant/60 bg-surface-container-low/40 flex flex-col items-center pt-3 shrink-0">
        <Tooltip content="Open the Window Assistant — it builds a TUMBLE / HOP / SESSION query over a table.">
          <button type="button" onClick={onToggle}
            aria-expanded={false} aria-controls="kse-window-assistant"
            className="p-1.5 rounded-md text-on-surface-variant hover:text-primary hover:bg-surface-container-high transition-colors"
            aria-label="Open the Window Assistant">
            <span aria-hidden="true" className="material-symbols-outlined text-[20px]">magic_button</span>
          </button>
        </Tooltip>
        <span aria-hidden="true" className="mt-3 text-[10px] tracking-[0.08em] uppercase text-outline [writing-mode:vertical-rl]">Window</span>
      </div>
    );
  }

  return (
    <div id="kse-window-assistant" className="w-72 border-l border-outline-variant/60 bg-surface-container-low/40 p-4 flex flex-col gap-4 overflow-y-auto shrink-0">
      <div className="flex items-center gap-2">
        <span aria-hidden="true" className="material-symbols-outlined text-primary text-[20px]">magic_button</span>
        <h3 className="text-[14px] font-semibold text-on-surface">Window Assistant</h3>
        <button type="button" onClick={onToggle} aria-expanded
          className="ml-auto p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
          aria-label="Fold the Window Assistant" title="Fold">
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">right_panel_close</span>
        </button>
      </div>
      <p className="text-[12px] text-on-surface-variant leading-relaxed">
        Builds a windowed aggregation over <span className="font-mono text-on-surface">{table}</span>.
      </p>
      <div className="space-y-3">
        <Field label="Window type">
          {p => (
            <Select {...p} value={windowType} onChange={e => setWindowType(e.target.value as WindowKind)}>
              <option value="TUMBLE">Tumbling (Non-overlapping)</option>
              <option value="HOP">Hopping (Overlapping)</option>
              <option value="SESSION">Session (Inactivity based)</option>
            </Select>
          )}
        </Field>
        <Field label={windowType === 'SESSION' ? 'Inactivity gap' : 'Size'} className="flex-1">
          {p => (
            <div className="flex gap-2">
              {/* parseInt(e.target.value) donnait NaN dès que le champ était vidé. */}
              <NumberInput {...p} min={1} fallback={5} className="flex-1"
                value={windowSize} onChange={setWindowSize} />
              <Select aria-label="Time unit" className="w-28"
                value={windowUnit} onChange={e => setWindowUnit(e.target.value as WindowUnit)}>
                <option value="SECOND">SECOND</option>
                <option value="MINUTE">MINUTE</option>
                <option value="HOUR">HOUR</option>
              </Select>
            </div>
          )}
        </Field>
        {windowType === 'HOP' && (
          <Field label="Slide" description="How far each window advances. Must be smaller than the size to overlap.">
            {p => <NumberInput {...p} min={1} fallback={1} value={windowSlide} onChange={setWindowSlide} />}
          </Field>
        )}
        <Field
          label="Time column"
          description={guessedTimeCol
            ? `Detected “${guessedTimeCol}” in the table schema.`
            : 'Expand the table in the sidebar to detect one automatically.'}
        >
          {p => (
            <Input {...p} value={windowTimeCol} placeholder={effectiveTimeCol}
              onChange={e => setWindowTimeCol(e.target.value)} />
          )}
        </Field>
        {windowType === 'SESSION' && (
          <Field label="Partition by" description="Required by Flink for SESSION windows.">
            {p => (
              <Input {...p} value={windowPartitionBy} placeholder="user_id"
                onChange={e => setWindowPartitionBy(e.target.value)} />
            )}
          </Field>
        )}
        {windowCaveat(windowSpec) ? (
          <div className="p-3 bg-warning/10 border border-warning/30 rounded-lg">
            <p className="text-[11px] text-on-surface-variant leading-snug">
              <span className="font-semibold text-warning">Heads up:</span> {windowCaveat(windowSpec)}
            </p>
          </div>
        ) : (
          <div className="p-3 bg-primary/5 border border-primary/20 rounded-lg">
            <p className="text-[11px] text-on-surface-variant leading-snug"><span className="font-semibold text-primary">Tip:</span> Tumbling windows are ideal for periodic metrics like “Orders per 5 min”.</p>
          </div>
        )}
        <Button variant="secondary" className="w-full" icon="bolt"
          onClick={() => { void onApply(buildWindowSql(windowSpec)); }}>
          Replace editor content
        </Button>
      </div>
    </div>
  );
};
