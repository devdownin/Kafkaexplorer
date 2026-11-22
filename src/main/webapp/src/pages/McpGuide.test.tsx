// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import McpGuide from './McpGuide';

const LocationProbe = () => {
  const location = useLocation();
  return <output data-testid="guide-url">{location.search}</output>;
};

const renderGuide = (url = '/mcp/guide') => render(
  <MemoryRouter initialEntries={[url]}>
    <McpGuide />
    <LocationProbe />
  </MemoryRouter>,
);

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
  it('restaure un parcours partagé, son nœud et un focus depuis les paramètres URL', async () => {
    const user = userEvent.setup();
    renderGuide('/mcp/guide?scenario=skill&step=evidence&focus=evidence');
    expect(screen.getByRole('button', { name: 'Découverte d’un Skill' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Étape 8 : Manifeste vérifié/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Preuves' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Gouvernance' }));
    expect(screen.getByRole('button', { name: /Étape 4 : Revue & droits/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('guide-url')).toHaveTextContent('focus=guard');
    expect(screen.getByTestId('guide-url')).toHaveTextContent('step=policy');
  });

  it('donne accès aux neuf étapes par la piste rapide et distingue le retour des preuves', async () => {
    const user = userEvent.setup();
    renderGuide('/mcp/guide?scenario=forecast&step=evidence&focus=evidence');
    const rail = screen.getByRole('group', { name: 'Accès rapide aux étapes' });
    const selectedRailStep = within(rail).getByRole('button', { name: /08.*Historique ≠ futur/ });
    expect(selectedRailStep).toHaveAttribute('aria-current', 'step');
    expect(screen.getByText(/Retour et restitution/)).toBeInTheDocument();
    await user.click(within(rail).getByRole('button', { name: /04.*Tool controls/ }));
    expect(screen.getByTestId('guide-url')).toHaveTextContent('step=policy');
    expect(screen.getByText(/Demande et invocation/)).toBeInTheDocument();
  });

  it('reste lisible en liste sur mobile et navigable au clavier sur le graphe', async () => {
    const user = userEvent.setup();
    renderGuide();
    await user.click(screen.getByRole('button', { name: /Choisir le point 7 : Trace & Kafka/ }));
    expect(screen.getByRole('button', { name: /Étape 7 : Trace & Kafka/ })).toHaveAttribute('aria-pressed', 'true');

    const server = screen.getByRole('button', { name: /Étape 6 : kex_trace_key/ });
    server.focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: /Étape 7 : Trace & Kafka/ })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('button', { name: /Étape 1 : Question métier/ })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Zoom arrière' }));
    expect(screen.getByLabelText('Zoom actuel')).toHaveTextContent('75%');
    await user.click(screen.getByRole('button', { name: 'Réinitialiser le zoom' }));
    expect(screen.getByLabelText('Zoom actuel')).toHaveTextContent('100%');
  });
});
