// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Sidebar from './Sidebar';

const RouteJump = () => {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate('/query')}>Open SQL route</button>
      <button onClick={() => navigate('/topic/orders')}>Open topic route</button>
    </>
  );
};

describe('Sidebar navigation groups', () => {
  it('collapses inactive groups and reveals a group when navigation enters it', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/metrics']}>
        <Sidebar isCollapsed={false} onToggle={() => undefined} />
        <RouteJump />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /Metrics/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Observe/ })).toBeDisabled();

    const explore = screen.getByRole('button', { name: /Explore/ });
    await user.click(explore);
    expect(explore).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link', { name: /SQL Editor/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Open SQL route' }));
    expect(screen.getByRole('link', { name: /SQL Editor/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Explore/ })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Open topic route' }));
    expect(screen.getByRole('link', { name: /SQL Editor/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Explore/ })).toBeDisabled();
  });
});