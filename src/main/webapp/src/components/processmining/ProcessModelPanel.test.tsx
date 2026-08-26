// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ProcessModelPanel from './ProcessModelPanel';
import type { ProcessModel } from '../../api/types';

const model = (extra: Partial<ProcessModel> = {}): ProcessModel => ({
  available: true,
  unavailableReason: null,
  cases: 100,
  events: 300,
  eventsWithoutCase: 0,
  windowStartMs: 1_767_225_600_000,
  windowEndMs: 1_767_225_600_000 + 7_200_000,
  eventTimeSource: 'MAPPED_FIELD',
  activities: [],
  edges: [
    { from: 'received', to: 'validated', occurrences: 100, cases: 100,
      p50Ms: 800, p95Ms: 3_200, maxMs: 41_000, outOfOrderCount: 0 },
    { from: 'validated', to: 'enriched', occurrences: 91, cases: 91,
      p50Ms: 1_100, p95Ms: 9_000, maxMs: 12_000, outOfOrderCount: 2 },
  ],
  variants: [
    { path: ['received', 'validated', 'enriched'], cases: 91, example: 'ORD-1' },
    { path: ['received', 'validated'], cases: 9, example: 'ORD-42' },
  ],
  starts: [{ activity: 'received', cases: 100 }],
  ends: [{ activity: 'enriched', cases: 91 }, { activity: 'validated', cases: 9 }],
  repeats: [{ activity: 'received', casesAffected: 3, maxOccurrencesInOneCase: 2 }],
  spotlightCases: ['ORD-1', 'ORD-42'],
  variantsOmitted: 0,
  edgesOmitted: 0,
  notes: ['The window is a slice: a case whose first event predates it is missing its start.'],
  ...extra,
});

describe('ProcessModelPanel', () => {
  it('renders nothing when there is no measurement to show', () => {
    const { container } = render(<ProcessModelPanel model={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  /*
   * Le point de tout ceci : le graphe de successions directes était calculé et seul le modèle le
   * voyait, donc l'opérateur lisait un récit qu'il n'avait aucun moyen de recouper.
   */
  it('shows the transitions the narrative rests on, with their latencies', () => {
    render(<ProcessModelPanel model={model()} />);

    expect(screen.getByText('Measured process')).toBeInTheDocument();
    expect(screen.getByText(/100 cases/)).toBeInTheDocument();
    // `received` legitimately appears in the transitions table and again under repeated steps.
    expect(screen.getAllByText('received').length).toBeGreaterThan(0);
    expect(screen.getByText('3.2 s')).toBeInTheDocument();
    // Twice, and that is the assertion: once in the table, once in the "slowest hop" figure. If
    // the headline figure ever disagreed with the row it summarises, this is where it would show.
    expect(screen.getAllByText('9.0 s')).toHaveLength(2);
  });

  /* Deux horloges qui se contredisent est un constat sur le parc, pas un défaut à lisser. */
  it('marks a transition the broker saw in the opposite order', () => {
    render(<ProcessModelPanel model={model()} />);
    expect(screen.getByText('clock skew')).toBeInTheDocument();
    expect(screen.getByText(/skewed producer clock or a back-dated event/)).toBeInTheDocument();
  });

  it('states the variant shares rather than leaving the reader to divide', () => {
    render(<ProcessModelPanel model={model()} />);
    // 91 of 100 cases took the nominal variant *and* ended on `enriched`, so the share is written
    // in both sections — the same number answering two different questions.
    expect(screen.getAllByText('91.0%')).toHaveLength(2);
    expect(screen.getByText(/received → validated → enriched/)).toBeInTheDocument();
  });

  /*
   * Rien n'est appelé orphelin : ce qui est rendu est la distribution des fins, et la phrase le
   * dit — quelle activité doit terminer un processus est un fait métier qu'on n'a pas.
   */
  it('renders where cases ended as a distribution, not a verdict', () => {
    render(<ProcessModelPanel model={model()} />);
    expect(screen.getByText('Where cases ended')).toBeInTheDocument();
    expect(screen.getByText(/business fact the application does not have/)).toBeInTheDocument();
    expect(screen.queryByText(/orphan/i)).not.toBeInTheDocument();
  });

  /*
   * « Pas disponible » est un état, pas un processus vide. Afficher zéro cas et zéro transition
   * serait la mesure-qu'on-n'a-pas-prise déguisée en mesure valant zéro.
   */
  it('gives the reason when no event log could be built, instead of rows of zeros', () => {
    render(<ProcessModelPanel model={model({
      available: false,
      unavailableReason: 'No correlation id is mapped, so the records cannot be grouped into cases.',
      cases: 0,
      events: 0,
      eventsWithoutCase: 240,
    })} />);

    expect(screen.getByText(/No correlation id is mapped/)).toBeInTheDocument();
    expect(screen.getByText(/240 record\(s\) were read and digested all the same/))
      .toBeInTheDocument();
    expect(screen.queryByText('Where cases ended')).not.toBeInTheDocument();
    expect(screen.queryByText(/Transitions/)).not.toBeInTheDocument();
  });

  it('says the measurement covers every record read, not the prompt sample', () => {
    render(<ProcessModelPanel model={model()} />);
    expect(screen.getByText(/Counted over every record read, not over the sample shown to the model/))
      .toBeInTheDocument();
  });
});
