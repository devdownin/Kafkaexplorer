// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import { describeResume, resumableStep } from './processMiningDraft';
import type { Step } from './processMiningDraft';

const draft = (over: Partial<Parameters<typeof resumableStep>[0]> = {}) => ({
  step: 'SELECT' as Step,
  analysisMode: 'SNAPSHOT' as const,
  hasProfile: false,
  hasMapping: false,
  hasSnapshot: false,
  ...over,
});

describe('resumableStep', () => {
  it('never reopens on the profiling wait screen', () => {
    // La requête est morte avec la page : l'étape afficherait un chargement sans fin.
    expect(resumableStep(draft({ step: 'PROFILING', hasProfile: true }))).toBe('SELECT');
  });

  it('reopens a validated schema', () => {
    expect(resumableStep(draft({ step: 'VALIDATE', hasProfile: true }))).toBe('VALIDATE');
  });

  it('falls back when the data the step needs is missing', () => {
    expect(resumableStep(draft({ step: 'VALIDATE' }))).toBe('SELECT');
    expect(resumableStep(draft({ step: 'ANALYZE', hasProfile: true }))).toBe('VALIDATE');
    expect(resumableStep(draft({ step: 'ANALYZE' }))).toBe('SELECT');
  });

  it('reopens the analysis step once the mapping exists', () => {
    expect(resumableStep(draft({ step: 'ANALYZE', hasMapping: true }))).toBe('ANALYZE');
  });

  it('reopens a snapshot result', () => {
    expect(resumableStep(draft({ step: 'RESULTS', hasMapping: true, hasSnapshot: true }))).toBe('RESULTS');
  });

  it('sends a live run back to its launch screen', () => {
    // Le flux SSE est fermé : rouvrir « Results » présenterait un instantané mort comme un direct.
    expect(resumableStep(draft({ step: 'RESULTS', analysisMode: 'LIVE', hasMapping: true })))
      .toBe('ANALYZE');
  });

  it('does not show a results screen with no result', () => {
    expect(resumableStep(draft({ step: 'RESULTS', hasMapping: true }))).toBe('ANALYZE');
  });
});

describe('describeResume', () => {
  it('says nothing when there was nothing to resume', () => {
    expect(describeResume('SELECT', 'SELECT')).toBeNull();
  });

  it('explains a step that could not be restored as it was', () => {
    expect(describeResume('SELECT', 'PROFILING')).toMatch(/start it again/i);
    expect(describeResume('ANALYZE', 'RESULTS')).toMatch(/live session/i);
  });

  it('says a resumed pipeline is a resumed pipeline', () => {
    expect(describeResume('VALIDATE', 'VALIDATE')).toMatch(/left off/i);
  });
});
