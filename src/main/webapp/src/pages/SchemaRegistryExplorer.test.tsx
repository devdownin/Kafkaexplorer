// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import SchemaRegistryExplorer from './SchemaRegistryExplorer';

vi.mock('axios');
const mockedAxios = vi.mocked(axios, true);
const SUBJECT = 'demo.avro.orders-value';
const base = `/api/schema-registry/subjects/${encodeURIComponent(SUBJECT)}`;

function renderPage() {
  return render(<RouterProvider router={createMemoryRouter([
    { path: '/schema-registry', element: <SchemaRegistryExplorer /> },
  ], { initialEntries: ['/schema-registry'] })} />);
}

beforeEach(() => { vi.clearAllMocks(); });

describe('Schema Registry Explorer', () => {
  it('loads registered versions and checks their compatibility without mutations', async () => {
    mockedAxios.get.mockImplementation((url: string) => {
      if (url === '/api/schema-registry/subjects') {
        return Promise.resolve({ data: { subjects: [SUBJECT], total: 1, truncated: false } });
      }
      if (url === `${base}/versions`) return Promise.resolve({ data: [1, 2] });
      if (url.startsWith(`${base}/versions/`)) {
        const version = Number(url.split('/').at(-1));
        return Promise.resolve({ data: {
          subject: SUBJECT, version, id: 40 + version, schemaType: 'AVRO',
          schema: JSON.stringify({ type: 'record', name: 'Order', fields: [
            { name: 'id', type: 'string' },
            ...(version === 2 ? [{ name: 'amount', type: 'double' }] : []),
          ] }),
          references: [],
        } });
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`));
    });
    mockedAxios.post.mockResolvedValue({ data: {
      compatible: false, baselineVersion: 1, candidateVersion: 2,
      level: 'BACKWARD', messages: ['A breaking change was found'],
    } });

    renderPage();
    expect(await screen.findByRole('heading', { name: SUBJECT })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Schema comparison')).toBeInTheDocument());
    expect(screen.getByText(/^\\+\\d+ lines$/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Check compatibility' }));
    await waitFor(() => expect(screen.getByText('Incompatible')).toBeInTheDocument());
    expect(mockedAxios.post).toHaveBeenCalledWith(`${base}/compatibility`,
      { baselineVersion: 1, candidateVersion: 2 }, expect.objectContaining({ timeout: 10000 }));
    expect(screen.getByText('BACKWARD')).toBeInTheDocument();
    expect(screen.getByText('A breaking change was found')).toBeInTheDocument();
  });

  it('explains the unavailable optional registry instead of blocking the app', async () => {
    mockedAxios.get.mockRejectedValue(new Error('Connection refused'));
    renderPage();
    expect(await screen.findByText('Schema Registry unavailable')).toBeInTheDocument();
    expect(screen.getByText(/Standard Kafka browsing/)).toBeInTheDocument();
    expect(screen.getByText(/compose\/schema-registry.yml/)).toBeInTheDocument();
  });
});
