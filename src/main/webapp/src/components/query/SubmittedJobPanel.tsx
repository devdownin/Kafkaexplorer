// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

// Composant de la page SQL Editor, sorti de `QueryWorkbench.tsx` — voir `ResultsGrid.tsx`.
import React from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../ui';
import type { FlinkJobSummary, FlinkManagedJobDetails } from '../../api/types';
import { describeJobOutcome, historyLines, isJobTerminal } from '../../pages/flinkJobHistory';

export interface SubmittedJobPanelProps {
  submission: FlinkJobSummary;
  /** Ce que `GET /api/query/jobs/{queryId}` a rendu au dernier tour, ou null avant le premier. */
  details: FlinkManagedJobDetails | null;
  /** La raison pour laquelle on ne sait plus rien du job — jamais confondue avec « il va bien ». */
  detailsError: string | null;
  polling: boolean;
  stopping: boolean;
  onStop: () => void;
  /** Proposé seulement quand la cible de l'INSERT ne résout pas dans le catalogue. */
  onCreateTarget?: () => void;
  createTargetLabel?: string;
}

/**
 * Ce qu'est devenu le job soumis — et non ce qu'il était à la milliseconde où il est parti.
 *
 * <p>Ce panneau affichait le statut lu ~150 ms après la soumission, et plus rien ensuite : un job
 * qui meurt à sa première ligne — broker injoignable, sérialisation, option refusée à l'ouverture
 * du sink — laissait « Flink job submitted » en vert pour toujours, et il fallait aller au tableau
 * de bord pour l'apprendre. C'est le contraire de ce qu'une barre d'état doit faire, sur le seul
 * geste de cette page qui n'a aucun repli.
 *
 * <p>`GET /api/query/jobs/{queryId}` servait déjà `statusDetail`, `errorMessage` et l'historique
 * daté, et n'était lu que par la carte du tableau de bord. La lecture de ces trois champs est
 * celle de cette carte-là (`pages/flinkJobHistory.ts`), importée plutôt que réécrite : deux
 * lectures d'un même enregistrement finissent par en dire deux choses différentes.
 *
 * <p>Trois états sont distingués, parce qu'ils envoient ailleurs : le job tourne (on continue de
 * demander), il s'est terminé (on cesse), ou son état n'a pas pu être relu — ce dernier n'est
 * <em>pas</em> une fin, et la phrase le dit plutôt que de laisser le vert de la soumission valoir
 * pour un bilan.
 */
export const SubmittedJobPanel: React.FC<SubmittedJobPanelProps> = ({
  submission, details, detailsError, polling, stopping, onStop, onCreateTarget, createTargetLabel,
}) => {
  const status = details?.status ?? submission.status;
  const failed = status.toUpperCase() === 'FAILED';
  const ended = isJobTerminal(status);
  const history = historyLines(details);
  // Les classes sont écrites en toutes lettres : Tailwind ne voit pas un nom construit à
  // l'exécution, donc `bg-${tone}/10` ne produit aucune règle et le panneau perd sa couleur.
  const box = failed
    ? 'bg-error/10 border-error/30'
    : ended ? 'bg-surface-container-high border-outline-variant' : 'bg-success/10 border-success/30';
  const accent = failed ? 'text-error' : ended ? 'text-on-surface-variant' : 'text-success';

  return (
    <div className="p-4">
      <div className={`flex items-start gap-3 p-4 rounded-lg border ${box}`}>
        <span className={`material-symbols-outlined ${accent} text-base mt-0.5 shrink-0`}>
          {failed ? 'error' : ended ? 'check_circle' : 'rocket_launch'}
        </span>
        <div className="flex-1 min-w-0 space-y-2">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className={`${accent} font-semibold text-sm`}>
                {failed ? 'Flink job failed' : ended ? 'Flink job ended' : 'Flink job running'}
              </p>
              <p className="text-xs text-on-surface-variant">
                {details
                  /* `lastUpdatedAt` plutôt que l'horloge du navigateur : c'est le dernier
                     instant où le serveur a regardé, et il est mesuré sur la même horloge que
                     `startedAt` — la durée est donc juste, et le rendu reste pur, ce que
                     `Date.now()` en plein rendu n'est pas. */
                  ? describeJobOutcome(details, details.lastUpdatedAt)
                  : 'The SQL was accepted in asynchronous job mode.'}
                {polling && !ended && ' Refreshing…'}
              </p>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              {!ended && (
                <Button variant="secondary" icon="stop_circle" onClick={onStop} disabled={stopping}>
                  {stopping ? 'Stopping…' : 'Stop'}
                </Button>
              )}
              <Link to="/" className="text-[11px] text-primary hover:underline whitespace-nowrap">
                Dashboard
              </Link>
            </div>
          </div>

          {/* La raison est ce qu'on vient lire sur une carte rouge, et elle n'existe nulle part
              ailleurs : `FlinkJobSummary` ne la porte pas. */}
          {details?.errorMessage && (
            <pre className="text-[10px] text-error font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-error/20 pt-2">
              {details.errorMessage}
            </pre>
          )}
          {details?.statusDetail && !details.errorMessage && (
            <p className="text-[11px] text-on-surface-variant">{details.statusDetail}</p>
          )}
          {/* « Nous n'avons pas su redemander » n'est pas « le job va bien ». */}
          {detailsError && (
            <p className="text-[11px] text-warning">
              The job's state could not be re-read: {detailsError}
            </p>
          )}

          <div className="grid grid-cols-1 md:grid-cols-2 gap-2 text-[11px] font-mono text-on-surface">
            <div>
              <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Status</p>
              <p>{status}</p>
            </div>
            <div>
              <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Type</p>
              <p>{submission.statementType.replace('_', ' ')}</p>
            </div>
            <div>
              <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Query ID</p>
              <p className="break-all">{submission.queryId}</p>
            </div>
            <div>
              <p className="text-on-surface-variant uppercase tracking-wider text-[10px]">Flink Job ID</p>
              <p className="break-all">{submission.flinkJobId}</p>
            </div>
          </div>

          {history.length > 1 && (
            <ul className="text-[10px] text-on-surface-variant font-mono border-t border-outline-variant pt-2 space-y-0.5">
              {history.map(line => (
                <li key={`${line.timestamp}-${line.status}`}>
                  {new Date(line.timestamp).toLocaleTimeString()} · {line.status}
                  {line.detail ? ` — ${line.detail}` : ''}
                </li>
              ))}
            </ul>
          )}

          {onCreateTarget && (
            <div className="border-t border-outline-variant pt-2">
              <Button variant="secondary" icon="add_box" onClick={onCreateTarget}>
                {createTargetLabel ?? 'Create the target table'}
              </Button>
            </div>
          )}

          <pre className="text-[10px] text-on-surface-variant font-mono whitespace-pre-wrap overflow-x-auto leading-relaxed border-t border-outline-variant pt-2">
            {submission.sql}
          </pre>
        </div>
      </div>
    </div>
  );
};
