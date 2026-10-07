// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { ForecastProgress } from './ForecastProgress';
import type { ForecastProgress as Progress, ForecastRecord } from '../../api/types';
const progress = { seriesId: 'series', state: 'WARMING_UP', reason: 'Need more observations', observedPoints: 40, requiredPoints: 512,
  missingPoints: 472, imputedPoints: 0, checkedAt: 1, nextScheduledAt: 100, stepMillis: 60000, horizon: 30,
  topics: ['orders'], groups: ['worker'], breach: null, breachState: 'NOT_CONFIGURED' } as unknown as Progress;
describe('ForecastProgress', () => {
  afterEach(cleanup);
  it('shows actual history progress and the preparation reason', () => {
    render(<ForecastProgress progress={progress} error="" result={null} />);
    expect(screen.getByText(/40 \/ 512 observed points/)).toBeTruthy();
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '40');
    expect(screen.getByText('Need more observations')).toBeTruthy();
    expect(screen.queryByText(/bounded queue/)).toBeNull();
  });
  it('shows an unavailable history without inventing zero measurements', () => {
    const unavailable = { ...progress, state: 'UNAVAILABLE', reason: 'History read failed', observedPoints: null, missingPoints: null, imputedPoints: null } as unknown as Progress;
    render(<ForecastProgress progress={unavailable} error="" result={null} />);
    expect(screen.getByText('History read failed')).toBeTruthy(); expect(screen.queryByRole('progressbar')).toBeNull();
  });
  it('reports the poll error the panel hands it', () => {
    render(<ForecastProgress progress={null} error="History progress unavailable." result={null} />);
    expect(screen.getByRole('status')).toHaveTextContent('History progress unavailable.');
  });
  it('does not present a breach from another persisted result', () => {
    const stale = { ...progress, breachState: 'EVALUATED', breach: { resultKey: 'old', threshold: { breached: true, threshold: 3, direction: 'ABOVE' } } } as unknown as Progress;
    const result = { key: 'new', context: { points: [], outputUnit: 'messages' }, forecast: null, quality: null, state: 'READY', visibility: 'SHADOW' } as unknown as ForecastRecord;
    render(<ForecastProgress progress={stale} error="" result={result} />);
    expect(screen.getByText('Not evaluated for this result')).toBeTruthy();
    expect(screen.queryByText(/Predicted · ABOVE/)).toBeNull();
  });
});
