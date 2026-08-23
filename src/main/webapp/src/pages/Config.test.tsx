// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import Config from './Config';
import { ToastProvider } from '../components/Toast';
import { ConfirmProvider } from '../components/ui';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

/**
 * Ce que ce fichier tient, et c'est le cœur du changement : **essayer un modèle n'engage à rien**.
 *
 * `handleTestLlm` commençait par `POST /api/config`, donc une sonde repointait le déploiement en
 * cours et, la persistance active, l'écrivait sur disque. Explorer et s'engager étaient le même
 * geste. Un test de rendu est le seul endroit où cette absence de requête se vérifie.
 */

const serverConfig = {
  bootstrapServers: 'localhost:9092',
  mode: 'PLAIN',
  llmProvider: 'OPENROUTER',
  llmBaseUrl: 'https://openrouter.ai/api/v1',
  llmModel: 'openai/gpt-4o-mini',
  llmMaxTokens: 4096,
  llmSnapshotWindowSize: 100,
  llmSnapshotWindowTimeoutSeconds: 30,
  llmApiKeyConfigured: true,
  llmProviderDefaults: {
    OPENROUTER: { baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4o-mini' },
    OLLAMA: { baseUrl: 'http://localhost:11434/v1', model: 'qwen3:4b' },
    ANTHROPIC: { baseUrl: 'https://api.anthropic.com', model: 'claude-3-5-sonnet-20241022' },
    OPENAI_COMPATIBLE: { baseUrl: '', model: '' },
    SPECTRA: { baseUrl: 'http://localhost:8080', model: '' },
  },
};

const shortlist = {
  available: true,
  criteria: ['emits text', 'supports structured outputs', 'cheapest first'],
  error: null,
  models: [
    {
      id: 'openai/gpt-4o-mini', name: 'GPT-4o mini', contextLength: 128000,
      schemaSupport: 'CONSTRAINED', reasoningMandatory: null,
      promptPriceUsdPerMillion: 0.15, completionPriceUsdPerMillion: 0.6,
      projectedCostUsd: 0.0069,
    },
    {
      id: 'some/cheap-model', name: 'Cheap', contextLength: 64000,
      schemaSupport: 'ACCEPTED_UNCONSTRAINED', reasoningMandatory: true,
      promptPriceUsdPerMillion: 0.02, completionPriceUsdPerMillion: 0.05,
      projectedCostUsd: 0.0008,
    },
  ],
};

/*
 * Un data router, pas un `MemoryRouter` : la page appelle `useUnsavedGuard`, donc `useBlocker`,
 * qui n'existe que sur un routeur de données — c'est la même contrainte que pour les autres pages
 * testées ici.
 */
const renderPage = () => {
  const router = createMemoryRouter([{ path: '/config', element: <Config /> }],
    { initialEntries: ['/config'] });
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={router} />
      </ConfirmProvider>
    </ToastProvider>,
  );
};

beforeEach(() => {
  localStorage.clear();
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/config') return Promise.resolve({ data: serverConfig });
    if (url === '/api/config/llm-models') return Promise.resolve({ data: shortlist });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
  mockedAxios.post.mockResolvedValue({
    data: { ok: true, message: 'Candidate reachable via https://openrouter.ai/api/v1.', candidate: true },
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('Config — testing a model', () => {
  it('probes without applying anything', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /test llm/i }));

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalled());
    const posted = mockedAxios.post.mock.calls.map(call => call[0]);
    expect(posted).toContain('/api/config/test-llm');
    expect(posted).not.toContain('/api/config');
  });

  it('sends the form’s own model, so an unsaved one is what gets tested', async () => {
    const user = userEvent.setup();
    renderPage();
    const field = await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.clear(field);
    await user.type(field, 'some/other-model');
    await user.click(screen.getByRole('button', { name: /test llm/i }));

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalled());
    const call = mockedAxios.post.mock.calls.find(c => c[0] === '/api/config/test-llm');
    expect(call?.[1]).toEqual({ llmModel: 'some/other-model' });
  });

  /*
   * La frontière de sécurité, vue du navigateur : ni le point d'accès ni la clé ne partent dans la
   * sonde. Le serveur les ignorerait de toute façon, mais les envoyer mettrait la clé dans un corps
   * de requête et dans les journaux d'un proxy pour rien.
   */
  it('never sends the endpoint or the API key in a probe', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /test llm/i }));

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalled());
    const call = mockedAxios.post.mock.calls.find(c => c[0] === '/api/config/test-llm');
    const body = call?.[1] as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['llmModel']);
  });

  /*
   * La sonde n'essaie que le modèle. Il faut donc le dire quand le formulaire a pris de l'avance
   * sur le fournisseur en vigueur, sinon « joignable » se lit comme un verdict sur celui qu'on
   * vient de choisir.
   */
  it('says the probe uses the connection in force once the provider has been changed', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    expect(screen.queryByText(/connection currently in force/i)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /ollama/i }));

    expect(await screen.findByText(/connection currently in force/i)).toBeInTheDocument();
  });
});

describe('Config — the model shortlist', () => {
  /*
   * Paresseuse et derrière un geste : rien dont le seul produit est un confort de formulaire ne
   * doit peser sur le chargement de la page.
   */
  it('asks for nothing until the picker is opened', async () => {
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');
    expect(mockedAxios.get.mock.calls.map(c => c[0])).not.toContain('/api/config/llm-models');
  });

  it('lists the models with their projected cost, labelled as a projection', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /browse models/i }));

    await screen.findByText('some/cheap-model');
    expect(screen.getByText(/128k ctx/)).toBeInTheDocument();
    expect(screen.getByText(/Projected from published prices/)).toBeInTheDocument();
  });

  it('puts a chosen model into the field', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /browse models/i }));
    await user.click(await screen.findByText('some/cheap-model'));

    expect(await screen.findByDisplayValue('some/cheap-model')).toBeInTheDocument();
  });

  /* Une vue filtrée présentée comme « les modèles » est le même mensonge qu'une liste tronquée. */
  it('states what the list was filtered by', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /browse models/i }));

    expect(await screen.findByText(/supports structured outputs/)).toBeInTheDocument();
  });

  it('says why the list is empty rather than showing nothing', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') return Promise.resolve({ data: serverConfig });
      if (url === '/api/config/llm-models') {
        return Promise.resolve({
          data: { available: false, models: [], criteria: [], error: 'OpenRouter answered HTTP 502.' },
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /browse models/i }));

    expect(await screen.findByText(/HTTP 502/)).toBeInTheDocument();
  });

  it('offers no picker for a provider that publishes no catalogue', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, llmProvider: 'OLLAMA', llmModel: 'qwen3:4b' } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();
    await screen.findByDisplayValue('qwen3:4b');

    expect(screen.queryByRole('button', { name: /browse models/i })).not.toBeInTheDocument();
  });
});

describe('Config — provider defaults', () => {
  /*
   * Les défauts venaient de constantes recopiées dans ce fichier. Servis par le serveur, ils ne
   * peuvent plus dériver — et c'est le passage d'un fournisseur à l'autre qui le prouve.
   */
  it('takes the suggested model from the server when switching provider', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /ollama/i }));

    expect(await screen.findByDisplayValue('qwen3:4b')).toBeInTheDocument();
  });
});

describe('Config — when the configuration cannot be read', () => {
  /*
   * Le formulaire ne se dessine pas par-dessus une réponse qu'on n'a pas eue. C'était un `catch`
   * vide : la page affichait une configuration complète sans en avoir reçu la moindre valeur —
   * l'affirmation non vérifiée que ce dépôt retire partout ailleurs, sur l'écran dont le seul
   * geste est la saisie.
   */
  it('shows the reason instead of a form it never received', async () => {
    mockedAxios.get.mockRejectedValue(new Error('Network Error'));
    renderPage();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByLabelText(/bootstrap servers/i)).not.toBeInTheDocument();
  });

  it('offers a retry that draws the form once the server answers', async () => {
    const user = userEvent.setup();
    mockedAxios.get.mockRejectedValueOnce(new Error('Network Error'));
    renderPage();
    await screen.findByRole('alert');

    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') return Promise.resolve({ data: serverConfig });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    await user.click(screen.getByRole('button', { name: /retry|try again/i }));

    expect(await screen.findByDisplayValue('openai/gpt-4o-mini')).toBeInTheDocument();
  });
});

describe('Config — saving a model', () => {
  /* La forme du slug, refusée à l'enregistrement plutôt qu'à la première fenêtre analysée. */
  it('refuses a slug with no vendor before it reaches the server', async () => {
    const user = userEvent.setup();
    renderPage();
    const field = await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.clear(field);
    await user.type(field, 'qwen3:4b');
    await user.click(screen.getByRole('button', { name: /save configuration/i }));

    expect(await screen.findByText(/has no vendor/i)).toBeInTheDocument();
    expect(mockedAxios.post.mock.calls.map(c => c[0])).not.toContain('/api/config');
  });
});

describe('Config — a key that does not follow the endpoint', () => {
  /*
   * Le serveur efface la clé quand l'hôte change ; le champ du formulaire est vide dans les deux
   * cas, donc sans phrase le prochain appel échoue sur un identifiant manquant sans que rien ne
   * relie les deux.
   */
  it('says the stored key was not carried to the new host', async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockResolvedValue({
      data: { ...serverConfig, llmApiKeyConfigured: false, credentialsCleared: ['llmApiKey'] },
    });
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /save configuration/i }));

    expect(await screen.findByText(/was not carried over/i)).toBeInTheDocument();
  });

  it('says nothing about it on an ordinary save', async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockResolvedValue({
      data: { ...serverConfig, credentialsCleared: [] },
    });
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /save configuration/i }));

    await screen.findByRole('button', { name: /saved!/i });
    expect(screen.queryByText(/was not carried over/i)).not.toBeInTheDocument();
  });
});
