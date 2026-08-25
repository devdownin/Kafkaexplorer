// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmProvider, useConfirm } from './ConfirmDialog';

/** Harness: exposes the confirm() result of a danger dialog. */
function Harness({ onResult }: { onResult: (v: boolean) => void }) {
  const confirm = useConfirm();
  return (
    <button onClick={async () => onResult(await confirm({ title: 'Delete this metric?', confirmLabel: 'Delete', tone: 'danger' }))}>
      trigger
    </button>
  );
}

const setup = (onResult: (v: boolean) => void) =>
  render(<ConfirmProvider><Harness onResult={onResult} /></ConfirmProvider>);

describe('ConfirmDialog / useConfirm', () => {
  it('opens with the given title and resolves true when confirmed', async () => {
    const onResult = vi.fn();
    setup(onResult);
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Delete this metric?');

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(true));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('resolves false when cancelled', async () => {
    const onResult = vi.fn();
    setup(onResult);
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));
    await screen.findByRole('dialog');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(false));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  /*
   * Le dialogue est la carte, pas le voile plein écran.
   *
   * `role="dialog"` portait sur le conteneur `fixed inset-0`, donc l'élément annoncé
   * comme dialogue faisait tout l'écran pour une question de trois lignes — et le voile
   * masquait l'application derrière un flou, alors qu'une confirmation parle précisément
   * de ce qui est derrière elle (« remplacer le contenu de l'éditeur ? »).
   */
  it('scopes the dialog to the card rather than to the full-screen backdrop', async () => {
    setup(vi.fn());
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.className).not.toMatch(/\bfixed\b/);
    expect(dialog.className).not.toMatch(/\binset-0\b/);
    expect(dialog.className).toMatch(/max-w-sm/);

    // Le fond reste un voile léger : il assombrit sans flouter l'application.
    const backdrop = dialog.parentElement!;
    expect(backdrop.className).toMatch(/confirm-overlay/);
    expect(backdrop.className).not.toMatch(/glass-overlay/);
  });

  it('still cancels when the backdrop is clicked', async () => {
    const onResult = vi.fn();
    setup(onResult);
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));

    const dialog = await screen.findByRole('dialog');
    await userEvent.click(dialog.parentElement!);
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(false));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('resolves false when dismissed with Escape', async () => {
    const onResult = vi.fn();
    setup(onResult);
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));
    await screen.findByRole('dialog');

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(false));
  });
});
