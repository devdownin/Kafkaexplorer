// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import axios from 'axios';
import type { AssistantMetricRequest, AssistantMetricResponse, AssistantStatus } from '../../api/types';
import { Button, Textarea } from '../ui';
import { ASSISTANT_REQUEST_TIMEOUT_MS, failedResponse, privacyNote } from '../../pages/sqlAssistant';
import { extractApiErrorMessage } from '../../pages/queryError';
import { ProposalCard } from './ProposalCard';

export interface MetricDraftAssistProps {
  status: AssistantStatus;
  /** Le topic choisi dans le formulaire, décrit au modèle en premier. */
  topic: string;
  /** Pré-remplit le formulaire ; l'aperçu existant tranche, l'enregistrement reste un geste. */
  onApply: (draft: AssistantMetricResponse) => void;
}

/**
 * « Décrire la métrique » dans l'éditeur de métrique.
 *
 * L'assistant traduit une demande de l'utilisateur ; il ne propose rien de lui-même — c'est le
 * panneau « KPI proposés » qui le fait, adossé à une observation. Aucun seuil n'est proposé.
 */
export const MetricDraftAssist: React.FC<MetricDraftAssistProps> = ({ status, topic, onApply }) => {
  const [description, setDescription] = useState('');
  const [pending, setPending] = useState(false);
  const [draft, setDraft] = useState<AssistantMetricResponse | null>(null);
  const note = privacyNote(status);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const text = description.trim();
    if (!text || pending) return;
    setPending(true);
    try {
      const body: AssistantMetricRequest = { description: text, topic: topic.trim() || null };
      const res = await axios.post<AssistantMetricResponse>('/api/assistant/metric', body,
        { timeout: ASSISTANT_REQUEST_TIMEOUT_MS });
      setDraft(res.data);
    } catch (error) {
      setDraft({
        proposal: failedResponse(extractApiErrorMessage(error, 'The assistant did not answer')),
        name: null, type: 'GAUGE', description: null,
      });
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="space-y-2 rounded-lg border border-outline-variant/60 p-3">
      <form onSubmit={submit} className="space-y-2" aria-label="Metric assistant">
        <label htmlFor="metric-assistant-description"
          className="flex items-center gap-1 text-[12px] font-medium text-on-surface-variant">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">smart_toy</span>
          Describe the metric
        </label>
        <Textarea
          id="metric-assistant-description"
          rows={2}
          value={description}
          onChange={e => setDescription(e.target.value)}
          placeholder="e.g. orders in the CANCELLED state over the last five minutes"
          maxLength={2000}
        />
        <Button type="submit" size="sm" variant="secondary" loading={pending} disabled={!description.trim() || pending}>
          Draft
        </Button>
        {note && <p className="text-[11px] text-outline">{note}</p>}
      </form>
      {draft && (
        <ProposalCard
          response={draft.proposal}
          actionLabel="Fill the form"
          onUse={() => { onApply(draft); setDraft(null); }}
          onDismiss={() => setDraft(null)}
        />
      )}
    </div>
  );
};
