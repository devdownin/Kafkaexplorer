// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import axios from 'axios';
import type { AssistantSqlRequest, AssistantSqlResponse, AssistantStatus } from '../../api/types';
import { Button, Input } from '../ui';
import { ASSISTANT_REQUEST_TIMEOUT_MS, failedResponse, privacyLine } from '../../pages/sqlAssistant';
import { extractApiErrorMessage } from '../../pages/queryError';
import { ProposalCard } from './ProposalCard';

export interface AskBarProps {
  status: AssistantStatus;
  /** Ouvre la proposition dans un nouvel onglet : la question l'accompagne, en commentaire. */
  onOpen: (response: AssistantSqlResponse, question: string) => void;
}

/**
 * « Demander une requête » au-dessus de l'éditeur.
 *
 * Un vrai `<form onSubmit>`, pour qu'Entrée soumette depuis le champ (CLAUDE.md, frontend). Le
 * résultat ne remplace jamais l'onglet en cours : il s'affiche avec son statut et ouvre un nouvel
 * onglet sur un geste, comme une requête sauvegardée.
 */
export const AskBar: React.FC<AskBarProps> = ({ status, onOpen }) => {
  const [question, setQuestion] = useState('');
  const [asked, setAsked] = useState('');
  const [pending, setPending] = useState(false);
  const [response, setResponse] = useState<AssistantSqlResponse | null>(null);
  const note = privacyLine(status);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const q = question.trim();
    if (!q || pending) return;
    setPending(true);
    setAsked(q);
    try {
      const body: AssistantSqlRequest = { mode: 'GENERATE', question: q, sql: null, error: null, tables: null };
      const res = await axios.post<AssistantSqlResponse>('/api/assistant/sql', body,
        { timeout: ASSISTANT_REQUEST_TIMEOUT_MS });
      setResponse(res.data);
    } catch (error) {
      setResponse(failedResponse(extractApiErrorMessage(error, 'The assistant did not answer')));
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="px-3 py-2 border-b border-outline-variant bg-surface-container-lowest">
      <form onSubmit={submit} className="flex items-center gap-2" aria-label="Ask the assistant for a query">
        <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-primary shrink-0">smart_toy</span>
        <Input
          value={question}
          onChange={e => setQuestion(e.target.value)}
          placeholder="Ask for a query — e.g. orders per status per minute in demo.orders"
          aria-label="Question for the SQL assistant"
          maxLength={2000}
          className="flex-1 min-w-0"
        />
        <Button type="submit" size="sm" variant="secondary" loading={pending} disabled={!question.trim() || pending}>
          Ask
        </Button>
      </form>
      {note && <p className="mt-1 text-[11px] text-outline">{note}</p>}
      {response && (
        <div className="mt-2 max-h-80 overflow-auto custom-scrollbar">
          <ProposalCard
            response={response}
            actionLabel="Open in a new tab"
            onUse={() => onOpen(response, asked)}
            onDismiss={() => setResponse(null)}
          />
        </div>
      )}
    </div>
  );
};

