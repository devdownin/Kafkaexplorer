// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { describeDataPolicy } from './llmPolicy';

describe('describeDataPolicy', () => {
  // Avant la première réponse, toute phrase serait une affirmation non vérifiée.
  it('says nothing before the runtime has been read', () => {
    expect(describeDataPolicy(null)).toBeNull();
  });

  it('reads a loopback endpoint as staying on the host', () => {
    const policy = describeDataPolicy({ llmProvider: 'OLLAMA', llmLocalDeployment: true });
    expect(policy?.tone).toBe('local');
    expect(policy?.detail).toContain('leaves this machine');
  });

  /*
   * Le cas que le bandeau savait déjà dire — conservé ici pour qu'il ne se perde pas en factorisant
   * les quatre, et parce qu'il est le seul à pouvoir promettre quelque chose.
   */
  it('states the enforced restriction under DENY', () => {
    const policy = describeDataPolicy({
      llmProvider: 'OPENROUTER', llmLocalDeployment: false, llmDataRetentionRefused: true,
    });
    expect(policy?.tone).toBe('restricted');
    expect(policy?.detail).toContain('do not retain');
  });

  /*
   * Le trou que ce module existe pour combler : `ALLOW` élargit l'exposition et n'affichait rien,
   * donc l'interface ne parlait que quand la nouvelle était bonne.
   */
  it('says so when routing is left open, and names the setting', () => {
    const policy = describeDataPolicy({
      llmProvider: 'OPENROUTER', llmLocalDeployment: false, llmDataRetentionRefused: false,
    });
    expect(policy?.tone).toBe('open');
    expect(policy?.detail).toContain('may retain');
    expect(policy?.detail).toContain('claude.openrouter-data-collection');
  });

  /*
   * Ni promesse ni reproche : sur ces fournisseurs la politique existe, mais elle appartient au
   * point d'accès et rien ici ne peut l'imposer ni la constater. Prétendre le contraire serait
   * exactement l'affirmation invérifiable que la pastille de connexion a été réécrite pour retirer.
   */
  it('refuses to claim a policy where none can be enforced', () => {
    for (const llmProvider of ['ANTHROPIC', 'OPENAI_COMPATIBLE', 'SPECTRA', 'OLLAMA']) {
      const policy = describeDataPolicy({ llmProvider, llmLocalDeployment: false });
      expect(policy?.tone, llmProvider).toBe('unenforceable');
      expect(policy?.detail, llmProvider).toContain('its own terms');
    }
  });

  // `llmDataRetentionRefused` est faux partout où la question n'est pas applicable : la phrase
  // « aucune rétention » ne doit donc jamais sortir pour un autre fournisseur.
  it('never promises no-retention outside OpenRouter', () => {
    const policy = describeDataPolicy({ llmProvider: 'ANTHROPIC', llmLocalDeployment: false });
    expect(policy?.label).not.toContain('No retention');
  });
});
