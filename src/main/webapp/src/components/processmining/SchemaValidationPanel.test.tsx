// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import SchemaValidationPanel, { FieldProfileResult } from './SchemaValidationPanel';

const topic = (name: string) => ({
  name,
  format: 'JSON',
  fields: [],
  candidateCorrelationKeys: [],
  candidateTimestamps: [],
  candidateStatuses: [],
});

const profile = (overrides: Partial<FieldProfileResult> = {}): FieldProfileResult => ({
  topics: [topic('orders'), topic('payments')],
  unificationProposal: {
    correlationId: {
      canonicalName: 'correlationId',
      mappings: { orders: '$.orderId' },
      confidence: 0.9,
      conflicts: [],
    },
    timestamp: null,
    status: null,
    amount: null,
    warnings: [],
  },
  warnings: [],
  ...overrides,
});

/**
 * The ids are part of the contract, not an implementation detail: they are what the panel focuses
 * when a submitted path cannot match anything. Four roles × N topics makes a bare label lookup
 * ambiguous, so the field is addressed the way the component itself addresses it.
 */
const field = (role: string, name: string) =>
  document.getElementById(`mapping-${role}-${name}`) as HTMLInputElement;

const submit = () =>
  fireEvent.click(screen.getByRole('button', { name: /Validate and Launch Analysis/ }));

describe('SchemaValidationPanel', () => {
  /** The provider is a choice — the page's own banner says Ollama by default. */
  it('names the model that produced the proposal rather than asserting Claude', () => {
    render(<SchemaValidationPanel result={profile()} onValidate={vi.fn()} providerLabel="Ollama" />);

    expect(screen.getByText(/proposed by Ollama/)).toBeTruthy();
    expect(screen.queryByText(/detected by Claude/)).toBeNull();
  });

  /** Each box is labelled by its topic — it used to carry a placeholder and nothing else. */
  it('labels every field with the topic it maps', () => {
    render(<SchemaValidationPanel result={profile()} onValidate={vi.fn()} />);

    const input = field('correlationId', 'orders');
    const label = document.querySelector('label[for="mapping-correlationId-orders"]');
    expect(label?.textContent).toBe('orders');
    expect(input.value).toBe('$.orderId');
  });

  /**
   * The editor used to render a role's rows from the proposal alone and return null when there were
   * none — so a topic the model said nothing about had no box to type into, and the panel whose
   * whole purpose is correcting the mapping could only adjust what the model had already found.
   */
  it('offers a row for every profiled topic, not only the ones the model mapped', () => {
    render(<SchemaValidationPanel result={profile()} onValidate={vi.fn()} />);

    // 'payments' carries no proposed mapping, and is exactly the one worth supplying.
    expect(field('correlationId', 'payments').value).toBe('');
  });

  /** A role the model found nothing for is shown, and marked as such, rather than being absent. */
  it('shows a role the model did not detect', () => {
    render(<SchemaValidationPanel result={profile()} onValidate={vi.fn()} />);

    expect(screen.getByText('Timestamp')).toBeTruthy();
    expect(field('timestamp', 'orders')).toBeTruthy();
    expect(screen.getAllByText('NOT DETECTED').length).toBe(3);
  });

  it('sends the supplied path along with the proposed one', () => {
    const onValidate = vi.fn();
    render(<SchemaValidationPanel result={profile()} onValidate={onValidate} />);

    fireEvent.change(field('correlationId', 'payments'), { target: { value: '$.paymentRef' } });
    submit();

    expect(onValidate).toHaveBeenCalledWith({
      correlationId: { orders: '$.orderId', payments: '$.paymentRef' },
    });
  });

  /**
   * A path that cannot match anything is refused here rather than sailing through to an analysis
   * whose only symptom is an empty correlation — the failure furthest from its cause.
   */
  it('refuses a path that cannot match anything, and says which', () => {
    const onValidate = vi.fn();
    render(<SchemaValidationPanel result={profile()} onValidate={onValidate} />);

    fireEvent.change(field('correlationId', 'payments'), { target: { value: '$.ref[0' } });
    submit();

    expect(onValidate).not.toHaveBeenCalled();
    expect(screen.getByText(/Unbalanced brackets/)).toBeTruthy();
    expect(field('correlationId', 'payments').getAttribute('aria-invalid')).toBe('true');
  });

  it('drops the roles left entirely blank', () => {
    const onValidate = vi.fn();
    render(<SchemaValidationPanel result={profile()} onValidate={onValidate} />);

    submit();

    // Only correlationId carried a value; the other three roles are absent rather than empty.
    expect(onValidate).toHaveBeenCalledWith({ correlationId: { orders: '$.orderId' } });
  });

  /**
   * A model that proposed nothing at all is the case where typing the mapping by hand matters most,
   * and it used to be the one case with nowhere to type it.
   */
  it('still lets the mapping be supplied when the model proposed nothing', () => {
    const onValidate = vi.fn();
    render(
      <SchemaValidationPanel
        result={profile({ unificationProposal: null })}
        onValidate={onValidate}
      />);

    fireEvent.change(field('correlationId', 'orders'), { target: { value: '$.id' } });
    submit();

    expect(onValidate).toHaveBeenCalledWith({ correlationId: { orders: '$.id' } });
  });

  it('says there is nothing to map when no topic was profiled', () => {
    render(<SchemaValidationPanel result={profile({ topics: [] })} onValidate={vi.fn()} />);

    expect(screen.getByText(/No topic was profiled/)).toBeTruthy();
  });
});
