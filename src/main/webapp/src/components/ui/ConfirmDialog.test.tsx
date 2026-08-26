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
    expect(dialog.className).toMatch(/max-w-lg/);

    // Le fond reste un voile léger : il assombrit sans flouter l'application.
    const backdrop = dialog.parentElement!;
    expect(backdrop.className).toMatch(/confirm-overlay/);
    expect(backdrop.className).not.toMatch(/glass-overlay/);
  });

  /*
   * Le titre et la description portent une donnée dont rien ne borne la longueur : un nom
   * d'onglet, un nom de table — donc un identifiant Kafka. Sans césure autorisée, un tel nom
   * n'en offre aucune (les points n'en sont pas) et sortait de la carte par la droite.
   *
   * jsdom n'a pas de mise en page : ce qui est vérifié ici est le mécanisme, pas le débordement.
   * La mesure, elle, a été prise dans Chromium et est consignée dans le commit.
   */
  it('lets an unbreakable name wrap instead of escaping the card', async () => {
    const Long = () => {
      const confirm = useConfirm();
      return (
        <button onClick={() => void confirm({
          title: 'Drop acme.production.orders.shipped.enriched.consolidated.v2?',
          description: 'The window query replaces everything in it.',
        })}>trigger</button>
      );
    };
    render(<ConfirmProvider><Long /></ConfirmProvider>);
    await userEvent.click(screen.getByRole('button', { name: 'trigger' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.querySelector('h2')!.className).toMatch(/break-words/);
    expect(dialog.querySelector('p')!.className).toMatch(/break-words/);
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
