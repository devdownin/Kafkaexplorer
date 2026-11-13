// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react';
import type { AssistantSqlResponse } from '../../api/types';
import { Badge, Button } from '../ui';
import { describeProvenance, describeScope, proposalVerdict } from '../../pages/sqlAssistant';

export interface ProposalCardProps {
  response: AssistantSqlResponse;
  /** Le geste qui utilise la proposition (ouvrir un onglet, remplir le formulaire). */
  actionLabel: string;
  onUse: () => void;
  onDismiss: () => void;
  /** Ce qui précède la proposition, quand il y a quelque chose à comparer (la requête d'origine). */
  original?: string | null;
}

/**
 * Une proposition de l'assistant, toujours avec son statut.
 *
 * Il n'existe pas de rendu du SQL sans le badge qui dit si le planificateur l'a accepté : c'est la
 * règle 2 de `sqlAssistant.ts`. Et le seul geste offert l'utilise sans l'exécuter.
 */
export const ProposalCard: React.FC<ProposalCardProps> = ({ response, actionLabel, onUse, onDismiss, original }) => {
  const verdict = proposalVerdict(response);
  const scope = describeScope(response);
  const provenance = describeProvenance(response);
  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3 text-[12px]"
      role="region" aria-label="Assistant proposal">
      <div className="flex items-center gap-2 flex-wrap">
        <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-primary">smart_toy</span>
        <Badge tone={verdict.tone} dot>{verdict.label}</Badge>
        {provenance && <span className="text-on-surface-variant">{provenance}</span>}
        <button type="button" onClick={onDismiss} aria-label="Dismiss the proposal"
          className="ml-auto text-outline hover:text-on-surface transition-colors">
          <span aria-hidden="true" className="material-symbols-outlined text-sm">close</span>
        </button>
      </div>
      {verdict.detail && (
        <p className="mt-1.5 text-on-surface-variant break-words">{verdict.detail}</p>
      )}
      {response.explanation && <p className="mt-1.5 text-on-surface break-words">{response.explanation}</p>}
      {response.sql && (
        <div className={original ? 'mt-2 grid gap-2 md:grid-cols-2' : 'mt-2'}>
          {original && (
            <div className="min-w-0">
              <p className="text-[10px] uppercase tracking-wide text-outline mb-1">Before</p>
              <pre className="font-mono text-[11px] whitespace-pre-wrap break-words bg-surface-container p-2 rounded border border-outline-variant max-h-40 overflow-auto">{original}</pre>
            </div>
          )}
          <div className="min-w-0">
            {original && <p className="text-[10px] uppercase tracking-wide text-outline mb-1">Proposed</p>}
            <pre className="font-mono text-[11px] whitespace-pre-wrap break-words bg-surface-container p-2 rounded border border-outline-variant max-h-40 overflow-auto">{response.sql}</pre>
          </div>
        </div>
      )}
      {scope && <p className="mt-1.5 text-[11px] text-outline">{scope}</p>}
      {response.sql && (
        <div className="mt-2 flex items-center gap-3 flex-wrap">
          <Button size="sm" variant={response.validated ? 'primary' : 'outline'} icon="open_in_new" onClick={onUse}>
            {actionLabel}
          </Button>
          <span className="text-[11px] text-outline">
            Nothing runs until you run it. A statement the planner accepts can still answer another question — read it first.
          </span>
        </div>
      )}
    </div>
  );
};
