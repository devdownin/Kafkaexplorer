// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le bloc de Réglages du second consommateur : il dit ce que l'assistant appelle réellement, et son
 * bouton teste ce modèle-là — pas celui de `claude.*`, qu'un test vert au-dessus décrivait seul.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const get = vi.fn();
const post = vi.fn();
vi.mock('axios', () => ({ default: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) } }));

import { AssistantSettings } from './AssistantSettings';

const STATUS = {
  enabled: true, problem: null, provider: 'Ollama', model: 'gemma4:e4b', localDeployment: false,
  baseUrl: 'http://ollama:11434/v1', requestTimeoutSeconds: 300, promptCharBudget: 12000, contextWindowTokens: 16384,
};

beforeEach(() => vi.clearAllMocks());

describe('AssistantSettings', () => {
  it('states the shared window and what the assistant really calls', async () => {
    get.mockResolvedValue({ data: STATUS });
    render(<AssistantSettings contextWindowTokens={16384} promptCharCeiling={36864} />);

    expect(screen.getByText(/Context window declared: 16,384 tokens/)).toBeInTheDocument();
    expect(await screen.findByText(/gemma4:e4b at http:\/\/ollama:11434\/v1 — 300 s per call/)).toBeInTheDocument();
  });

  it('tests the assistant\'s own model through its own route', async () => {
    get.mockResolvedValue({ data: STATUS });
    post.mockResolvedValue({ data: { ok: true, message: 'Assistant model reachable via http://ollama:11434/v1. Sample reply: OK' } });
    render(<AssistantSettings contextWindowTokens={0} promptCharCeiling={null} />);

    await userEvent.click(await screen.findByRole('button', { name: /Test the assistant model/ }));

    expect(post.mock.calls[0][0]).toBe('/api/assistant/test');
    expect(await screen.findByRole('status')).toHaveTextContent('Sample reply: OK');
  });

  it('says so when the server reports no assistant, and offers no test', async () => {
    get.mockResolvedValue({ data: {} });
    render(<AssistantSettings />);

    expect(await screen.findByText('This server does not report an assistant.')).toBeInTheDocument();
    expect(screen.getByText(/nothing checks that they fit/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
