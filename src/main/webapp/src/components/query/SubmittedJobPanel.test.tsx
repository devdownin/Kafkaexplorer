// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { SubmittedJobPanel } from './SubmittedJobPanel';
import type { FlinkJobSummary, FlinkManagedJobDetails } from '../../api/types';

/*
 * Ce panneau montrait le statut lu ~150 ms après la soumission, et plus rien : un job mort à sa
 * première ligne restait « Flink job submitted », en vert, pour toujours. Ce qui est vérifié ici
 * est ce que le vert ne doit plus couvrir.
 */

const submission: FlinkJobSummary = {
  queryId: 'q-1',
  flinkJobId: 'f-1',
  statementType: 'INSERT',
  executionMode: 'ASYNC_JOB',
  status: 'RUNNING',
  sql: 'INSERT INTO sink SELECT id FROM src',
  startedAt: 1_700_000_000_000,
  endedAt: null,
  cancelRequested: false,
};

const details = (over: Partial<FlinkManagedJobDetails>): FlinkManagedJobDetails => ({
  queryId: 'q-1',
  flinkJobId: 'f-1',
  statementType: 'INSERT',
  executionMode: 'ASYNC_JOB',
  status: 'RUNNING',
  statusDetail: null,
  sql: submission.sql,
  startedAt: 1_700_000_000_000,
  endedAt: null,
  cancelRequested: false,
  cancelRequestedAt: null,
  errorMessage: null,
  lastUpdatedAt: 1_700_000_005_000,
  history: null,
  ...over,
});

const show = (props: Partial<React.ComponentProps<typeof SubmittedJobPanel>> = {}) => render(
  <MemoryRouter>
    <SubmittedJobPanel
      submission={submission}
      details={null}
      detailsError={null}
      polling={true}
      stopping={false}
      onStop={() => {}}
      {...props}
    />
  </MemoryRouter>,
);

describe('SubmittedJobPanel', () => {
  it('offers to stop a job that is still running', async () => {
    const onStop = vi.fn();
    show({ details: details({}), onStop });

    expect(screen.getByText('Flink job running')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }));
    expect(onStop).toHaveBeenCalled();
  });

  /** Le cas pour lequel ce panneau a été réécrit : le job est mort et l'écran le disait vert. */
  it('reports a job that failed after it was submitted, with the reason', () => {
    show({
      details: details({
        status: 'FAILED',
        endedAt: 1_700_000_004_000,
        errorMessage: 'Unsupported options found for kafka',
      }),
      polling: false,
    });

    expect(screen.getByText('Flink job failed')).toBeInTheDocument();
    expect(screen.getByText(/Unsupported options found for kafka/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument();
  });

  /**
   * « Nous n'avons pas su redemander » n'est pas « le job va bien » — la distinction que tout ce
   * sous-système a été corrigé pour tenir.
   */
  it('says the state could not be re-read, without calling the job ended', () => {
    show({ details: details({}), detailsError: 'the server did not answer' });

    expect(screen.getByText(/could not be re-read/)).toBeInTheDocument();
    expect(screen.getByText('Flink job running')).toBeInTheDocument();
  });

  it('offers to create the target table only when one is missing', async () => {
    const onCreateTarget = vi.fn();
    const { unmount } = show({ details: details({}) });
    expect(screen.queryByRole('button', { name: /^Create / })).not.toBeInTheDocument();
    unmount();

    show({ details: details({}), onCreateTarget, createTargetLabel: 'Create orders_out' });
    await userEvent.click(screen.getByRole('button', { name: 'Create orders_out' }));
    expect(onCreateTarget).toHaveBeenCalled();
  });
});
