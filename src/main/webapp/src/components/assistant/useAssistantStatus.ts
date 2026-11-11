// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useState } from 'react';
import axios from 'axios';
import type { AssistantStatus } from '../../api/types';

/**
 * L'état de l'assistant, lu une fois au montage.
 *
 * `null` tant que rien n'a répondu, et aussi quand la lecture échoue : l'assistant est un ajout à
 * la page, et une page dont le serveur ne connaît pas `/api/assistant` (une version plus ancienne
 * derrière le même front) doit rester exactement celle d'avant, sans bouton ni bandeau d'erreur.
 */
export function useAssistantStatus(): AssistantStatus | null {
  const [status, setStatus] = useState<AssistantStatus | null>(null);
  useEffect(() => {
    let alive = true;
    axios.get<AssistantStatus>('/api/assistant/status')
      .then(res => { if (alive && res?.data && typeof res.data.enabled === 'boolean') setStatus(res.data); })
      .catch(() => { /* voir ci-dessus : l'absence d'assistant n'est pas une erreur de la page */ });
    return () => { alive = false; };
  }, []);
  return status;
}
