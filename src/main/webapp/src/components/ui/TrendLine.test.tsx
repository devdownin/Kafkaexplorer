// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TrendLine } from './TrendLine';

describe('TrendLine', () => {
  it('draws nothing for fewer than two points', () => {
    const { container } = render(<TrendLine values={[5]} label="x" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is an image named by what it shows', () => {
    render(<TrendLine values={[1, 3, 2]} label="Total over 3 refreshes" />);
    expect(screen.getByRole('img', { name: 'Total over 3 refreshes' })).toBeInTheDocument();
  });

  it('draws a constant series at mid-height instead of stretching noise', () => {
    const { container } = render(<TrendLine values={[7, 7, 7]} label="flat" height={40} />);
    expect(container.querySelectorAll('path')).toHaveLength(1);
    const d = container.querySelector('path')!.getAttribute('d')!;
    expect(d.match(/ 20\.0/g)).toHaveLength(3);
  });
});
