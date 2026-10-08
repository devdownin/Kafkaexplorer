// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import axios from 'axios';
import { ForecastPreparation } from './ForecastPreparation';
vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const check = (id: string, state: string) => ({ id, state, detail: `${id} detail`, action: `${id} action` });
const readiness = (postgres: string, timesfm: string) => ({ checkedAt: 1, probed: false, checks: [
  check('pilot', 'READY'), check('tools', 'CONFIG_REQUIRED'), check('postgres', postgres), check('timesfm', timesfm),
  check('series', 'CONFIG_REQUIRED'), check('scope', 'CONFIG_REQUIRED'), check('history', 'NOT_CHECKED')] });

describe('ForecastPreparation', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);
  it('says once that the stack is missing instead of listing every consequence', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: readiness('CONFIG_REQUIRED', 'CONFIG_REQUIRED') });
    render(<ForecastPreparation onConfigure={() => {}} />);
    expect(await screen.findByText('The forecast stack is not running.')).toBeTruthy();
    expect(screen.getByText('bin/forecast-stack.sh')).toBeTruthy();
    const details = screen.getByText('All 7 checks').closest('details')!;
    expect(details).not.toHaveAttribute('open');
    expect(details).toHaveTextContent('tools: CONFIG REQUIRED');
    expect(screen.queryByRole('button', { name: 'Test PostgreSQL and TimesFM' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Configure a forecast' })).toBeTruthy();
  });
  it('lists every check, and offers the probe, once one dependency is configured', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: readiness('NOT_CHECKED', 'CONFIG_REQUIRED') });
    render(<ForecastPreparation />);
    expect(await screen.findByText('timesfm action')).toBeTruthy();
    expect(screen.queryByText('The forecast stack is not running.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Test PostgreSQL and TimesFM' })).toBeTruthy();
  });
});
