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

    await userEvent.click(screen.getByRole('combobox', { name: 'Search' }));
    expect(onClose).not.toHaveBeenCalled();

    await userEvent.click(dialog.parentElement!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('exposes a grouped listbox and keeps the active option in sync with keyboard navigation', async () => {
    const user = userEvent.setup();
    setup();
    const input = screen.getByRole('combobox', { name: 'Search' });
    const listbox = screen.getByRole('listbox', { name: 'Command results' });

    expect(input).toHaveAttribute('aria-controls', listbox.id);
    expect(screen.getByRole('group', { name: 'Actions' })).toBeInTheDocument();
    await user.click(input);
    await user.keyboard('{ArrowDown}');

    const activeId = input.getAttribute('aria-activedescendant');
    expect(activeId).toBe('command-option-1');
    expect(document.getElementById(activeId!)).toHaveAttribute('aria-selected', 'true');
    expect(document.getElementById(activeId!)).toHaveTextContent('Add metric');
  });

  it('keeps keyboard focus in the modal search field until dismissal', async () => {
    const user = userEvent.setup();
    setup();
    const input = screen.getByRole('combobox', { name: 'Search' });
    await user.tab();
    expect(input).toHaveFocus();
  });

  it('restores focus to the opener when the palette unmounts', () => {
    const opener = document.createElement('button');
    opener.textContent = 'Open palette';
    document.body.appendChild(opener);
    opener.focus();

    const palette = render(
      <MemoryRouter>
        <CommandPalette onClose={() => undefined} topics={[]} tables={[]} />
      </MemoryRouter>,
    );
    palette.unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });
});
