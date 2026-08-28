// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { RadioCards } from './RadioCards';

const OPTIONS = [
  { value: 'a', label: 'Alpha', description: 'the first' },
  { value: 'b', label: 'Beta', description: 'the second' },
  { value: 'c', label: 'Gamma' },
] as const;

const Harness = ({ onChange }: { onChange?: (v: string) => void }) => {
  const [value, setValue] = useState<string>('a');
  return (
    <RadioCards
      legend="Pick one"
      name="pick"
      value={value}
      onChange={v => { setValue(v); onChange?.(v); }}
      options={OPTIONS}
    />
  );
};

/**
 * Ce que remplace ce composant : des `<button aria-pressed>` dans un `<fieldset>`. Ils se
 * comportaient en cases indépendantes tout en signifiant un choix exclusif — cinq arrêts de
 * tabulation pour cinq fournisseurs, les flèches sans effet, et rien qui annonce « 2 sur 5 ».
 */
describe('RadioCards', () => {
  it('is a radio group, not a row of buttons', () => {
    render(<Harness />);

    expect(screen.getByRole('group', { name: 'Pick one' })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('marks exactly one as chosen', () => {
    render(<Harness />);

    expect(screen.getByRole('radio', { name: /Alpha/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /Beta/ })).not.toBeChecked();
  });

  it('reports the value that was picked', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);

    await user.click(screen.getByRole('radio', { name: /Beta/ }));

    expect(onChange).toHaveBeenCalledWith('b');
    expect(screen.getByRole('radio', { name: /Beta/ })).toBeChecked();
  });

  /*
   * Le groupe entier vaut un arrêt de tabulation, et les flèches y circulent : c'est le
   * comportement natif d'un `name` partagé, et c'est précisément ce que la version en boutons ne
   * pouvait pas offrir.
   */
  it('takes one tab stop and moves with the arrow keys', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.tab();
    expect(screen.getByRole('radio', { name: /Alpha/ })).toHaveFocus();

    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: /Beta/ })).toHaveFocus();
    expect(screen.getByRole('radio', { name: /Beta/ })).toBeChecked();
  });

  /* `sr-only` et non `hidden` : l'entrée doit rester focalisable pour que tout ceci existe. */
  it('keeps the input reachable rather than hiding it', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.tab();

    expect(document.activeElement).toBe(screen.getByRole('radio', { name: /Alpha/ }));
  });

  it('renders an option that carries no description', () => {
    render(<Harness />);
    expect(screen.getByRole('radio', { name: /Gamma/ })).toBeInTheDocument();
  });
});
