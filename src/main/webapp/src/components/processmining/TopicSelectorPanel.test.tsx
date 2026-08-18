// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage du sélecteur de topics de Process Mining, qui n'avait aucun test.
 *
 * `topicSelection.test.ts` couvre la logique partagée ; ce qui reste propre à ce panneau est
 * qu'il étend les motifs sur **sa propre** liste de topics — il interroge `/api/dashboard`
 * lui-même plutôt que de lire le catalogue — et que ce qu'il ajoute part bien dans `onStart`.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const TOPICS = [
  'demo.orders.1.received', 'demo.orders.2.validated',
  'demo.payments.authorized', 'internal.audit.history',
];

async function renderPanel(onStart = vi.fn()) {
  const { default: TopicSelectorPanel } = await import('./TopicSelectorPanel');
  const { ToastProvider } = await import('../Toast');
  const view = render(
    <ToastProvider>
      <TopicSelectorPanel onStart={onStart} />
    </ToastProvider>,
  );
  // La liste arrive de la requête que le panneau lance lui-même.
  await screen.findByText('demo.orders.1.received');
  return { ...view, onStart };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedAxios.get.mockResolvedValue({ data: { topics: TOPICS } });
});

describe('TopicSelectorPanel', () => {
  it('expands a pattern over the topics it loaded, and starts with them', async () => {
    const user = userEvent.setup();
    const { onStart } = await renderPanel();

    await user.type(
      screen.getByLabelText('Add topics by name, list or pattern'),
      'demo.orders.*{Enter}');

    await waitFor(() => expect(
      screen.getByRole('checkbox', { name: /demo.orders.1.received/ })).toBeChecked());
    expect(screen.getByRole('checkbox', { name: /demo.orders.2.validated/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /demo.payments.authorized/ })).not.toBeChecked();

    await user.click(screen.getByRole('button', { name: /Start Profiling/ }));
    expect(onStart).toHaveBeenCalledWith(
      ['demo.orders.1.received', 'demo.orders.2.validated'],
      expect.objectContaining({ mode: 'LATEST_N' }),
    );
  });

  it('takes a pasted list in one gesture', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(
      screen.getByLabelText('Add topics by name, list or pattern'),
      'demo.orders.1.received, demo.payments.authorized{Enter}');

    await waitFor(() => expect(screen.getByText('2 topics selected')).toBeInTheDocument());
  });

  it('reports a pattern that matches nothing rather than selecting anything', async () => {
    const user = userEvent.setup();
    await renderPanel();

    await user.type(
      screen.getByLabelText('Add topics by name, list or pattern'),
      'nope.*{Enter}');

    expect(await screen.findByText(/no topic matches nope\.\*/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Start Profiling/ })).toBeDisabled();
  });

  it('applies no cap — a profiling run is bounded by its prompt budget, not by a topic count', async () => {
    const user = userEvent.setup();
    const many = Array.from({ length: 60 }, (_, i) => `t${i}`);
    mockedAxios.get.mockResolvedValue({ data: { topics: many } });

    const { default: TopicSelectorPanel } = await import('./TopicSelectorPanel');
    const { ToastProvider } = await import('../Toast');
    render(<ToastProvider><TopicSelectorPanel onStart={vi.fn()} /></ToastProvider>);
    await screen.findByText('t0');

    await user.type(screen.getByLabelText('Add topics by name, list or pattern'), '*{Enter}');

    await waitFor(() => expect(screen.getByText('60 topics selected')).toBeInTheDocument());
  });
});
