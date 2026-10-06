// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import axios from 'axios';
import { ForecastProgress } from './ForecastProgress';
import type { ForecastRecord } from '../../api/types';
vi.mock('axios', () => ({ default: { get: vi.fn() } }));
const progress = { seriesId: 'series', state: 'WARMING_UP', reason: 'Need more observations', observedPoints: 40, requiredPoints: 512,
  missingPoints: 472, imputedPoints: 0, checkedAt: 1, nextScheduledAt: 100, stepMillis: 60000, horizon: 30,
  topics: ['orders'], groups: ['worker'], breach: null, breachState: 'NOT_CONFIGURED' };
describe('ForecastProgress', () => {
  beforeEach(() => vi.clearAllMocks()); afterEach(cleanup);
  it('shows actual history progress and the preparation reason', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: progress }); render(<ForecastProgress seriesId="series" result={null} />);
    expect(await screen.findByText(/40 \/ 512 observed buckets/)).toBeTruthy();
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '40');
    expect(screen.getByText('Need more observations')).toBeTruthy();
  });
  it('shows an unavailable history without inventing zero measurements', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { ...progress, state: 'UNAVAILABLE', reason: 'History read failed', observedPoints: null, missingPoints: null, imputedPoints: null } });
    render(<ForecastProgress seriesId="series" result={null} />);
    expect(await screen.findByText('History read failed')).toBeTruthy(); expect(screen.queryByRole('progressbar')).toBeNull();
  });
  it('does not present a breach from another persisted result', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { ...progress, breachState: 'EVALUATED', breach: { resultKey: 'old', threshold: { breached: true, threshold: 3, direction: 'ABOVE' } } } });
    const result = { key: 'new', context: { points: [], outputUnit: 'messages' }, forecast: null, quality: null, state: 'READY', visibility: 'SHADOW' } as unknown as ForecastRecord;
    render(<ForecastProgress seriesId="series" result={result} />);
    expect(await screen.findByText('Not evaluated for this result')).toBeTruthy();
    expect(screen.queryByText(/Predicted · ABOVE/)).toBeNull();
  });
});
