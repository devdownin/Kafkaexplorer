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
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { DataModelResponse } from '../api/types';

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
        { name: 'order_id', type: 'STRING', primaryKey: true, references: null },
        { name: 'status', type: 'STRING', primaryKey: false, references: null },
      ],
    },
    {
      id: 'demo_payments_authorized',
      topic: 'demo.payments.authorized',
      format: 'JSON',
      primaryKey: 'payment_id',
      messageCount: 1183,
      columns: [
        { name: 'payment_id', type: 'STRING', primaryKey: true, references: null },
        { name: 'order_id', type: 'STRING', primaryKey: false, references: 'demo_orders_1_received' },
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
  mockedAxios.get.mockResolvedValue({ data: {} });
}

async function renderPage(initialEntry = '/data-model') {
  const { default: DataModel } = await import('./DataModel');
  const { ToastProvider } = await import('../components/Toast');
  const { ConfirmProvider } = await import('../components/ui');
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter(
          [{ path: '/data-model', element: <DataModel /> }, { path: '/query', element: <p>SQL editor</p> }],
          { initialEntries: [initialEntry] },
        )} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

/** Ouvre le tiroir de sélection — sous le seuil desktop, c'est la seule porte vers les topics. */
async function openTopics(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^Topics \(/ }));
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
      { topics: ['demo.orders.1.received', 'demo.orders.2.validated'] },
      expect.anything(),
    ));
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
      { topics: ['demo.orders.1.received'] },
      expect.anything(),
    ));
  });

  it('replays a selection carried by the URL, which is what makes a model shareable', async () => {
    stubApi();
    await renderPage('/data-model?topics=demo.orders.1.received,demo.payments.authorized');

    await waitFor(() => expect(mockedAxios.post).toHaveBeenCalledWith(
      '/api/data-model',
      { topics: ['demo.orders.1.received', 'demo.payments.authorized'] },
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
        columns: [{ name: 'reading', type: 'DOUBLE', primaryKey: false, references: null }],
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
          { name: 'customer_id', type: 'STRING', primaryKey: false, references: null },
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

  it('compares two selections and reports what differs, without touching the model on screen', async () => {
    const user = userEvent.setup();
    const withSensor: DataModelResponse = {
      ...model,
      entities: [...model.entities, {
        id: 'demo_iot_sensors', topic: 'demo.iot.sensors', format: 'JSON',
        primaryKey: null, messageCount: 500,
        columns: [{ name: 'reading', type: 'DOUBLE', primaryKey: false, references: null }],
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
