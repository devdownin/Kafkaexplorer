// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import type { LlmModelCheck } from '../api/types';
import { describeModelCheck, describeModelIdentity, hasModelWarning } from './llmModelCheck';

const check = (over: Partial<LlmModelCheck> = {}): LlmModelCheck => ({
  id: 'openai/gpt-4o-mini',
  name: 'GPT-4o mini',
  contextLength: 128_000,
  emitsText: true,
  schemaSupport: 'CONSTRAINED',
  reasoningMandatory: null,
  promptBudgetTokens: 34_096,
  promptBudgetFits: true,
  availableToKey: null,
  error: null,
  ...over,
});

const texts = (c: LlmModelCheck | null | undefined) =>
  describeModelCheck(c).map(note => note.text).join(' | ');

describe('describeModelCheck', () => {
  it('says nothing at all when the catalogue was not consulted', () => {
    expect(describeModelCheck(null)).toEqual([]);
    expect(describeModelCheck(undefined)).toEqual([]);
  });

  it('reports why the lookup failed instead of grading a model it never read', () => {
    const notes = describeModelCheck(check({ error: 'OpenRouter answered HTTP 404 for "x/y".' }));
    expect(notes).toHaveLength(1);
    expect(notes[0].tone).toBe('unknown');
    expect(notes[0].text).toContain('404');
  });

  /*
   * Le seul cas que la liste par clé départage : une clé d'organisation restreinte reçoit la même
   * 404 qu'un slug mal tapé, et les deux envoient l'opérateur à deux endroits différents.
   */
  it('says a refusal is an entitlement when the key demonstrably cannot reach the model', () => {
    const notes = describeModelCheck(check({
      error: 'OpenRouter answered HTTP 404 for "x/y".',
      availableToKey: false,
    }));
    expect(notes).toHaveLength(2);
    expect(notes[1].tone).toBe('warning');
    expect(notes[1].text).toContain('entitlement');
  });

  it('stays silent about entitlement when it could not be established', () => {
    for (const availableToKey of [null, true] as const) {
      const notes = describeModelCheck(check({ error: 'HTTP 404', availableToKey }));
      expect(notes).toHaveLength(1);
    }
  });

  /*
   * La règle qui gouverne tout ce module : un fait absent ne produit pas de phrase. Une modalité
   * non rapportée qui s'afficherait « ce modèle n'émet pas de texte » serait un refus inventé à
   * partir d'un champ manquant.
   */
  it('does not turn an unreported fact into a refusal', () => {
    const notes = describeModelCheck(check({
      emitsText: null,
      schemaSupport: 'UNKNOWN',
      reasoningMandatory: null,
      contextLength: null,
      promptBudgetFits: null,
    }));
    expect(notes.every(note => note.tone !== 'warning')).toBe(true);
    expect(texts(check({ emitsText: null }))).not.toContain('does not emit text');
  });

  it('flags a model that cannot emit text at all', () => {
    const notes = describeModelCheck(check({ emitsText: false }));
    expect(notes[0].tone).toBe('warning');
    expect(notes[0].text).toContain('does not emit text');
  });

  /*
   * Le cas que le code en fonctionnement ne peut pas voir : champ accepté, schéma ignoré, aucune
   * erreur. Il doit se distinguer d'un refus franc, qui lui coûte une requête et se répare tout
   * seul.
   */
  it('tells an accepted-but-ignored schema from an outright refusal', () => {
    const accepted = texts(check({ schemaSupport: 'ACCEPTED_UNCONSTRAINED' }));
    expect(accepted).toContain('does not enforce it');
    expect(accepted).toContain('No error is raised');

    const refused = texts(check({ schemaSupport: 'UNSUPPORTED' }));
    expect(refused).toContain('one extra request');
    expect(refused).not.toContain('No error is raised');
  });

  it('says nothing about schemas when the catalogue did not say', () => {
    expect(texts(check({ schemaSupport: 'UNKNOWN' }))).not.toMatch(/schema/i);
  });

  it('warns only when reasoning is actually mandatory', () => {
    expect(texts(check({ reasoningMandatory: true }))).toContain('claude.max-tokens');
    expect(texts(check({ reasoningMandatory: false }))).not.toContain('claude.max-tokens');
    expect(texts(check({ reasoningMandatory: null }))).not.toContain('claude.max-tokens');
  });

  /*
   * Le mot « floor » est le contrat de cette phrase : le ratio est optimiste, donc un budget qui
   * passe peut malgré tout ne pas tenir. Sans lui la phrase promettrait ce que seule la
   * tokenisation du modèle décide.
   */
  it('states the budget verdict as a floor, never as a calibration', () => {
    expect(texts(check({ promptBudgetFits: true }))).toContain('floor');
  });

  it('warns when the prompt budget exceeds the window, and names the fix', () => {
    const notes = describeModelCheck(check({ contextLength: 8192, promptBudgetFits: false }));
    const budget = notes[notes.length - 1];
    expect(budget.tone).toBe('warning');
    expect(budget.text).toContain('process-mining.prompt-char-budget');
    expect(budget.text).toContain('truncated in silence');
  });

  it('refuses a budget verdict when the window is unpublished', () => {
    const notes = describeModelCheck(check({ contextLength: null, promptBudgetFits: null }));
    const budget = notes[notes.length - 1];
    expect(budget.tone).toBe('unknown');
    expect(budget.text).toContain('not published');
  });

  it('puts what blocks an analysis before what merely degrades it', () => {
    const notes = describeModelCheck(check({
      emitsText: false,
      schemaSupport: 'UNSUPPORTED',
      reasoningMandatory: true,
    }));
    expect(notes[0].text).toContain('does not emit text');
  });

  it('formats token counts for reading', () => {
    expect(texts(check())).toContain((34_096).toLocaleString());
  });
});

describe('describeModelIdentity', () => {
  it('names the model the catalogue answered for, which aliases make worth stating', () => {
    expect(describeModelIdentity(check({ id: 'openai/gpt-4o-mini', name: 'GPT-4o mini' })))
      .toBe('GPT-4o mini (openai/gpt-4o-mini)');
  });

  it('falls back to the slug when there is no display name', () => {
    expect(describeModelIdentity(check({ name: null }))).toBe('openai/gpt-4o-mini');
  });

  it('names nothing when the lookup failed', () => {
    expect(describeModelIdentity(check({ error: 'nope' }))).toBeNull();
  });
});

describe('hasModelWarning', () => {
  it('is false for a model whose every note is reassuring or unknown', () => {
    expect(hasModelWarning(describeModelCheck(check()))).toBe(false);
    expect(hasModelWarning(describeModelCheck(check({ error: 'could not ask' })))).toBe(false);
  });

  it('is true as soon as one note asks for a change', () => {
    expect(hasModelWarning(describeModelCheck(check({ emitsText: false })))).toBe(true);
  });
});
