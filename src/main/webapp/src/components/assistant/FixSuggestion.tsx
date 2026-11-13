// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import axios from 'axios';
import type { AssistantSqlRequest, AssistantSqlResponse } from '../../api/types';
import { Button } from '../ui';
import { ASSISTANT_REQUEST_TIMEOUT_MS, failedResponse } from '../../pages/sqlAssistant';
import { extractApiErrorMessage } from '../../pages/queryError';
import { ProposalCard } from './ProposalCard';

export interface FixSuggestionProps {
  /** L'instruction refusée, telle qu'elle a été envoyée. */
  sql: string;
  /** Le message du planificateur, brut : la position qu'il porte est ce que le modèle doit lire. */
  error: string;
  onOpen: (response: AssistantSqlResponse) => void;
}

/**
 * « Proposer une correction », dans le panneau d'erreur de l'éditeur.
 *
 * Le premier usage de l'assistant et le plus borné : modifier, pas inventer, à partir d'un message
 * qui porte déjà sa ligne et sa colonne — et le seul dont la réussite se décide sans humain. La
 * correction s'affiche à côté de l'original, et s'ouvre dans un nouvel onglet sur un geste.
 */
export const FixSuggestion: React.FC<FixSuggestionProps> = ({ sql, error, onOpen }) => {
  const [pending, setPending] = useState(false);
  const [response, setResponse] = useState<AssistantSqlResponse | null>(null);

  const ask = async () => {
    setPending(true);
    try {
      const body: AssistantSqlRequest = { mode: 'FIX', question: null, sql, error, tables: null };
      const res = await axios.post<AssistantSqlResponse>('/api/assistant/sql', body,
        { timeout: ASSISTANT_REQUEST_TIMEOUT_MS });
      setResponse(res.data);
    } catch (e) {
      setResponse(failedResponse(extractApiErrorMessage(e, 'The assistant did not answer')));
    } finally {
      setPending(false);
    }
  };

  if (response) {
    return (
      <div className="mt-3">
        <ProposalCard
          response={response}
          original={sql}
          actionLabel="Open the fix in a new tab"
          onUse={() => onOpen(response)}
          onDismiss={() => setResponse(null)}
        />
      </div>
    );
  }
  return (
    <div className="mt-2">
      <Button size="sm" variant="outline" icon="smart_toy" loading={pending} disabled={pending} onClick={() => void ask()}>
        {pending ? 'Asking the assistant…' : 'Suggest a fix'}
      </Button>
    </div>
  );
};
