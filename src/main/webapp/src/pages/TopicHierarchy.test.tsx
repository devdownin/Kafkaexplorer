// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import TopicHierarchy from './TopicHierarchy';

vi.mock('axios');
const api = vi.mocked(axios, true);
const renderPage = () => render(<RouterProvider router={createMemoryRouter([
  { path: '/topics/hierarchy', element: <TopicHierarchy /> },
  { path: '/topic/:name', element: <Link to="/topics/hierarchy">Return to hierarchy</Link> },
], { initialEntries: ['/topics/hierarchy'] })} />);

beforeEach(() => { sessionStorage.clear(); vi.clearAllMocks(); });

describe('TopicHierarchy', () => {
  it('supports arrow navigation, reveals excluded names, and restores the chosen view', async () => {
    api.get.mockResolvedValue({ data: { topics: ['sales.eu.orders', 'sales.us.shipped', 'sales-eu', 'flat', 'bad..name', 'mixed.eu-test'] } });
    const user = userEvent.setup();
    renderPage();
    const tree = await screen.findByRole('tree', { name: 'Topic hierarchy' });
    const root = within(tree).getByRole('treeitem', { name: /sales, 2 topics/ });
    root.focus();
    await user.keyboard('{ArrowDown}');
    await waitFor(() => expect(within(tree).getByRole('treeitem', { name: /^sales\.eu, / })).toHaveFocus());
    await user.keyboard('{ArrowRight}');
    expect(within(tree).getByRole('treeitem', { name: /^sales\.eu, / })).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard('{ArrowRight}');
    await waitFor(() => expect(within(tree).getByRole('treeitem', { name: 'sales.eu.orders' })).toHaveFocus());
    await user.keyboard('{ArrowLeft}');
    await waitFor(() => expect(within(tree).getByRole('treeitem', { name: /^sales\.eu, / })).toHaveFocus());

    await user.click(screen.getByRole('button', { name: 'Inspect 3 excluded names' }));
    expect(screen.getByText('flat')).toBeInTheDocument();
    expect(screen.getByText('No separator')).toBeInTheDocument();
    expect(screen.getByText('Mixed separators')).toBeInTheDocument();
    expect(screen.getByText('Empty level')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Naming separator'), '-');
    await user.click(screen.getByTitle('sales-eu'));
    await user.click(screen.getByRole('link', { name: 'Return to hierarchy' }));
    expect(await screen.findByRole('tree', { name: 'Topic hierarchy' })).toBeInTheDocument();
    expect(screen.getByLabelText('Naming separator')).toHaveValue('-');
    await user.selectOptions(screen.getByLabelText('Naming separator'), '.');
    expect(within(screen.getByRole('tree')).getByRole('treeitem', { name: /^sales\.eu, / })).toHaveAttribute('aria-expanded', 'true');
  });

  it('bounds mounted tree rows with thousands of matching topics', async () => {
    api.get.mockResolvedValue({ data: { topics: Array.from({ length: 5000 }, (_, i) => `sales.eu.${i}`) } });
    const user = userEvent.setup();
    renderPage();
    const tree = await screen.findByRole('tree');
    await user.type(screen.getByLabelText('Filter hierarchy'), 'sales');
    await waitFor(() => expect(within(tree).getAllByRole('treeitem').length).toBeGreaterThan(1));
    expect(within(tree).getAllByRole('treeitem').length).toBeLessThan(100);
    fireEvent.scroll(tree, { target: { scrollTop: 40_000 } });
    expect(within(tree).getAllByRole('treeitem').length).toBeLessThan(100);
  });
});
