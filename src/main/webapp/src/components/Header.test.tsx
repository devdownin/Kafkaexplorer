// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import Header from './Header';

/*
 * The pill is the one element whose job is to say what you are connected to, so what it renders
 * is worth pinning: it used to show `explorer.cluster-name`, shipped as "KRAFT 4.2" — a Kafka
 * version asserted by every deployment without ever asking the broker.
 */

const renderHeader = (props: Parameters<typeof Header>[0]) =>
  render(<RouterProvider router={createMemoryRouter([{ path: '/', element: <Header {...props} /> }], { initialEntries: ['/'] })} />);

describe('Header connection pill', () => {
  it('shows the deployment name and the effective bootstrap address on hover', () => {
    renderHeader({ isHealthy: true, clusterName: 'Orders prod', bootstrapServers: 'kafka:29092' });
    expect(screen.getByText('Orders prod')).toBeInTheDocument();
    expect(screen.getByTitle('Connected — Orders prod · kafka:29092')).toBeInTheDocument();
  });

  it('claims no name before the first dashboard poll answers', () => {
    renderHeader({ isHealthy: true, clusterName: '' });
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
    expect(screen.queryByText(/kraft|docker cluster/i)).toBeNull();
  });

  it('is neither green nor red before anything has been probed', () => {
    renderHeader({ isHealthy: null, clusterName: 'Orders prod', bootstrapServers: 'kafka:9092' });
    const pill = screen.getByTitle('Checking the connection…');
    expect(pill).toHaveTextContent('Connecting…');
    expect(pill.className).not.toMatch(/success|error/);
  });

  it('names the unreachable address when disconnected', () => {
    renderHeader({ isHealthy: false, clusterName: 'Orders prod', bootstrapServers: 'kafka:9092' });
    expect(screen.getByText('Disconnected')).toBeInTheDocument();
    expect(screen.getByTitle('Disconnected — no answer from kafka:9092')).toBeInTheDocument();
  });
});
