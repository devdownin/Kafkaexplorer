// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Segmented } from './Segmented';

const OPTIONS = [{ value: 'a', label: 'Alpha' }, { value: 'b', label: 'Beta' }];

describe('Segmented', () => {
  it('exposes each option as a pressed/unpressed button', () => {
    render(<Segmented ariaLabel="Mode" value="a" onChange={() => {}} options={OPTIONS} />);
    expect(screen.getByRole('group', { name: 'Mode' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Alpha' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Beta' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('reports the chosen value', async () => {
    const onChange = vi.fn();
    render(<Segmented ariaLabel="Mode" value="a" onChange={onChange} options={OPTIONS} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Beta' }));
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('is a button and not a submit, so it is safe inside a form', () => {
    render(<Segmented ariaLabel="Mode" value="a" onChange={() => {}} options={OPTIONS} />);
    expect(screen.getByRole('button', { name: 'Alpha' })).toHaveAttribute('type', 'button');
  });

  it('keeps the selected option readable while there is no layout to place the pill', () => {
    render(<Segmented ariaLabel="Mode" value="b" onChange={() => {}} options={OPTIONS} />);
    expect(screen.getByRole('button', { name: 'Beta' })).toHaveClass('bg-surface-container-highest');
  });
});
