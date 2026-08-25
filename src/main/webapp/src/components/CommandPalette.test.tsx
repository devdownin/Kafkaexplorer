// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CommandPalette from './CommandPalette';

const setup = (onClose = vi.fn()) => {
  render(
    <MemoryRouter>
      <CommandPalette onClose={onClose} topics={['demo.orders.1.received']} tables={['demo_orders_1_received']} />
    </MemoryRouter>,
  );
  return onClose;
};

describe('CommandPalette', () => {
  /*
   * Le dialogue est la carte, pas le voile plein écran.
   *
   * `role="dialog"` portait sur le conteneur `fixed inset-0` : l'élément annoncé comme
   * dialogue faisait la taille du viewport, et le clic-pour-fermer — dont tout l'intérêt
   * est d'être *hors* du dialogue — se trouvait dedans.
   */
  it('scopes the dialog to the card rather than to the full-screen backdrop', () => {
    setup();
    const dialog = screen.getByRole('dialog', { name: 'Command palette' });
    expect(dialog.className).not.toMatch(/\bfixed\b/);
    expect(dialog.className).not.toMatch(/\binset-0\b/);
    expect(dialog.className).toMatch(/max-w-xl/);
    expect(dialog.parentElement!.className).toMatch(/fixed inset-0/);
  });

  it('closes on a backdrop click but not on a click inside the card', async () => {
    const onClose = setup();
    const dialog = screen.getByRole('dialog');

    await userEvent.click(screen.getByRole('textbox', { name: 'Search' }));
    expect(onClose).not.toHaveBeenCalled();

    await userEvent.click(dialog.parentElement!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
