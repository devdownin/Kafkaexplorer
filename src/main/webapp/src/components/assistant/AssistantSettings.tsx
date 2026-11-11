// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState } from 'react';
import axios from 'axios';
import type { LlmTestResponse } from '../../api/types';
import { Badge, Button } from '../ui';
import { useAssistantStatus } from './useAssistantStatus';
import {
  describeAssistantEffective, describeAssistantHelp, describeContextWindow,
} from '../../pages/sqlAssistant';

export interface AssistantSettingsProps {
  /** `GET /api/config` : la fenêtre déclarée et le plafond qu'elle impose, partagés par les deux. */
  contextWindowTokens?: number | null;
  promptCharCeiling?: number | null;
}

/**
 * Le second consommateur du modèle, dans la page qui règle le premier.
 *
 * Réglages ne montrait et ne testait que `claude.*` ; l'assistant peut appeler un autre modèle
 * (`explorer.assistant.*`), et rien ici ne le disait — un test vert pouvait décrire un modèle que
 * l'assistant n'appelle pas. Ce bloc dit ce qu'il appelle réellement, avec quel délai et quel
 * budget, et le teste par sa propre route. Lecture seule : ses réglages se posent au déploiement.
 */
export const AssistantSettings: React.FC<AssistantSettingsProps> = ({ contextWindowTokens, promptCharCeiling }) => {
  const status = useAssistantStatus();
  const help = describeAssistantHelp(status);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);

  const test = async () => {
    setTesting(true);
    setResult(null);
    // Le délai du serveur, plus une marge : la même règle que le test de `claude.*` au-dessus.
    const waitMs = ((status?.requestTimeoutSeconds ?? 60) + 15) * 1000;
    try {
      const res = await axios.post<LlmTestResponse>('/api/assistant/test', null, { timeout: waitMs });
      setResult({ ok: res.data.ok, message: res.data.message });
    } catch (err: unknown) {
      setResult({ ok: false, message: err instanceof Error ? err.message : 'The test did not answer.' });
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="mt-5 pt-4 border-t border-outline-variant/60 space-y-2 text-[12px]" data-testid="assistant-settings">
      <h3 className="text-[12.5px] font-semibold text-on-surface flex items-center gap-1.5">
        <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-primary">smart_toy</span>
        SQL &amp; metric assistant
      </h3>
      <p className="text-on-surface-variant">{describeContextWindow(contextWindowTokens, promptCharCeiling)}</p>
      {!status && (
        <p className="text-on-surface-variant">This server does not report an assistant.</p>
      )}
      {status && help && (
        <>
          <div className="flex items-center gap-2 flex-wrap">
            <Badge tone={help.tone} dot>{help.label}</Badge>
            <span className="text-on-surface-variant break-words">{describeAssistantEffective(status)}</span>
          </div>
          <p className="text-on-surface-variant break-words">{help.detail}</p>
          {help.howToEnable && <p className="font-mono text-[11.5px] text-on-surface-variant break-words">{help.howToEnable}</p>}
          <div className="flex items-center gap-3 flex-wrap">
            <Button type="button" size="sm" variant="outline" icon={testing ? undefined : 'network_check'}
              loading={testing} disabled={testing} onClick={() => void test()}>
              {testing ? 'Testing the assistant model…' : 'Test the assistant model'}
            </Button>
            {result && (
              <span role="status" className={`break-words ${result.ok ? 'text-success' : 'text-error'}`}>{result.message}</span>
            )}
          </div>
        </>
      )}
    </div>
  );
};
