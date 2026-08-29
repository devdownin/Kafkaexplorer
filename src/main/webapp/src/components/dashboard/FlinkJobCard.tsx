// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import axios from 'axios';
import { Badge, Button, Card, type BadgeTone } from '../ui';
import type { FlinkJobSummary, FlinkManagedJobDetails } from '../../api/types';
import { describeApiError } from '../../pages/queryError';
import { describeJobOutcome, formatDuration, historyLines } from '../../pages/flinkJobHistory';

/**
 * Une carte du panneau « Flink SQL Jobs », historique compris.
 *
 * <p>Extraite du tableau de bord parce qu'elle a grandi : ce que le résumé porte tenait dans la
 * boucle qui le rendait, ce que le magasin garde ne tient plus. Le détail est chargé **par la
 * carte et au clic**, ce qui est aussi ce qui garde l'état hors de la page — le tableau de bord
 * sonde toutes les cinq secondes, et une lecture par carte à chaque tour ferait payer à tout le
 * monde un détail que personne ne regarde.
 */

/** axios n'a pas de délai par défaut : sans ça, un dépli tourne sans fin sur un serveur muet. */
const JOB_DETAIL_TIMEOUT_MS = 10000;

const formatJobTime = (ms: number | null | undefined) =>
  ms ? new Date(ms).toLocaleString() : '—';

/**
 * Les statuts sur lesquels le bouton Kill n'a plus rien à faire.
 *
 * Volontairement plus court que `FlinkJobStore.isTerminal` côté serveur, qui compte `UNKNOWN`
 * parmi les états terminaux : un job dans cet état ne parvient jamais jusqu'ici, la liste que le
 * tableau de bord reçoit étant déjà filtrée dessus. Voir F11 dans `FLINK-JOBS-AUDIT.md` — c'est
 * une règle écrite des deux côtés du fil, et elle est ici inobservable, pas juste.
 */
const isTerminalStatus = (status: string) =>
  ['FINISHED', 'FAILED', 'CANCELED', 'CANCELLED'].includes(status.toUpperCase());

const getJobBadgeTone = (status: string): BadgeTone => {
  const upper = status.toUpperCase();
  if (upper === 'RUNNING') return 'primary';
  if (['CANCELLING', 'CANCEL_REQUESTED'].includes(upper)) return 'warning';
  if (upper === 'FINISHED') return 'success';
  if (upper === 'FAILED') return 'error';
  return 'neutral';
};

interface Props {
  job: FlinkJobSummary;
  /** Passé plutôt que lu ici : la page tient déjà une horloge, deux en dériveraient. */
  now: number;
  killing: boolean;
  onKill: (queryId: string) => void;
}

const FlinkJobCard: React.FC<Props> = ({ job, now, killing, onKill }) => {
  const [open, setOpen] = useState(false);
  const [details, setDetails] = useState<FlinkManagedJobDetails | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * `GET /api/query/jobs/{id}` sert `statusDetail`, `errorMessage` et les transitions datées — les
   * trois champs que `FlinkJobSummary` laisse tomber, et donc la seule réponse à « qu'est-il
   * arrivé à mon INSERT ». Lu une fois : replier puis rouvrir ne relance rien.
   */
  const toggle = async () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (details) return;
    setLoading(true);
    setError(null);
    try {
      const response = await axios.get<FlinkManagedJobDetails>(
        `/api/query/jobs/${encodeURIComponent(job.queryId)}`, { timeout: JOB_DETAIL_TIMEOUT_MS });
      setDetails(response.data);
    } catch (e) {
      // Un panneau qui reste, pas un toast qui passe : l'échec porte sur ce qu'on venait lire.
      const described = describeApiError(e);
      setError(described.hint ?? described.title);
    } finally {
      setLoading(false);
    }
  };

  const lines = details ? historyLines(details) : [];

  return (
    <Card padding="none" className="p-4 flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] uppercase tracking-[0.05em] text-on-surface-variant">
            {job.statementType.replace('_', ' ')}
          </p>
          <p className="text-[12px] font-mono text-on-surface line-clamp-2 mt-0.5">{job.sql}</p>
          <p className="text-[11px] text-outline font-mono mt-1">Query: {job.queryId.substring(0, 16)}</p>
          {/* Nul quand la soumission a échoué avant qu'un JobClient existe. */}
          <p className="text-[11px] text-outline font-mono">Flink: {(job.flinkJobId ?? '—').substring(0, 16)}</p>
        </div>
        <Badge tone={getJobBadgeTone(job.status)} dot>{job.status}</Badge>
      </div>
      <div className="grid grid-cols-2 gap-2 text-[11px] text-on-surface-variant font-mono">
        <div>
          <p className="uppercase tracking-[0.05em] text-outline">Started</p>
          <p>{formatJobTime(job.startedAt)}</p>
        </div>
        <div>
          <p className="uppercase tracking-[0.05em] text-outline">Ended</p>
          <p>{formatJobTime(job.endedAt)}</p>
        </div>
      </div>
      {job.cancelRequested && (
        <p className="text-[11px] text-warning font-mono">Cancellation requested</p>
      )}

      <div className="border-t border-outline-variant pt-2">
        <button
          type="button"
          onClick={() => void toggle()}
          aria-expanded={open}
          aria-controls={`job-history-${job.queryId}`}
          className="flex w-full items-center gap-1.5 min-h-[24px] px-1 -mx-1 rounded text-[11px]
                     text-on-surface-variant hover:text-on-surface hover:bg-surface-variant/40
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <span className="material-symbols-outlined text-[16px]" aria-hidden="true">
            {open ? 'expand_less' : 'expand_more'}
          </span>
          History and detail
        </button>
        {open && (
          <div id={`job-history-${job.queryId}`} className="mt-2 space-y-2">
            {loading && <p className="text-[11px] text-on-surface-variant">Reading the job record…</p>}
            {error && <p className="text-[11px] text-error">{error}</p>}
            {details && (
              <>
                <p className="text-[11px] text-on-surface-variant">{describeJobOutcome(details, now)}</p>
                {details.errorMessage && (
                  <p className="text-[11px] text-error font-mono break-words">{details.errorMessage}</p>
                )}
                {/* Répond à « d'où vient ce job » : une soumission en mode Flink Job, ou la
                    lecture synchrone d'un écran. */}
                <p className="text-[11px] text-outline font-mono">{details.executionMode}</p>
                <ol className="space-y-1">
                  {lines.map((line, i) => (
                    <li key={`${line.timestamp}-${i}`} className="text-[11px] flex gap-2">
                      <span className="text-outline font-mono tabular-nums shrink-0">
                        {formatJobTime(line.timestamp)}
                      </span>
                      <span className="min-w-0">
                        <span className="text-on-surface font-mono">{line.status}</span>
                        {line.sincePreviousMs !== null && (
                          <span className="text-outline"> · after {formatDuration(line.sincePreviousMs)}</span>
                        )}
                        {line.detail && (
                          <span className="block text-on-surface-variant break-words">{line.detail}</span>
                        )}
                      </span>
                    </li>
                  ))}
                </ol>
                {lines.length === 0 && (
                  <p className="text-[11px] text-on-surface-variant">
                    This record carries no history — it was written by an earlier version.
                  </p>
                )}
              </>
            )}
          </div>
        )}
      </div>

      <Button
        variant="danger" size="sm" className="w-full"
        onClick={() => onKill(job.queryId)}
        loading={killing}
        disabled={killing || isTerminalStatus(job.status)}
        icon={killing ? undefined : 'cancel'}
      >
        {killing ? 'Killing…' : 'Kill Job'}
      </Button>
    </Card>
  );
};

export default FlinkJobCard;
