// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import McpGuide from './McpGuide';

const renderGuide = () => render(<MemoryRouter><McpGuide /></MemoryRouter>);

describe('Guide pédagogique MCP', () => {
  it('explique les quatre primitives et présente le graphe comme une référence, pas une trace live', () => {
    renderGuide();
    for (const title of ['Skill', 'Tool', 'Resource', 'Prompt']) {
      expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
    }
    expect(screen.getByText('Référence, pas trace live')).toBeInTheDocument();
    expect(screen.getByText(/n'est pas calculé à partir d'un run/)).toBeInTheDocument();
  });

  it('change de scénario et expose les responsabilités, sources et garde-fous du nœud choisi', async () => {
    const user = userEvent.setup();
    renderGuide();
    await user.click(screen.getByRole('button', { name: 'Analyse de forecast' }));
    expect(screen.getByRole('button', { name: 'Analyse de forecast' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Étape 6 : kex_forecast_/ })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: /Étape 7 : ForecastPilot/ }));
    expect(screen.getByText(/KafkaExplorer fournit l’historique/)).toBeInTheDocument();
    expect(screen.getByText(/Les horizons, révisions du modèle/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Suivant' }));
    expect(screen.getByRole('button', { name: /Étape 8 : Historique ≠ futur/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('distingue le skill interne à Kex de sa distribution par ressources MCP', async () => {
    const user = userEvent.setup();
    renderGuide();
    await user.click(screen.getByRole('button', { name: 'Découverte d’un Skill' }));
    expect(screen.getByText(/deux mécanismes distincts/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Étape 3 : Skills de Kex/ }));
    expect(screen.getByText(/Les skills Kex PENDING ne sont pas injectés/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Étape 6 : kex:\/\/skills/ }));
    expect(screen.getByText(/n’annonce pas l’extension native skills\/list/)).toBeInTheDocument();
  });
});
