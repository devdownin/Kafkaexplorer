// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Ce que devient le contenu des messages une fois envoyé au modèle — dit à l'écran, dans les
 * quatre cas où la réponse diffère, et jamais plus fort que ce qui est vérifiable.
 *
 * Les deux pages qui appellent un modèle posaient la question à moitié. Réglages ne parlait que
 * quand la nouvelle était bonne : `DENY` affichait sa restriction, `ALLOW` retombait sur la phrase
 * générique « inférence distante » — donc le seul réglage qui *élargit* l'exposition était le seul
 * à ne rien afficher. Process Mining, la page où le contenu part réellement, n'en disait rien du
 * tout : « les digests sont envoyés à ce point d'accès » et rien sur ce qu'il en fait.
 *
 * Deux règles tiennent ce module. D'abord, une politique n'est affirmée que là où elle est
 * *applicable* : sur OpenRouter elle est imposée au routage, donc on peut en parler ; sur Anthropic,
 * une passerelle quelconque ou un Ollama distant, cette application n'a aucun moyen de l'imposer ni
 * de l'observer, et le dire relèverait de l'invention. Ensuite, l'inconnu se dit, il ne se tait pas
 * — c'est aussi la position d'OpenRouter, qui suppose rétention *et* entraînement quand la
 * politique d'un fournisseur ne peut être établie.
 *
 * Ce module décrit ce que le déploiement **impose**, pas ce qu'un modèle **déclare** : voir la note
 * de `docs/LLM-PROVIDERS.md`.
 */

/** Les faits que `GET /api/config` rapporte et dont dépend la phrase. */
export interface LlmPolicyFacts {
  llmProvider?: string;
  llmLocalDeployment?: boolean;
  /** Vrai seulement là où le routage a pu être restreint — donc OpenRouter avec `DENY`. */
  llmDataRetentionRefused?: boolean;
}

export type PolicyTone = 'local' | 'restricted' | 'open' | 'unenforceable';

export interface LlmPolicy {
  tone: PolicyTone;
  /** Étiquette courte, telle quelle dans une puce. */
  label: string;
  /** La phrase complète : ce qui part, et ce qu'il en advient. */
  detail: string;
}

/**
 * `null` tant que rien n'a été chargé : avant que `/api/config` réponde, toute phrase serait une
 * affirmation non vérifiée — la même règle que la pastille de connexion, qui affiche « Connexion… »
 * plutôt qu'un vert optimiste.
 */
export const describeDataPolicy = (facts: LlmPolicyFacts | null): LlmPolicy | null => {
  if (!facts) return null;

  if (facts.llmLocalDeployment) {
    return {
      tone: 'local',
      label: 'Stays on this host',
      detail: 'The endpoint is a loopback address, so no message content leaves this machine.',
    };
  }

  if (facts.llmDataRetentionRefused) {
    return {
      tone: 'restricted',
      label: 'No retention',
      detail: 'Message digests leave this host, and routing is restricted to providers that do not '
        + 'retain or train on them — OpenRouter enforces that, so a provider which does cannot serve '
        + 'these requests.',
    };
  }

  if (facts.llmProvider === 'OPENROUTER') {
    return {
      tone: 'open',
      label: 'Retention allowed',
      detail: 'Message digests leave this host and routing is unrestricted, so whichever provider '
        + 'serves a request may retain what it is sent. Set claude.openrouter-data-collection to '
        + 'DENY to exclude those providers.',
    };
  }

  return {
    tone: 'unenforceable',
    label: 'Governed by the endpoint',
    detail: 'Message digests leave this host. What the endpoint does with them is governed by its '
      + 'own terms — this application cannot enforce or observe a retention policy here.',
  };
};
