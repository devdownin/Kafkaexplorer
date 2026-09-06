// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de l'écran de supervision des files d'échec.
 *
 * La logique est dans `deadLetterSupervision.ts` et testée là-bas ; ce qui est pinné ici est ce
 * que seul le câblage décide, et qui casserait sans bruit :
 *
 * - la file et sa **source** partent dans le même appel, sans quoi la seconde courbe n'existe pas ;
 * - une lecture de séries qui échoue laisse la page debout et dit pourquoi, au lieu de rendre des
 *   courbes plates — qui affirmeraient qu'aucun message n'a été perdu ;
 * - un cluster sans file d'échec obtient un état vide qui dit ce qui a été cherché, et non un
 *   tableau de zéro ligne.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import type { AxiosRequestConfig } from 'axios';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const TOPICS = ['orders', 'orders.DLQ', 'payments', 'payments.retry.5m', 'lonely-dlt'];

const DASHBOARD = {
  topics: TOPICS,
  topicSizes: { 'orders.DLQ': 12, 'payments.retry.5m': 3, 'lonely-dlt': 0 },
  totalMessages: 15,
  tables: [],
  health: true,
  clusterName: 'test',
  bootstrapServers: 'localhost:9092',
  topicLastMessages: {},
  internalTopicPrefix: 'internal.',
};

function series(topic: string, counts: number[]) {
  return {
    topic,
    windowStartMs: 0,
    windowEndMs: counts.length * 3_600_000,
    bucketMs: 3_600_000,
    counts,
    total: counts.reduce((a, b) => a + b, 0),
    coveredFromMs: null,
    partitionsMeasured: 1,
    partitionsTotal: 1,
    available: true,
    note: null,
  };
}

const ACTIVITY = {
  topics: {
    'orders.DLQ': series('orders.DLQ', [0, 4, 8]),
    orders: series('orders', [100, 100, 100]),
    'payments.retry.5m': series('payments.retry.5m', [0, 0, 0]),
    payments: series('payments', [50, 50, 50]),
    'lonely-dlt': series('lonely-dlt', [0, 0, 0]),
  },
  windowStartMs: 0,
  windowEndMs: 3 * 3_600_000,
  bucketMs: 3_600_000,
  buckets: 3,
  available: true,
  warnings: [],
};

let activityParams: Record<string, unknown> | undefined;

function stubApi(options: { topics?: unknown; activity?: unknown; activityFails?: boolean } = {}) {
  activityParams = undefined;
  mockedAxios.get.mockImplementation((url: string, config?: AxiosRequestConfig) => {
    if (url === '/api/dashboard') {
      return Promise.resolve({ status: 200, data: options.topics ?? DASHBOARD });
    }
    if (url === '/api/dashboard/activity') {
      activityParams = config?.params as Record<string, unknown> | undefined;
      return options.activityFails
        ? Promise.reject(new Error('the broker did not answer'))
        : Promise.resolve({ status: 200, data: options.activity ?? ACTIVITY });
    }
    return Promise.resolve({ status: 200, data: {} });
  });
}

async function renderPage() {
  const { default: DeadLetter } = await import('./DeadLetter');
  return render(
    <RouterProvider
      router={createMemoryRouter([{ path: '/', element: <DeadLetter /> }], { initialEntries: ['/'] })}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('DeadLetter', () => {
  it('lists only the queues, and pairs each with a source that exists', async () => {
    stubApi();
    await renderPage();

    await waitFor(() => expect(screen.getByText('orders.DLQ')).toBeInTheDocument());
    expect(screen.getByText('payments.retry.5m')).toBeInTheDocument();
    expect(screen.getByText('lonely-dlt')).toBeInTheDocument();
    // Un topic ordinaire n'est pas une file : il n'a pas de ligne à lui. `orders` reste atteignable
    // depuis la ligne de sa file — c'est le lien « from », pas une ligne.
    expect(screen.getAllByRole('row')).toHaveLength(4);
    // `lonely` n'existe pas sur ce cluster, donc rien n'est apparié plutôt qu'un nom inventé.
    expect(screen.getByText('no source paired')).toBeInTheDocument();
  });

  it('asks for every source in the same call as its queue', async () => {
    stubApi();
    await renderPage();

    await waitFor(() => expect(activityParams).toBeDefined());
    // Par paires : la coupe serveur (`explorer.activity-max-topics`) retire des lignes entières
    // au lieu de retirer toutes les sources d'un coup.
    expect(activityParams!.topics).toBe('lonely-dlt,orders.DLQ,orders,payments.retry.5m,payments');
  });

  it('renders both curves per queue, and names what the second one measures', async () => {
    stubApi();
    await renderPage();

    // La part sur la fenêtre : 12 messages tombés pour 300 produits par `orders`.
    await waitFor(() => expect(screen.getByText('4%')).toBeInTheDocument());
    const shares = screen.getAllByRole('img', { name: /took .* of what/ });
    expect(shares.length).toBeGreaterThan(0);
    expect(shares[0]).toHaveAccessibleName(/orders\.DLQ took 4% of what orders produced/);
  });

  it('says a queue without a source has nothing to compare against', async () => {
    stubApi();
    await renderPage();

    await waitFor(() =>
      expect(
        screen.getByRole('img', { name: /no source topic is paired, so there is nothing to compare against/ }),
      ).toBeInTheDocument(),
    );
    // Le motif n'est nommé qu'une fois sur la ligne : la cellule du topic. La courbe n'en fait
    // pas un doublon, elle porte la raison dans son énoncé accessible.
    expect(screen.getByText('no source paired')).toBeInTheDocument();
  });

  it('reads a quiet dead-letter topic as the good news', async () => {
    stubApi();
    await renderPage();

    await waitFor(() => expect(screen.getAllByText('quiet').length).toBeGreaterThan(0));
    expect(screen.getByText('receiving')).toBeInTheDocument();
  });

  it('states the failure instead of drawing flat curves when the series cannot be read', async () => {
    stubApi({ activityFails: true });
    await renderPage();

    await waitFor(() => expect(screen.getByText(/The series could not be read/)).toBeInTheDocument());
    // Le tableau reste : les noms et les tailles viennent de l'autre appel, qui a répondu.
    expect(screen.getByText('orders.DLQ')).toBeInTheDocument();
    expect(screen.queryByText('no traffic')).not.toBeInTheDocument();
  });

  it('says what it looked for on a cluster with no queue at all', async () => {
    stubApi({ topics: { ...DASHBOARD, topics: ['orders', 'payments'], topicSizes: {} } });
    await renderPage();

    await waitFor(() =>
      expect(screen.getByText('No dead-letter or retry topic on this cluster')).toBeInTheDocument(),
    );
    expect(mockedAxios.get).not.toHaveBeenCalledWith('/api/dashboard/activity', expect.anything());
  });

  it('passes the server own warnings through rather than swallowing them', async () => {
    stubApi({ activity: { ...ACTIVITY, warnings: ['3 topic(s) beyond the first 100 were not measured.'] } });
    await renderPage();

    await waitFor(() =>
      expect(screen.getByText('3 topic(s) beyond the first 100 were not measured.')).toBeInTheDocument(),
    );
  });
});
