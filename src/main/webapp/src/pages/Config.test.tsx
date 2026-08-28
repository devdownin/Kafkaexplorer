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
    await user.click(screen.getByRole('radio', { name: /ollama/i }));

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

    await user.click(screen.getByRole('radio', { name: /ollama/i }));

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

/*
 * « Test connection » **enregistre**, et c'est la seule chose qu'il puisse faire : `POST
 * /api/config` est le seul chemin qui repointe le cluster, et une adresse de courtier prise dans le
 * corps d'une requête serait la contrefaçon de requête côté serveur que `test-llm` refuse par
 * construction. Ce qui était faux n'était donc pas le geste, mais ce qu'il en disait.
 */
describe('Config — applying and testing the connection', () => {
  it('reports a refusal with the server’s reason, not as a connection failure', async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { message: "Not applied: mode is 'SASL_SSL', which is not one of PLAIN, SSL, CONFLUENT_CLOUD." } },
    });
    mockedAxios.isAxiosError.mockImplementation((e: unknown) =>
      !!e && typeof e === 'object' && 'isAxiosError' in e);
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /test connection/i }));

    expect(await screen.findByText(/is not one of PLAIN/)).toBeInTheDocument();
    expect(screen.queryByText(/connection failed/i)).not.toBeInTheDocument();
  });

  /*
   * L'enregistrement réussi ne mettait pas `savedRef` à jour : la page affichait « Unsaved
   * changes » et sa garde de sortie annonçait « ces réglages n'ont pas été appliqués » à propos de
   * réglages qu'elle venait d'appliquer *et* d'écrire sur disque.
   */
  it('does not go on claiming unsaved changes once it has applied them', async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockResolvedValue({ data: { ...serverConfig, isConnected: true } });
    renderPage();
    const field = await screen.findByDisplayValue('localhost:9092');

    await user.clear(field);
    await user.type(field, 'broker:9092');
    expect(screen.getByText(/unsaved changes/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /test connection/i }));

    await waitFor(() => expect(screen.queryByText(/unsaved changes/i)).not.toBeInTheDocument());
  });
});

describe('Config — what the page says about the connection', () => {
  /*
   * « Not connected » recouvre un courtier arrêté, une adresse qui ne pointe sur rien et un client
   * que le cluster refuse : trois causes, trois corrections, et c'est cet écran qui porte
   * l'adresse. Le serveur donne la raison depuis `pingDetail` ; la page lisait le booléen.
   */
  it('says why the broker did not answer', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({
          data: { ...serverConfig, isConnected: false, connectionError: 'No answer within 2000 ms' },
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    expect(await screen.findByText(/No answer within 2000 ms/)).toBeInTheDocument();
  });

  it('says nothing of the sort when the broker answers', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, isConnected: true, connectionError: null } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    await screen.findByText(/^Connected$/);
    expect(screen.queryByText(/No answer within/)).not.toBeInTheDocument();
  });
});

describe('Config — the LLM configuration in force', () => {
  /*
   * `llmConfigurationProblem` est servi et rendu par Process Mining depuis toujours ; la page qui
   * porte le réglage à changer se taisait.
   */
  it('names what stops the deployment calling a model', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({
          data: { ...serverConfig, llmConfigurationProblem: 'No API key is configured (claude.api-key).' },
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    expect(await screen.findByText(/No API key is configured/)).toBeInTheDocument();
  });

  /* `AUTO` décline pour un point d'accès inconnu : le réglage seul ne dit pas si un schéma part. */
  it('says whether a schema really travels with each request', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, llmStructuredOutputActive: false } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    expect(await screen.findByText(/not constrained by a JSON schema/)).toBeInTheDocument();
  });

  it('shows nothing about it when the server did not say', async () => {
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');
    expect(screen.queryByTestId('llm-in-force')).not.toBeInTheDocument();
  });
});

describe('Config — a verdict describes what was tried', () => {
  /*
   * Une sonde ne valide que ce qu'elle envoie. Un chemin de keystore manquant refusait « Test
   * LLM » — une erreur sans rapport avec le geste — et emportait le focus vers un champ Kafka.
   */
  it('does not refuse an LLM probe over an unrelated Kafka field', async () => {
    const user = userEvent.setup();
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, mode: 'SSL', truststorePath: '', keystorePath: '' } });
      }
      if (url === '/api/config/llm-models') return Promise.resolve({ data: shortlist });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /test llm/i }));

    await waitFor(() => expect(
      mockedAxios.post.mock.calls.map(c => c[0])).toContain('/api/config/test-llm'));
    expect(screen.queryByText(/Truststore path is required/)).not.toBeInTheDocument();
  });

  /* Un verdict porte sur ce qui a été essayé, pas sur ce qui est à l'écran depuis. */
  it('drops the LLM verdict once the model has changed', async () => {
    const user = userEvent.setup();
    renderPage();
    const field = await screen.findByDisplayValue('openai/gpt-4o-mini');

    await user.click(screen.getByRole('button', { name: /test llm/i }));
    await screen.findByText(/Candidate reachable/);

    await user.type(field, 'x');

    expect(screen.queryByText(/Candidate reachable/)).not.toBeInTheDocument();
  });
});

describe('Config — taking back a stored setting', () => {
  const stored = {
    settingsPersisted: true,
    settingsStoreSecrets: true,
    settingsStorePath: '/app/data/settings.json',
    settingsStoredFields: ['bootstrapServers', 'llmModel'],
  };

  beforeEach(() => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') return Promise.resolve({ data: { ...serverConfig, ...stored } });
      if (url === '/api/config/llm-models') return Promise.resolve({ data: shortlist });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
  });

  /*
   * Le serveur envoyait un *nombre*, lu par personne : « 5 réglages sont conservés » ne se corrige
   * pas, là où « l'adresse du courtier est conservée » se corrige.
   */
  it('names the settings the file speaks for', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: /Bootstrap servers/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /LLM model/ })).toBeInTheDocument();
  });

  it('releases one, and says the running application did not move', async () => {
    const user = userEvent.setup();
    mockedAxios.delete.mockResolvedValue({
      data: { ...stored, settingsStoredFields: ['llmModel'], forgotten: ['bootstrapServers'] },
    });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Bootstrap servers/ }));
    await user.click(await screen.findByRole('button', { name: /^forget$/i }));

    await waitFor(() => expect(mockedAxios.delete).toHaveBeenCalledWith(
      '/api/config/stored', { params: { field: 'bootstrapServers' } }));
    expect(await screen.findByText(/Nothing changed in the running application/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Bootstrap servers/ })).not.toBeInTheDocument();
  });

  /* Le geste est contre-intuitif dans un sens précis, et le dialogue est ce qui le dit. */
  it('says what forgetting does not do, before doing it', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Bootstrap servers/ }));

    expect(await screen.findByText(/does not change what this application is connected to/))
      .toBeInTheDocument();
  });

  it('does nothing when the confirmation is declined', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /Bootstrap servers/ }));
    await user.click(await screen.findByRole('button', { name: /cancel/i }));

    expect(mockedAxios.delete).not.toHaveBeenCalled();
  });

  /* Un fichier qu'on n'a pas pu réécrire n'est pas un oubli : les réglages sont toujours là. */
  it('says the settings are still stored when the file could not be rewritten', async () => {
    const user = userEvent.setup();
    mockedAxios.delete.mockResolvedValue({
      data: { ...stored, forgotten: [], forgetError: 'Permission denied' },
    });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /LLM model/ }));
    await user.click(await screen.findByRole('button', { name: /^forget$/i }));

    expect(await screen.findByText(/Still stored: Permission denied/)).toBeInTheDocument();
  });

  it('offers nothing to release on a deployment that keeps nothing', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, settingsPersisted: false } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();

    await screen.findByDisplayValue('openai/gpt-4o-mini');
    expect(screen.queryByRole('button', { name: /forget all saved settings/i })).not.toBeInTheDocument();
  });
});

/*
 * Trois réglages que le formulaire renvoyait au serveur sans jamais les montrer. Celui qui compte
 * est la politique de collecte : c'est le seul endroit de cette application où une affirmation de
 * confidentialité cesse d'être un avertissement, parce qu'OpenRouter l'impose au routage.
 */
describe('Config — decoding and routing', () => {
  const openRouting = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(screen.getByText(/decoding and routing/i));
  };

  it('offers the structured-output contract the server already accepted', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await openRouting(user);

    expect(screen.getByRole('group', { name: /structured output/i })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /^Auto/ })).toBeChecked();
  });

  it('sends the chosen contract on save', async () => {
    const user = userEvent.setup();
    mockedAxios.post.mockResolvedValue({ data: serverConfig });
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');

    await openRouting(user);
    await user.click(screen.getByRole('radio', { name: /^Off/ }));
    await user.click(screen.getByRole('button', { name: /save configuration/i }));

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalled());
    const call = mockedAxios.post.mock.calls.find(c => c[0] === '/api/config');
    expect((call?.[1] as Record<string, unknown>).llmStructuredOutput).toBe('OFF');
  });

  /*
   * `llmDataRetentionRefused` est calculé côté serveur, donc passer à ALLOW dans le formulaire
   * laisserait le bandeau annoncer « aucune rétention » jusqu'à l'enregistrement — le mensonge
   * exact que ce repère existe pour empêcher, sur la seule phrase de cette page qui engage
   * quelque chose.
   */
  it('says the privacy banner is stale once the routing policy has been changed', async () => {
    const user = userEvent.setup();
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({
          data: { ...serverConfig, llmOpenrouterDataCollection: 'DENY', llmDataRetentionRefused: true },
        });
      }
      if (url === '/api/config/llm-models') return Promise.resolve({ data: shortlist });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');
    expect(screen.queryByText(/not the provider selected above|Save to apply/i)).not.toBeInTheDocument();

    await openRouting(user);
    await user.click(screen.getByRole('radio', { name: /allow any provider/i }));

    expect(await screen.findByText(/Save to apply/i)).toBeInTheDocument();
  });

  /* Ce que la restriction coûte, dit là où on la choisit. */
  it('states what refusing retention costs, and what allowing it costs', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByDisplayValue('openai/gpt-4o-mini');
    await openRouting(user);

    expect(screen.getByText(/stops being routable/)).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: /allow any provider/i }));

    expect(await screen.findByText(/may be used for training/)).toBeInTheDocument();
  });

  /* Le routage est celui d'OpenRouter : il n'a pas de sens ailleurs, et il n'est pas proposé. */
  it('offers no routing policy for a provider that has none', async () => {
    const user = userEvent.setup();
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/config') {
        return Promise.resolve({ data: { ...serverConfig, llmProvider: 'OLLAMA', llmModel: 'qwen3:4b' } });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    renderPage();
    await screen.findByDisplayValue('qwen3:4b');

    await openRouting(user);

    expect(screen.getByRole('group', { name: /structured output/i })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /data collection/i })).not.toBeInTheDocument();
  });
});
