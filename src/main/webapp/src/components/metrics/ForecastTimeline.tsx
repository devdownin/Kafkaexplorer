// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import type { ForecastSeriesView } from '../../api/types';
import { forecastTimeline } from './forecastStage';
import type { StepState } from './forecastStage';

const MARK: Record<StepState, { icon: string; tone: string; text: string }> = {
  done: { icon: 'check_circle', tone: 'text-success', text: 'done' },
  current: { icon: 'radio_button_checked', tone: 'text-primary', text: 'in progress' },
  pending: { icon: 'radio_button_unchecked', tone: 'text-outline', text: 'not started' },
  failed: { icon: 'error', tone: 'text-error', text: 'blocked' },
};

/** History → forecast → quality → approval, in one row; each step's detail has a stable id. */
export function ForecastTimeline({ series }: { series: ForecastSeriesView }) {
  return <ol aria-label="Forecast progress" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
    {forecastTimeline(series).map(s => <li key={s.key} aria-current={s.state === 'current' ? 'step' : undefined}
      className="flex gap-2 items-start">
      <span className={`material-symbols-outlined ${MARK[s.state].tone}`} aria-hidden="true">{MARK[s.state].icon}</span>
      <div>
        <p className="font-semibold">{s.label}<span className="sr-only"> ({MARK[s.state].text})</span></p>
        <p id={`forecast-step-${s.key}`} className="text-sm text-on-surface-variant">{s.detail}</p>
      </div>
    </li>)}
  </ol>;
}
