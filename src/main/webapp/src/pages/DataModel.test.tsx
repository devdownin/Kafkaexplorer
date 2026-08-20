// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la page Modèle de données.
 *
 * `dataModel.test.ts` couvre la logique pure — la disposition, la géométrie des arêtes, les
 * exports, la déduction du SQL. Ce qui restait sans test, c'est la page elle-même : ce qu'elle
 * envoie au serveur, ce qu'elle rend de la réponse, et ce que ses commandes font réellement. Sur
 * la PR précédente, c'est le harnais de captures d'écran qui a servi de premier lecteur — un
 * harnais qui ne tourne qu'à la main et à un seul viewport.
 *
 * `matchMedia` est bouché dans `src/test/setup.ts` et répond **false**, donc ces tests
 * s'exécutent dans la disposition étroite : les panneaux sont des tiroirs, et le sélecteur de
 * topics doit être ouvert avant d'être atteignable. C'est délibéré — la disposition qui casse en
 * silence est celle-là.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { DataModelResponse } from '../api/types';
import { readPanelOpen } from './dataModel';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

vi.mock('../catalogStore', () => ({
  useCatalog: () => ({
    topics: [
      'demo.orders.1.received', 'demo.orders.2.validated',
      'demo.payments.authorized', 'internal.audit.history',
    ],
    tables: [],
  }),
}));

const model: DataModelResponse = {
  entities: [
    {
      id: 'demo_orders_1_received',
      topic: 'demo.orders.1.received',
      format: 'JSON',
      primaryKey: 'order_id',
      messageCount: 1_240_000,
      columns: [
        { name: 'order_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
        { name: 'status', type: 'STRING', primaryKey: false, references: null, keyBase: null },
      ],
    },
    {
      id: 'demo_payments_authorized',
      topic: 'demo.payments.authorized',
      format: 'JSON',
      primaryKey: 'payment_id',
      messageCount: 1183,
      columns: [
        { name: 'payment_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
        { name: 'order_id', type: 'STRING', primaryKey: false, references: 'demo_orders_1_received', keyBase: null },
      ],
    },
  ],
  relations: [{
    from: 'demo_payments_authorized', to: 'demo_orders_1_received',
    fromColumn: 'order_id', toColumn: 'order_id',
    confidence: 'HIGH',
    reason: "'order_id' names topic 'demo.orders.1.received' and matches its key column 'order_id'.",
  }],
  warnings: [],
  topicsRequested: 2,
  topicsAnalyzed: 2,
  truncated: false,
};

function stubApi(response: DataModelResponse | Error = model) {
  mockedAxios.post.mockImplementation(() => (response instanceof Error
    ? Promise.reject(response)
    : Promise.resolve({ data: response })));
  // `GET /api/data-model/limits` : la page lit ses bornes au lieu d'en garder une copie.
  mockedAxios.get.mockResolvedValue({ data: {
    maxTopics: 100, defaultMaxTopics: 30, perTopicTimeoutMs: 20_000, inferenceThreads: 4,
  } });
}

async function renderPage(initialEntry = '/data-model') {
  const { default: DataModel } = await import('./DataModel');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  // Le routeur est rendu accessible : la page réécrit l'URL après chaque génération, et c'est
  // l'historique mémoire qu'elle écrit — `window.location` ne bouge pas sous un memory router.
  const router = createMemoryRouter(
    [{ path: '/data-model', element: <DataModel /> }, { path: '/query', element: <p>SQL editor</p> }],
    { initialEntries: [initialEntry] },
  );
  return {
    ...render(
      <ToastProvider>
        <ConfirmProvider>
          <RouterProvider router={router} />
        </ConfirmProvider>
      </ToastProvider>,
    ),
    router,
  };
}

/** Ouvre le tiroir de sélection — sous le seuil desktop, c'est la seule porte vers les topics. */
async function openTopics(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^Topics \(/ }));
}

/**
 * Bascule le bouchon `matchMedia` sur « desktop » le temps d'un test. C'est la seule disposition
 * où le panneau se replie en rail, et le repli est précisément ce qui rend sa largeur au canevas :
 * le laisser hors des tests le laisserait hors de tout contrôle.
 */
function asDesktop(): () => void {
  const original = window.matchMedia;
  window.matchMedia = (query: string) =>
    ({ ...original(query), matches: true }) as MediaQueryList;
  return () => { window.matchMedia = original; };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('DataModel page', () => {
  it('opens on an empty state that says what the page is for', async () => {
    stubApi();
    await renderPage();

    expect(await screen.findByText('No model yet')).toBeInTheDocument();
    // Rien n'est demandé au serveur tant qu'aucun topic n'est choisi.
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('is reachable in the narrow layout: the drawer is the only door, and it is on the empty state', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    // C'est là que le geste suivant est justement de choisir des topics.
    await openTopics(user);
    expect(await screen.findByLabelText('Filter topics')).toBeInTheDocument();
  });

  it('takes a pattern and expands it over the catalogue, like Stream Flow does', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    await user.type(
      screen.getByLabelText('Add topics by name, list or pattern'),
      'demo.orders.*{Enter}');

    // Les deux topics du motif sont cochés, sans requête supplémentaire : le catalogue est
    // déjà en mémoire.
    await waitFor(() => expect(
      screen.getByRole('checkbox', { name: /demo.orders.1.received/ })).toBeChecked());
    expect(screen.getByRole('checkbox', { name: /demo.orders.2.validated/ })).toBeChecked();

    await user.click(screen.getByRole('button', { name: /Generate model/ }));
    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/data-model',
      { topics: ['demo.orders.1.received', 'demo.orders.2.validated'], maxTopics: 30 },
      expect.anything(),
    ));
  });

  it('filters the checkbox list with a pattern, the syntax the field above advertises', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    await user.type(screen.getByLabelText('Filter topics'), 'demo.orders.*');

    // Les deux topics du motif restent listés…
    expect(screen.getByRole('checkbox', { name: /demo.orders.1.received/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /demo.orders.2.validated/ })).toBeInTheDocument();
    // …et rien d'autre : le filtre montre exactement ce que la saisie ajouterait.
    expect(screen.queryByRole('checkbox', { name: /demo.payments.authorized/ })).toBeNull();
  });

  it('an anchored filter that shows nothing says so, and names the form that would work', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    await user.type(screen.getByLabelText('Filter topics'), 'orders*');

    // Une liste vide se lit « aucun topic » ; ici c'est « motif ancré ».
    expect(await screen.findByText(/patterns are anchored/i)).toBeInTheDocument();
    expect(screen.getByText('*orders*')).toBeInTheDocument();
  });

  it('reports a pattern that matches nothing rather than sending it as a topic name', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    await user.type(
      screen.getByLabelText('Add topics by name, list or pattern'),
      'nope.*{Enter}');

    expect(await screen.findByText(/no topic matches nope\.\*/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Generate model/ })).toBeDisabled();
  });

  it('posts the selected topics and nothing else', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    await user.click(screen.getByRole('checkbox', { name: /demo.orders.1.received/ }));
    await user.click(screen.getByRole('button', { name: /Generate model/ }));

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/data-model',
      { topics: ['demo.orders.1.received'], maxTopics: 30 },
      expect.anything(),
    ));
  });

  /**
   * Le plafond était 30 en dur des deux côtés. Il vit maintenant dans la configuration serveur,
   * la page le lit, et le champ borne ce qu'elle envoie — sans quoi elle promettrait une
   * génération que le serveur refuserait au bout de plusieurs minutes d'inférence.
   */
  it('sends the topic budget the field is set to, bounded by the ceiling the server serves',
    async () => {
      const user = userEvent.setup();
      stubApi();
      await renderPage();

      await openTopics(user);
      const field = await screen.findByLabelText('Max topics');
      await waitFor(() => expect(field).toHaveAttribute('max', '100'));

      await user.clear(field);
      await user.type(field, '60');
      await user.tab();

      await user.click(screen.getByRole('checkbox', { name: /demo.orders.1.received/ }));
      await user.click(screen.getByRole('button', { name: /Generate model/ }));

      await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
        '/api/data-model',
        { topics: ['demo.orders.1.received'], maxTopics: 60 },
        expect.anything(),
      ));
    });

  /**
   * Le lien porte le budget. Sans lui, une génération de cinquante topics partagée s'ouvrait
   * chez son destinataire avec un champ à 30, en perdait vingt, et annonçait qu'ils étaient
   * « laissés de côté » — d'un modèle qui avait pourtant été construit sur les cinquante.
   */
  it('replays the budget a shared link was built under, not the default', async () => {
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized&max=60');

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/data-model',
      { topics: ['demo.orders.1.received', 'demo.payments.authorized'], maxTopics: 60 },
      expect.anything(),
    ));
  });

  it('writes the budget into the URL it produces, so the link replays the run', async () => {
    const user = userEvent.setup();
    stubApi();
    const { router } = await renderPage();

    await openTopics(user);
    const field = await screen.findByLabelText('Max topics');
    await waitFor(() => expect(field).toHaveAttribute('max', '100'));
    await user.clear(field);
    await user.type(field, '60');
    await user.tab();

    await user.click(screen.getByRole('checkbox', { name: /demo.orders.1.received/ }));
    await user.click(screen.getByRole('button', { name: /Generate model/ }));

    await waitFor(() => expect(router.state.location.search).toContain('max=60'));
  });

  // Deux minutes fixes suffisaient à 30 topics et plus à 100 : axios abandonnait pendant que le
  // serveur travaillait encore. L'attente est dérivée de ce que le serveur dit de son pire cas.
  it('waits longer than the fixed two minutes when the run is large enough to need it', async () => {
    const many = Array.from({ length: 60 }, (_, i) => `demo.t${i}`);
    stubApi();
    await renderPage(`/data-model?topics=${many.join(',')}&max=100`);

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalled());
    const [, , config] = mockedAxios.post.mock.calls[0] as [string, unknown, { timeout: number }];
    expect(config.timeout).toBeGreaterThan(120_000);
  });

  it('will not let the field promise more than the server allows', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    const field = await screen.findByLabelText('Max topics');
    await waitFor(() => expect(field).toHaveAttribute('max', '100'));

    await user.clear(field);
    await user.type(field, '500');
    await user.tab();

    await waitFor(() => expect(field).toHaveValue(100));
  });

  it('replays a selection carried by the URL, which is what makes a model shareable', async () => {
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/data-model',
      { topics: ['demo.orders.1.received', 'demo.payments.authorized'], maxTopics: 30 },
      expect.anything(),
    ));
    expect(await screen.findByText('2 entities · 1 relation deduced')).toBeInTheDocument();
  });

  it('draws the entities with their columns, and compacts the count without losing it', async () => {
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    expect(screen.getByText('demo_orders_1_received')).toBeInTheDocument();
    // Le compte est abrégé dans l'en-tête à largeur fixe…
    expect(screen.getByText(/1\.2M msg/)).toBeInTheDocument();
    // …et l'exact reste atteignable, ici par le nom accessible du nœud.
    expect(screen.getByRole('button', { name: /1,240,000 messages/ })).toBeInTheDocument();
  });

  it('states what the model does not cover rather than dropping it silently', async () => {
    stubApi({
      ...model,
      warnings: ["Topic 'demo.errors.poison' yielded no schema — not shown."],
      topicsRequested: 3,
    });
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized,demo.errors.poison');

    expect(await screen.findByText(/yielded no schema/)).toBeInTheDocument();
    // La couverture dit sur combien de topics porte le modèle, pas seulement ce qu'il a trouvé.
    expect(screen.getByText('2 entities (of 3 topics selected) · 1 relation deduced')).toBeInTheDocument();
  });

  it('shows the evidence of a relation, and the query it describes', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await user.click(screen.getByRole('button', { name: /^demo_payments_authorized,/ }));

    // La justification du serveur, en toutes lettres dans l'inspecteur.
    expect(await screen.findByText(/names topic 'demo.orders.1.received'/)).toBeInTheDocument();

    // Et le prédicat de jointure que la relation décrit, prêt à ouvrir dans l'éditeur.
    const link = screen.getByRole('link', { name: /Open as SQL/ });
    const sql = decodeURIComponent(link.getAttribute('href')!.split('?sql=')[1]);
    expect(sql).toContain('ON payments.order_id = orders.order_id');
    expect(sql).toContain('LIMIT 50');
  });

  it('a failure lands in a panel that keeps the reason, not a toast that fades', async () => {
    stubApi(Object.assign(new Error('Request failed'), {
      response: { status: 400, data: { message: 'Select at least one topic to build the model from.' } },
    }));
    await renderPage('/data-model?topics=demo.orders.1.received');

    expect(await screen.findByText(/Select at least one topic/)).toBeInTheDocument();
  });

  it('offers no export while there is nothing to export', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage();

    await openTopics(user);
    expect(screen.queryByRole('button', { name: 'MMD' })).toBeNull();
  });

  it('offers the three formats once a model is drawn', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    for (const format of ['SVG', 'PNG', 'MMD']) {
      expect(screen.getByRole('button', { name: format })).toBeInTheDocument();
    }
  });

  it('sets aside the entities no relation touches, and says how many are not drawn', async () => {
    const user = userEvent.setup();
    stubApi({
      ...model,
      entities: [...model.entities, {
        id: 'demo_iot_sensors', topic: 'demo.iot.sensors', format: 'JSON',
        primaryKey: null, messageCount: 7200,
        columns: [{ name: 'reading', type: 'DOUBLE', primaryKey: false, references: null, keyBase: null }],
      }],
      topicsRequested: 3, topicsAnalyzed: 3,
    });
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized,demo.iot.sensors');

    await screen.findByText('3 entities · 1 relation deduced');
    // Une entité absente du diagramme doit le dire.
    expect(screen.getByText('1 not drawn')).toBeInTheDocument();
    await openTopics(user);
    expect(screen.getByRole('button', { name: /No deduced relation \(1\)/ })).toBeInTheDocument();
  });

  /*
   * Ces entités-là occupaient une grille sous le graphe dès que l'option était cochée, et une
   * liste sans hauteur bornée sinon. Elles vivent maintenant dans une vraie liste, ouverte par
   * défaut : c'est ce qui rend au canevas la place que le diagramme réclame.
   */
  it('lists the entities it does not draw, and says why they have no edge', async () => {
    const user = userEvent.setup();
    stubApi({
      ...model,
      entities: [...model.entities, {
        id: 'demo_iot_sensors', topic: 'demo.iot.sensors', format: 'JSON',
        primaryKey: null, messageCount: 7200,
        columns: [{ name: 'reading', type: 'DOUBLE', primaryKey: false, references: null, keyBase: null }],
      }],
      topicsRequested: 3, topicsAnalyzed: 3,
    });
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized,demo.iot.sensors');

    await screen.findByText('3 entities · 1 relation deduced');
    await openTopics(user);

    const list = screen.getByRole('listbox', { name: 'Entities with no deduced relation' });
    expect(list).toBeInTheDocument();
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('demo_iot_sensors');
    // Un compte seul se lit comme une troncature ; la liste dit que c'est une réponse.
    expect(screen.getByText(/no deduced relation — listed here rather than drawn/))
      .toBeInTheDocument();

    // Et elle reste le chemin vers l'inspecteur de ces entités, qui n'ont pas de nœud à cliquer.
    await user.click(within(list).getByRole('button', { name: 'demo_iot_sensors' }));
    expect(await screen.findByRole('heading', { name: 'demo_iot_sensors' })).toBeInTheDocument();
  });

  /*
   * L'export sérialise le DOM vivant, donc il partait au calibre de l'écran — un calibre choisi
   * pour *un viewport*, qui replie les colonnes en « +N more » pour faire tenir le diagramme.
   * Un SVG n'a pas de viewport : il se zoome sans fin, et il figeait pourtant ce repli pour
   * toujours. Le fichier part maintenant au calibre le plus large.
   *
   * jsdom n'a aucune mise en page, donc le canevas mesure zéro et l'écran est au calibre par
   * défaut (12 colonnes) : une entité de 15 colonnes replie les trois dernières à l'écran et
   * doit les porter dans le fichier.
   */
  it('exports the columns the screen folds away, not the screen\'s calibre', async () => {
    const user = userEvent.setup();
    const wide = {
      ...model,
      entities: [{
        ...model.entities[0],
        columns: Array.from({ length: 15 }, (_, i) => ({
          name: `col_${i}`, type: 'STRING', primaryKey: i === 0, references: null, keyBase: null,
        })),
      }, model.entities[1]],
    };
    stubApi(wide);

    // Seulement les deux méthodes : remplacer le global `URL` lui-même casse le `new URL(...)`
    // de react-router, qui navigue au moment où le modèle répond.
    const blobs: Blob[] = [];
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn((blob: Blob) => { blobs.push(blob); return 'blob:stub'; });
    URL.revokeObjectURL = vi.fn();
    try {
      await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');
      await screen.findByText('2 entities · 1 relation deduced');

      // À l'écran, les trois dernières colonnes sont repliées.
      expect(screen.getByText('+3 more columns')).toBeInTheDocument();

      await openTopics(user);
      await user.click(screen.getByRole('button', { name: 'SVG' }));

      expect(blobs).toHaveLength(1);
      const svg = await blobs[0].text();
      expect(svg).toContain('col_14');
      expect(svg).not.toContain('more columns');

      // Et l'écran est rendu à son calibre : l'export ne laisse pas la page élargie.
      expect(screen.getByText('+3 more columns')).toBeInTheDocument();
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });

  /*
   * Le panneau coûte 256 px en permanence à la seule chose que cette page existe pour montrer.
   * Il se replie donc, et le repli suit l'opérateur d'une visite à l'autre.
   */
  it('folds the selection panel to a rail on desktop, and remembers it', async () => {
    const restore = asDesktop();
    try {
      const user = userEvent.setup();
      stubApi();
      await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');
      await screen.findByText('2 entities · 1 relation deduced');

      // Déployé, la sélection est là sans tiroir à ouvrir.
      expect(screen.getByLabelText('Filter topics')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Hide the topic selector' }));
      expect(screen.queryByLabelText('Filter topics')).not.toBeInTheDocument();
      expect(readPanelOpen(true)).toBe(false);

      await user.click(screen.getByRole('button', { name: 'Show the topic selector' }));
      expect(screen.getByLabelText('Filter topics')).toBeInTheDocument();
      expect(readPanelOpen(false)).toBe(true);
    } finally {
      restore();
    }
  });

  it('jumps to an entity chosen from the search and opens its inspector', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    await user.type(screen.getByLabelText('Jump to an entity'), 'demo_payments_authorized');

    expect(await screen.findByRole('heading', { level: 2, name: 'demo_payments_authorized' }))
      .toBeInTheDocument();
  });

  it('hides relations of a grade turned off, and says how many it hid', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    // Le modèle ne porte qu'une relation, HIGH : l'éteindre doit tout retirer du dessin.
    await user.click(screen.getByRole('checkbox', { name: /Draw high-confidence relations/ }));

    expect(await screen.findByText('1 of 1 relations hidden by the confidence filter'))
      .toBeInTheDocument();
    // La couverture décrit toujours le modèle, pas le filtre : le serveur a bien déduit 1 relation.
    expect(screen.getByText('2 entities · 1 relation deduced')).toBeInTheDocument();
  });

  it('the inspector still lists a filtered-out relation, marked rather than omitted', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    await user.click(screen.getByRole('checkbox', { name: /Draw high-confidence relations/ }));
    await user.click(screen.getByRole('button', { name: /^demo_payments_authorized,/ }));

    // La preuve reste atteignable — c'est le panneau de preuve, pas le dessin…
    expect(await screen.findByText(/names topic 'demo.orders.1.received'/)).toBeInTheDocument();
    // …et il dit pourquoi aucun trait ne lui correspond à l'écran.
    expect(screen.getByText(/Hidden from the diagram by the confidence filter/)).toBeInTheDocument();
  });

  it('flags a key-like column that yielded no relation, and says what it could check', async () => {
    const user = userEvent.setup();
    stubApi({
      ...model,
      entities: [model.entities[0], {
        ...model.entities[1],
        columns: [
          ...model.entities[1].columns,
          // Ce que le serveur renvoie : le nom désigne 'customer', et aucune relation n'en est
          // sortie — donc aucun topic sélectionné ne porte ce nom.
          { name: 'customer_id', type: 'STRING', primaryKey: false, references: null, keyBase: 'customer' },
        ],
      }],
    });
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await user.click(screen.getByRole('button', { name: /^demo_payments_authorized,/ }));

    expect(await screen.findByText(/Key-like, no relation \(1\)/)).toBeInTheDocument();
    // Aucun topic sélectionné ne s'appelle « customer » : c'est la moitié vérifiable, et elle
    // est actionnable — ajouter ce topic à la sélection.
    expect(screen.getByText(/no selected topic is named after it/)).toBeInTheDocument();
  });

  it('builds one query from a subgraph, not two pairwise joins stitched by hand', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await user.click(screen.getByRole('button', { name: /^demo_payments_authorized,/ }));
    await user.click(await screen.findByRole('button', { name: /Add to the join/ }));
    await user.click(screen.getByRole('button', { name: /^demo_orders_1_received,/ }));
    await user.click(await screen.findByRole('button', { name: /Add to the join/ }));

    await openTopics(user);
    const link = await screen.findByRole('link', { name: /Open 2-table join as SQL/ });
    const sql = decodeURIComponent(link.getAttribute('href')!.split('?sql=')[1]);
    expect(sql).toContain('JOIN');
    expect(sql).toContain('LIMIT 50');
  });

  it('a join set of one says what it needs rather than offering a dead link', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await user.click(screen.getByRole('button', { name: /^demo_payments_authorized,/ }));
    await user.click(await screen.findByRole('button', { name: /Add to the join/ }));

    await openTopics(user);
    expect(await screen.findByText(/a join needs two tables/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /join as SQL/ })).toBeNull();
  });

  it('saves a named selection on this browser and reloads it', async () => {
    const user = userEvent.setup();
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    await user.type(screen.getByLabelText('Name this selection'), 'Order pipeline');
    await user.click(screen.getByRole('button', { name: /^Save$/ }));

    // Elle réapparaît dans la liste, avec le nombre de topics qu'elle porte.
    expect(await screen.findByText('Order pipeline')).toBeInTheDocument();
    expect(screen.getByText('2 topics')).toBeInTheDocument();
    // Et la page dit que c'est local à ce navigateur, pas partagé.
    expect(screen.getByRole('button', { name: /Delete the saved selection Order pipeline/ }))
      .toBeInTheDocument();
  });

  it('compares two selections and reports what differs, without touching the model on screen', async () => {
    const user = userEvent.setup();
    const withSensor: DataModelResponse = {
      ...model,
      entities: [...model.entities, {
        id: 'demo_iot_sensors', topic: 'demo.iot.sensors', format: 'JSON',
        primaryKey: null, messageCount: 500,
        columns: [{ name: 'reading', type: 'DOUBLE', primaryKey: false, references: null, keyBase: null }],
      }],
      topicsRequested: 3, topicsAnalyzed: 3,
    };
    mockedAxios.post.mockImplementation((_url, body) => Promise.resolve({
      data: (body as { topics: string[] }).topics.includes('demo.iot.sensors') ? withSensor : model,
    }));
    mockedAxios.get.mockResolvedValue({ data: {} });
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await screen.findByText('2 entities · 1 relation deduced');
    await openTopics(user);
    await user.click(screen.getByRole('button', { name: /Compare with another selection/ }));
    await user.type(
      screen.getByLabelText('Add comparison topics by name, list or pattern'),
      'demo.orders.1.received,demo.payments.authorized,demo.iot.sensors{Enter}');
    await user.click(screen.getByRole('button', { name: /^Compare$/ }));

    expect(await screen.findByText(/\+1 entity/)).toBeInTheDocument();
    // The comparison is a second, independent call — the model drawn on screen is untouched.
    expect(screen.getByText('2 entities · 1 relation deduced')).toBeInTheDocument();
  });
});
