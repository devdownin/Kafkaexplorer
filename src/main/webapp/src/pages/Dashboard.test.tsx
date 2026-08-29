// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le câblage de la colonne d'activité du tableau de bord.
 *
 * `topicActivity.test.ts` couvre la logique pure ; ce qui reste, et qui n'a de sens qu'ici, c'est
 * ce que la page *demande* et ce qu'elle affiche quand la réponse n'arrive pas. Deux choses en
 * particulier : elle ne mesure que les lignes affichées — sur un cluster de neuf cents topics,
 * demander la courbe de tous coûterait un balayage d'offsets que personne ne regarde — et une
 * lecture qui échoue laisse la raison à l'écran plutôt que des courbes plates, qui affirmeraient
 * « aucun trafic ».
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router-dom';
import axios from 'axios';
import Dashboard from './Dashboard';
import { ToastProvider } from '../components/Toast';
import { ConfirmProvider } from '../components/ui';
import type { TopicActivity, TopicActivityResponse } from '../api/types';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const HOUR = 3_600_000;
const WINDOW_START = 1_700_000_000_000;
const topics = Array.from({ length: 30 }, (_, i) => `demo.topic.${String(i).padStart(2, '0')}`);

function series(topic: string, counts: number[], overrides: Partial<TopicActivity> = {}): TopicActivity {
  return {
    topic,
    windowStartMs: WINDOW_START,
    windowEndMs: WINDOW_START + counts.length * HOUR,
    bucketMs: HOUR,
    counts,
    total: counts.reduce((a, b) => a + b, 0),
    coveredFromMs: null,
    partitionsMeasured: 1,
    partitionsTotal: 1,
    available: true,
    note: null,
    ...overrides,
  };
}

const dashboard = {
  topics,
  topicSizes: Object.fromEntries(topics.map((t, i) => [t, (i + 1) * 100])),
  totalMessages: 46_500,
  tables: [],
  jobs: [],
  health: true,
  clusterName: 'Kafka cluster',
  bootstrapServers: 'kafka:9092',
  topicLastMessages: Object.fromEntries(topics.map(t => [t, WINDOW_START])),
};

const runningJob = {
  queryId: 'q-7f3a91c4',
  flinkJobId: '8b1e0a2c94d7f6135ae2',
  statementType: 'INSERT',
  status: 'RUNNING',
  sql: 'INSERT INTO demo_orders_out SELECT * FROM demo_orders_1_received',
  startedAt: WINDOW_START,
  endedAt: null,
  cancelRequested: false,
};

const jobDetails = {
  ...runningJob,
  executionMode: 'ASYNC_JOB',
  statusDetail: 'Submitted via Flink Job mode',
  cancelRequestedAt: null,
  errorMessage: null,
  lastUpdatedAt: WINDOW_START + 1_000,
  history: [
    { timestamp: WINDOW_START, status: 'RUNNING', detail: 'Submitted via Flink Job mode' },
  ],
};

function activityFor(names: string[], warnings: string[] = []): TopicActivityResponse {
  return {
    topics: Object.fromEntries(names.map((t, i) => [t, series(t, [0, i + 1, 2 * (i + 1), i + 1])])),
    windowStartMs: WINDOW_START,
    windowEndMs: WINDOW_START + 24 * HOUR,
    bucketMs: HOUR,
    buckets: 24,
    available: true,
    warnings,
  };
}

/** Ce que la page a demandé pour la colonne, ou `null` si elle n'a rien demandé. */
function activityRequests() {
  return mockedAxios.get.mock.calls.filter(call => call[0] === '/api/dashboard/activity');
}

function stubApi(activity: TopicActivityResponse | Error = activityFor(topics.slice(0, 25))) {
  mockedAxios.get.mockImplementation((url: string) => {
    if (url === '/api/dashboard') return Promise.resolve({ data: dashboard });
    if (url === '/api/dashboard/activity') {
      return activity instanceof Error ? Promise.reject(activity) : Promise.resolve({ data: activity });
    }
    return Promise.resolve({ data: {} });
  });
  mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
}

/** Où le clic a mené, rendu en clair : l'assertion porte sur l'URL, pas sur un espion de routeur. */
const Landing: React.FC = () => {
  const location = useLocation();
  return <p>landed on {location.pathname}{location.search}</p>;
};

function renderPage() {
  return render(
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider router={createMemoryRouter([
          { path: '/', element: <Dashboard /> },
          { path: '/topic/:name', element: <Landing /> },
        ], { initialEntries: ['/'] })} />
      </ConfirmProvider>
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

/** Ce que la page a demandé pour le tableau lui-même. */
function dashboardRequests() {
  return mockedAxios.get.mock.calls.filter(call => call[0] === '/api/dashboard');
}

/*
 * Le sondage tournait à 5 s en constante de module : ni réglable, ni extinguible, ni énoncé. Ce
 * qui n'a de sens qu'ici, c'est que le réglage pilote vraiment la minuterie, que « off » ne laisse
 * rien tourner, et que la page date ce qu'elle montre — sans quoi « off » afficherait des chiffres
 * vieux d'une heure sans un mot.
 */
describe('Dashboard auto-refresh', () => {
  it('states when it read and what it will do next', async () => {
    stubApi();
    renderPage();

    expect(await screen.findByText(/Updated just now · refreshing every 5 s/)).toBeInTheDocument();
  });

  it('polls on the chosen cadence, and the choice sticks', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      stubApi();
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await waitFor(() => expect(dashboardRequests()).toHaveLength(1));

      await user.selectOptions(screen.getByLabelText('Auto-refresh'), '1m');
      expect(localStorage.getItem('kse:dashboard-refresh')).toBe('1m');
      // L'ancienne minuterie a pu tirer une fois entre le clic et le réarmement de l'effet :
      // le compte de référence est celui d'après le réarmement, pas celui d'avant le clic.
      await vi.advanceTimersByTimeAsync(0);
      const armed = dashboardRequests().length;

      // La cadence d'origine ne doit plus rien déclencher.
      await vi.advanceTimersByTimeAsync(50_000);
      expect(dashboardRequests()).toHaveLength(armed);

      await vi.advanceTimersByTimeAsync(15_000);
      expect(dashboardRequests()).toHaveLength(armed + 1);
    } finally {
      vi.useRealTimers();
    }
  });

  /*
   * Le sondage et le chargement initial ne vivaient qu'ensemble dans un seul effet : dépendre du
   * réglage aurait rejoué le chargement — bannière d'erreur comprise — à chaque changement.
   */
  it('changing the cadence rearms a timer, it does not ask anything more', async () => {
    stubApi();
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(dashboardRequests()).toHaveLength(1));
    // La lecture initiale de la colonne doit avoir eu lieu avant qu'on compte, sinon c'est elle
    // qu'on prendrait pour la lecture superflue.
    await waitFor(() => expect(activityRequests().length).toBeGreaterThanOrEqual(1));
    const activityBefore = activityRequests().length;

    await user.selectOptions(screen.getByLabelText('Auto-refresh'), '30s');

    expect(dashboardRequests()).toHaveLength(1);
    expect(activityRequests()).toHaveLength(activityBefore);
  });

  it('off stops the polling — both the table and the activity column', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      stubApi();
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderPage();
      await waitFor(() => expect(dashboardRequests()).toHaveLength(1));
      await waitFor(() => expect(activityRequests().length).toBeGreaterThanOrEqual(1));

      // Le compte est pris *avant* le clic : choisir « off » ne doit pas déclencher une
      // dernière lecture juste après qu'on lui a dit d'arrêter — ce que ferait un effet qui
      // relit à chaque exécution en dépendant du réglage.
      const dashboardSoFar = dashboardRequests().length;
      const activitySoFar = activityRequests().length;

      await user.selectOptions(screen.getByLabelText('Auto-refresh'), 'off');
      await vi.advanceTimersByTimeAsync(10 * 60_000);

      expect(dashboardRequests()).toHaveLength(dashboardSoFar);
      expect(activityRequests()).toHaveLength(activitySoFar);
      expect(screen.getByText(/auto-refresh off/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  /*
   * Ce que « off » demande, c'est de ne pas interroger le broker en boucle derrière l'opérateur,
   * pas de lui présenter les chiffres d'il y a une heure au moment où il revient les regarder.
   */
  it('still reads once when the tab comes back, even with the polling off', async () => {
    stubApi();
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(dashboardRequests()).toHaveLength(1));

    await user.selectOptions(screen.getByLabelText('Auto-refresh'), 'off');
    const before = dashboardRequests().length;

    document.dispatchEvent(new Event('visibilitychange'));

    await waitFor(() => expect(dashboardRequests().length).toBe(before + 1));
  });

  it('reopens on the stored choice rather than the default', async () => {
    localStorage.setItem('kse:dashboard-refresh', 'off');
    stubApi();
    renderPage();

    expect(await screen.findByText(/auto-refresh off/)).toBeInTheDocument();
    expect(screen.getByLabelText('Auto-refresh')).toHaveValue('off');
  });
});

describe('Dashboard activity column', () => {
  it('measures the rows on screen, not the whole cluster', async () => {
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    const params = activityRequests()[0][1]?.params as { topics: string; windowMs: number; buckets: number };
    // Vingt-cinq lignes affichées sur trente topics : la lecture s'arrête à la page.
    expect(params.topics.split(',')).toHaveLength(25);
    expect(params.topics.split(',')).toEqual(topics.slice(0, 25));
    expect(params.windowMs).toBe(24 * HOUR);
    expect(params.buckets).toBe(24);
  });

  it('renders each curve with what it says, since an image alone says nothing', async () => {
    stubApi();
    renderPage();

    // Un bouton, pas une image : le clic mène aux messages du bucket, donc l'affordance doit
    // exister au clavier aussi. Le SVG lui-même est `aria-hidden`.
    const curve = await screen.findByRole('button', { name: /produced in demo\.topic\.01/ });
    expect(curve.getAttribute('aria-label')).toMatch(/Peak/);
    // Et la ligne de portée dit la résolution plutôt que de laisser la courbe la suggérer.
    expect(screen.getByText(/One point per 1 h over the last 24 h/)).toBeInTheDocument();
  });

  it('writes the peak beside the curve, the scale being per row', async () => {
    stubApi();
    renderPage();

    // demo.topic.01 : counts [0, 2, 4, 2] sur des buckets d'une heure — le pic vaut 4.
    await screen.findByRole('button', { name: /produced in demo\.topic\.01/ });
    expect(screen.getAllByText('4/h').length).toBeGreaterThan(0);
  });

  it('opens the peak bucket in the topic explorer, primed at that hour', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    const curve = await screen.findByRole('button', { name: /produced in demo\.topic\.01/ });
    // Le nom accessible dit ce que la touche Entrée va faire, avant qu'on la presse.
    expect(curve.getAttribute('aria-label')).toMatch(/Opens demo\.topic\.01 with the search primed at/);
    await user.click(curve);

    // Le pic de la série est le bucket 2, soit deux heures après le début de la fenêtre.
    const at = new Date(WINDOW_START + 2 * HOUR);
    const pad = (n: number) => String(n).padStart(2, '0');
    const local = `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
    const landed = await screen.findByText(/^landed on/);
    expect(landed.textContent).toBe(
      `landed on /topic/demo.topic.01?start=TIMESTAMP&at=${encodeURIComponent(local)}`);
  });

  it('says a topic went silent, which no shape at this size shows', async () => {
    const response = activityFor(topics.slice(0, 25));
    // Un régime, puis plus rien sur le dernier quart de la fenêtre.
    const counts = [...Array(18).fill(12), ...Array(6).fill(0)];
    response.topics[topics[0]] = series(topics[0], counts);
    stubApi(response);
    renderPage();

    expect(await screen.findByText('silent 6 h+')).toBeInTheDocument();
    // Un prédicat plutôt qu'une `RegExp` construite : échapper un nom de topic à la main revient à
    // réécrire un échappement de regex, et le faire à moitié (les points, pas les antislashs) est
    // exactement ce que CodeQL signale. Ici il n'y a rien à échapper.
    const curve = screen.getByRole('button', {
      name: (accessibleName: string) => accessibleName.includes(`produced in ${topics[0]}`),
    });
    expect(curve.getAttribute('aria-label')).toMatch(/Nothing produced for at least 6 h/);
  });

  it('states a topic it could not measure instead of drawing it flat', async () => {
    const response = activityFor(topics.slice(0, 25));
    response.topics[topics[0]] = {
      ...series(topics[0], []),
      available: false,
      total: 0,
      note: 'Offsets could not be read for any of the 3 partition(s): Connection refused',
    };
    stubApi(response);
    renderPage();

    const unmeasured = await screen.findByRole('img', { name: /could not be measured: Offsets could not be read/ });
    expect(unmeasured).toHaveTextContent('not measured');
    expect(screen.getByText(/1 of the 25 topics on this page could not be measured/)).toBeInTheDocument();
  });

  it('keeps the table when the read fails, and says why', async () => {
    stubApi(new Error('Network Error'));
    renderPage();

    await waitFor(() => expect(screen.getByText(/Activity could not be measured/)).toBeInTheDocument());
    // Le tableau n'est pas remplacé par une bannière : seule la colonne manque.
    expect(screen.getByText(topics[0])).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /produced in/ })).toBeNull();
  });

  it('reports a read the broker refused outright, once', async () => {
    stubApi({
      topics: {}, windowStartMs: 0, windowEndMs: 0, bucketMs: 3_600_000, buckets: 24,
      available: false, warnings: ['Offsets could not be read: Connection refused'],
    });
    renderPage();

    await waitFor(() => expect(screen.getByText(
      'Activity could not be measured: Offsets could not be read: Connection refused',
    )).toBeInTheDocument());
    // Et pas une seconde fois en dessous, sous forme d'avertissement.
    expect(screen.queryByText('Offsets could not be read: Connection refused')).toBeNull();
  });

  it('renders the server own warnings, a truncated read being invisible otherwise', async () => {
    stubApi(activityFor(topics.slice(0, 25), ['2 topic(s) were left out of this read.']));
    renderPage();

    expect(await screen.findByText('2 topic(s) were left out of this read.')).toBeInTheDocument();
  });

  it('asks for nothing when the column is switched off', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Activity'), 'off');

    await waitFor(() => expect(screen.queryByRole('columnheader', { name: /Activity/ })).toBeNull());
    expect(activityRequests()).toHaveLength(1);
    // Le choix survit à la page : la colonne coûte des allers-retours au broker.
    expect(localStorage.getItem('kse:dashboard-activity')).toBe('off');
  });

  it('changes the scale without asking the server again, and says the scale changed', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Scale'), 'log');

    // L'échelle est un choix de lecture : elle ne change rien de ce qui est demandé…
    await waitFor(() => expect(screen.getByRole('columnheader', { name: /log scale/ })).toBeInTheDocument());
    expect(activityRequests()).toHaveLength(1);
    // …et une échelle non déclarée est ce qui rend un graphe trompeur, donc l'en-tête la nomme.
    expect(localStorage.getItem('kse:dashboard-activity-scale')).toBe('log');
  });

  it('re-reads on a window change rather than relabelling the previous series', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await waitFor(() => expect(activityRequests()).toHaveLength(1));
    await user.selectOptions(screen.getByLabelText('Activity'), '1h');

    await waitFor(() => expect(activityRequests()).toHaveLength(2));
    const params = activityRequests()[1][1]?.params as { windowMs: number; buckets: number };
    expect(params.windowMs).toBe(HOUR);
    expect(params.buckets).toBe(12);
  });
});

/*
 * La bascule « Mark retry ». Ce qui n'a de sens qu'ici, c'est qu'elle *marque* sans filtrer — la
 * confondre avec ses deux voisines « Hide … » ferait disparaître des lignes que personne n'a
 * demandé de cacher — et qu'elle dise quelque chose même quand la page courante n'en contient
 * aucune, faute de quoi elle est indiscernable d'un interrupteur en panne.
 */
describe('Dashboard retry marker', () => {
  const retryTopics = [
    'demo.orders.received', 'demo.orders.retry.5m', 'demo.RETRY-payments',
    // Les deux orthographes de la file morte : `.dlt` (Spring Kafka) et `.dlq` (Spring
    // Cloud Stream et le reste de l'écosystème), la seconde n'ayant longtemps été
    // reconnue nulle part.
    'demo.orders.dlt', 'demo.payments.dlq',
  ];

  function stubRetryCluster() {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/dashboard') {
        return Promise.resolve({
          data: {
            ...dashboard,
            topics: retryTopics,
            topicSizes: Object.fromEntries(retryTopics.map(t => [t, 10])),
            topicLastMessages: Object.fromEntries(retryTopics.map(t => [t, WINDOW_START])),
          },
        });
      }
      if (url === '/api/dashboard/activity') return Promise.resolve({ data: activityFor(retryTopics) });
      return Promise.resolve({ data: {} });
    });
    mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
  }

  it('marks every topic whose name carries "retry", whatever its case or position', async () => {
    const user = userEvent.setup();
    stubRetryCluster();
    renderPage();

    await screen.findByText('demo.orders.retry.5m');
    expect(screen.queryAllByText('Retry')).toHaveLength(0);

    await user.click(screen.getByLabelText('Mark retry'));

    // Un suffixe n'aurait trouvé ni `…retry.5m` ni `RETRY-payments`.
    expect(screen.getAllByText('Retry')).toHaveLength(2);
    expect(screen.getByText('2 marked retry')).toBeInTheDocument();
  });

  it('marks without filtering — the DLT and the plain topic stay on screen', async () => {
    const user = userEvent.setup();
    stubRetryCluster();
    renderPage();

    await screen.findByText('demo.orders.retry.5m');
    await user.click(screen.getByLabelText('Mark retry'));

    for (const topic of retryTopics) {
      expect(screen.getByText(topic)).toBeInTheDocument();
    }
    // La marque est portée en plus de l'état, jamais à sa place : un topic de reprise reste vide,
    // sain ou DLT par ailleurs.
    expect(screen.getByText('DLT')).toBeInTheDocument();
  });

  it('says so when nothing on the cluster matches, rather than looking broken', async () => {
    const user = userEvent.setup();
    stubApi();
    renderPage();

    await screen.findByText(topics[0]);
    await user.click(screen.getByLabelText('Mark retry'));

    expect(screen.getByText('no retry topic')).toBeInTheDocument();
    expect(screen.queryAllByText('Retry')).toHaveLength(0);
  });
});

/*
 * La règle de file morte, vue depuis la page. `topicKinds.test.ts` couvre la règle elle-même ; ce
 * qui n'a de sens qu'ici, c'est que les deux orthographes atteignent vraiment le badge et le
 * filtre — une règle élargie que la page n'appellerait pas serait verte en test et fausse à
 * l'écran.
 */
describe('Dashboard dead-letter rule', () => {
  const topicsWithDlq = ['demo.orders.received', 'demo.orders.dlt', 'demo.payments.dlq'];

  function stubDlqCluster() {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/dashboard') {
        return Promise.resolve({
          data: {
            ...dashboard,
            topics: topicsWithDlq,
            topicSizes: Object.fromEntries(topicsWithDlq.map(t => [t, 10])),
            topicLastMessages: Object.fromEntries(topicsWithDlq.map(t => [t, WINDOW_START])),
          },
        });
      }
      if (url === '/api/dashboard/activity') return Promise.resolve({ data: activityFor(topicsWithDlq) });
      return Promise.resolve({ data: {} });
    });
    mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
  }

  it('badges each dead-letter topic with the spelling it actually carries', async () => {
    stubDlqCluster();
    renderPage();

    await screen.findByText('demo.payments.dlq');
    // `DLQ` pour tout le monde serait une convention affirmée que le producteur n'a pas suivie.
    expect(screen.getByText('DLT')).toBeInTheDocument();
    expect(screen.getByText('DLQ')).toBeInTheDocument();
  });

  it('hides both spellings, the switch naming the kind rather than one of them', async () => {
    const user = userEvent.setup();
    stubDlqCluster();
    renderPage();

    await screen.findByText('demo.payments.dlq');
    await user.click(screen.getByLabelText('Hide dead letter'));

    expect(screen.queryByText('demo.orders.dlt')).toBeNull();
    expect(screen.queryByText('demo.payments.dlq')).toBeNull();
    expect(screen.getByText('demo.orders.received')).toBeInTheDocument();
  });
});

/*
 * L'historique d'un job.
 *
 * Le magasin garde `statusDetail`, `errorMessage` et les transitions datées — la réponse à
 * « qu'est-il arrivé à mon INSERT » — et `GET /api/query/jobs/{id}` les servait déjà. Ce qui n'a
 * de sens qu'ici : la carte ne les demande **qu'au clic** (le tableau de bord sonde toutes les
 * cinq secondes, une requête par carte à chaque tour ferait payer à tout le monde un détail que
 * personne ne regarde), elle ne redemande pas ce qu'elle a déjà lu, et une lecture qui échoue
 * laisse la raison à l'écran — c'est précisément ce qu'on était venu lire.
 */
describe('Dashboard — the history of a Flink job', () => {
  function stubWithJob(detail: unknown | Error = jobDetails) {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/dashboard') return Promise.resolve({ data: { ...dashboard, jobs: [runningJob] } });
      if (url === '/api/dashboard/activity') return Promise.resolve({ data: activityFor(topics.slice(0, 25)) });
      if (url.startsWith('/api/query/jobs/')) {
        return detail instanceof Error ? Promise.reject(detail) : Promise.resolve({ data: detail });
      }
      return Promise.resolve({ data: {} });
    });
    mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
  }

  const detailRequests = () =>
    mockedAxios.get.mock.calls.filter(call => String(call[0]).startsWith('/api/query/jobs/'));

  it('asks for the record only when the card is opened, and only once', async () => {
    stubWithJob();
    const user = userEvent.setup();
    renderPage();

    const toggle = await screen.findByRole('button', { name: /history and detail/i });
    expect(detailRequests()).toHaveLength(0);

    await user.click(toggle);
    await waitFor(() => expect(detailRequests()).toHaveLength(1));
    expect(detailRequests()[0][0]).toBe('/api/query/jobs/q-7f3a91c4');
    expect(await screen.findByText('Submitted via Flink Job mode')).toBeInTheDocument();

    // Replier puis rouvrir ne relit pas : ce qu'on a lu, on l'a.
    await user.click(toggle);
    await user.click(toggle);
    await waitFor(() => expect(screen.getByText('Submitted via Flink Job mode')).toBeInTheDocument());
    expect(detailRequests()).toHaveLength(1);
  });

  it('shows the reason when the record cannot be read', async () => {
    stubWithJob(new Error('Network Error'));
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /history and detail/i }));

    // Pas un toast qui passe en trois secondes : l'échec porte sur ce qu'on venait lire, et il
    // reste sous la carte avec la raison du serveur.
    expect(await screen.findByText(/backend may be offline or unreachable/i)).toBeInTheDocument();
    expect(screen.queryByText('Submitted via Flink Job mode')).not.toBeInTheDocument();
  });
});
