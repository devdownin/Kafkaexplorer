// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le panneau d'activité d'un topic.
 *
 * `topicActivity.test.ts` couvre la mesure et ce qu'on en dit ; ce qui n'a de sens qu'ici, c'est
 * le contrat du panneau avec la page : ce qu'il demande au serveur, l'instant qu'il rend au clic
 * — c'est lui qui règle le départ de la recherche, donc une erreur d'un bucket enverrait la
 * recherche à côté — et le fait qu'un topic non mesurable soit dit plutôt que dessiné plat.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import TopicActivityPanel from './TopicActivityPanel';
import type { TopicActivityResponse } from '../../api/types';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);

const HOUR = 3_600_000;
const START = 1_700_000_000_000;
const TOPIC = 'demo.orders.1.received';
/** Le pic est le bucket 2 — c'est celui que le clic doit désigner sans survol. */
const COUNTS = [4, 9, 40, 12, 7, 11, 9, 10, 8, 12, 9, 11];

function response(overrides: Partial<TopicActivityResponse['topics'][string]> = {}): TopicActivityResponse {
  return {
    topics: {
      [TOPIC]: {
        topic: TOPIC,
        windowStartMs: START,
        windowEndMs: START + COUNTS.length * HOUR,
        bucketMs: HOUR,
        counts: COUNTS,
        total: COUNTS.reduce((a, b) => a + b, 0),
        coveredFromMs: null,
        partitionsMeasured: 3,
        partitionsTotal: 3,
        available: true,
        note: null,
        ...overrides,
      },
    },
    windowStartMs: START,
    windowEndMs: START + COUNTS.length * HOUR,
    bucketMs: HOUR,
    buckets: COUNTS.length,
    available: true,
    warnings: [],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  mockedAxios.isCancel = ((e: unknown) => e instanceof Error && e.name === 'CanceledError') as never;
});

describe('TopicActivityPanel', () => {
  it('asks for this topic alone, and states what it measured', async () => {
    mockedAxios.get.mockResolvedValue({ data: response() });
    render(<TopicActivityPanel topic={TOPIC} />);

    await waitFor(() => expect(screen.getByText(/messages/)).toBeInTheDocument());
    const params = mockedAxios.get.mock.calls[0][1]?.params as { topics: string; buckets: number };
    expect(params.topics).toBe(TOPIC);
    expect(screen.getByText('142 messages')).toBeInTheDocument();
    expect(screen.getByText('peak 40/h')).toBeInTheDocument();
    // La colonne compte des offsets, pas des enregistrements présents : le panneau le dit, sans
    // quoi la courbe et « Messages » se liraient comme deux mesures de la même chose.
    expect(screen.getByText(/counted from offsets/)).toBeInTheDocument();
  });

  it('hands back the start of the bucket it designates', async () => {
    const user = userEvent.setup();
    const picked = vi.fn();
    mockedAxios.get.mockResolvedValue({ data: response() });
    render(<TopicActivityPanel topic={TOPIC} onPickInstant={picked} />);

    const chart = await screen.findByRole('button', { name: /produced in demo/ });
    await user.click(chart);

    // Sans survol, c'est le pic — le bucket que l'énoncé accessible nomme déjà.
    expect(picked).toHaveBeenCalledWith(START + 2 * HOUR);
  });

  it('says a topic it could not measure instead of drawing it flat', async () => {
    mockedAxios.get.mockResolvedValue({
      data: response({ available: false, counts: [], total: 0, note: 'Connection refused' }),
    });
    render(<TopicActivityPanel topic={TOPIC} />);

    expect(await screen.findByText('Activity could not be measured')).toBeInTheDocument();
    expect(screen.getByText('Connection refused')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /produced in/ })).toBeNull();
  });

  it('keeps the panel standing when the read fails, with the reason', async () => {
    mockedAxios.get.mockRejectedValue(new Error('Network Error'));
    render(<TopicActivityPanel topic={TOPIC} />);

    expect(await screen.findByText(/Cannot reach the server/)).toBeInTheDocument();
    // Les commandes restent : c'est par elles qu'on relance.
    expect(screen.getByLabelText('Window')).toBeInTheDocument();
  });
});
