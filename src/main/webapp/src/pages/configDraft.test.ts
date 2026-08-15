// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { SECRET_FIELDS, containsSecret, draftableOnly, mergeDraft } from './configDraft';

const FULL = {
  bootstrapServers: 'kafka:9092',
  mode: 'SSL',
  truststorePath: '/etc/ts.jks',
  truststorePassword: 'hunter2',
  keystorePath: '/etc/ks.jks',
  keystorePassword: 'hunter2',
  keyPassword: 'hunter2',
  confluentKey: 'AK123',
  confluentSecret: 'shhh',
  isConnected: true,
  llmProvider: 'ANTHROPIC',
  llmApiKey: 'sk-ant-secret',
  llmApiKeyConfigured: true,
  llmBaseUrl: 'https://api.anthropic.com',
  llmModel: 'claude',
  llmMaxTokens: 4096,
  llmSnapshotWindowSize: 100,
  llmSnapshotWindowTimeoutSeconds: 30,
};

describe('configDraft', () => {
  it('keeps no secret in a draft', () => {
    const draft = draftableOnly(FULL);
    for (const field of SECRET_FIELDS) {
      expect(draft).not.toHaveProperty(field);
    }
    expect(containsSecret(draft)).toBe(false);
  });

  it('keeps the fields that were actually typed', () => {
    const draft = draftableOnly(FULL);
    expect(draft.bootstrapServers).toBe('kafka:9092');
    expect(draft.mode).toBe('SSL');
    expect(draft.truststorePath).toBe('/etc/ts.jks');
    expect(draft.confluentKey).toBe('AK123');
    expect(draft.llmModel).toBe('claude');
    expect(draft.llmMaxTokens).toBe(4096);
  });

  it('drops the state the server owns', () => {
    const draft = draftableOnly(FULL);
    expect(draft).not.toHaveProperty('isConnected');
    expect(draft).not.toHaveProperty('llmApiKeyConfigured');
  });

  it('lets the draft win over the server, secrets excepted', () => {
    const server = { ...FULL };
    const merged = mergeDraft(server, { bootstrapServers: 'edited:9092' });
    expect(merged.bootstrapServers).toBe('edited:9092');
    // Le serveur reste seul détenteur des secrets et de l'état de connexion.
    expect(merged.truststorePassword).toBe('hunter2');
    expect(merged.isConnected).toBe(true);
  });

  it('ignores a secret smuggled into a draft', () => {
    const merged = mergeDraft(FULL, { llmApiKey: 'sk-ant-overwritten' } as Partial<typeof FULL>);
    expect(merged.llmApiKey).toBe('sk-ant-secret');
  });

  it('ignores keys a former version wrote', () => {
    const merged = mergeDraft(FULL, { retiredSetting: 'x' } as unknown as Partial<typeof FULL>);
    expect(merged).not.toHaveProperty('retiredSetting');
  });

  it('returns the server configuration untouched when there is no draft', () => {
    expect(mergeDraft(FULL, null)).toEqual(FULL);
  });

  it('containsSecret sees through an arbitrary object', () => {
    expect(containsSecret({ keystorePassword: 'x' })).toBe(true);
    expect(containsSecret({ bootstrapServers: 'x' })).toBe(false);
    expect(containsSecret(null)).toBe(false);
  });
});
