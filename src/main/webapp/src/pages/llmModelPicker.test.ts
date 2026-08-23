// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import type { LlmModelOption, LlmModelShortlist } from '../api/types';
import {
  PROJECTION_NOTE, describeOption, describeShortlist, formatContext, formatPricePerMillion,
  formatProjectedCost, optionSlugs,
} from './llmModelPicker';

const option = (over: Partial<LlmModelOption> = {}): LlmModelOption => ({
  id: 'openai/gpt-4o-mini',
  name: 'GPT-4o mini',
  contextLength: 128_000,
  schemaSupport: 'CONSTRAINED',
  reasoningMandatory: null,
  promptPriceUsdPerMillion: 0.15,
  completionPriceUsdPerMillion: 0.6,
  projectedCostUsd: 0.0069,
  ...over,
});

const list = (over: Partial<LlmModelShortlist> = {}): LlmModelShortlist => ({
  available: true,
  models: [option()],
  criteria: ['emits text', 'supports structured outputs'],
  error: null,
  ...over,
});

describe('describeOption', () => {
  it('reads out the window, the prices and the projected cost', () => {
    const parts = describeOption(option()).join(' · ');
    expect(parts).toContain('128k ctx');
    expect(parts).toContain('$0.150/M in');
    expect(parts).toContain('$0.600/M out');
    expect(parts).toContain('/ window');
  });

  /*
   * La règle qui traverse toute l'intégration : un fait absent ne produit rien. Un modèle sans prix
   * publié ne doit pas s'afficher « gratuit », ni sa fenêtre inconnue « 0 ».
   */
  it('says nothing about a price or a window that was not published', () => {
    const parts = describeOption(option({
      contextLength: null,
      promptPriceUsdPerMillion: null,
      completionPriceUsdPerMillion: null,
      projectedCostUsd: null,
    }));
    expect(parts).toEqual([]);
  });

  it('shows a free model as a real zero, not as an absent price', () => {
    const parts = describeOption(option({
      promptPriceUsdPerMillion: 0,
      completionPriceUsdPerMillion: 0,
      projectedCostUsd: 0,
    })).join(' · ');
    expect(parts).toContain('$0.000/M in');
    expect(parts).toContain('$0.00 / window');
  });

  /*
   * Le seul défaut qui vaut d'être crié dans une liste : accepté-mais-ignoré ne lèvera jamais
   * d'erreur, donc rien d'autre ne le dira. Un refus franc se répare tout seul.
   */
  it('flags a schema that is accepted but not enforced, distinctly from one that is refused', () => {
    expect(describeOption(option({ schemaSupport: 'ACCEPTED_UNCONSTRAINED' })).join(' '))
      .toContain('not enforced');
    expect(describeOption(option({ schemaSupport: 'UNSUPPORTED' })).join(' '))
      .toContain('no schema support');
    expect(describeOption(option({ schemaSupport: 'CONSTRAINED' })).join(' '))
      .not.toMatch(/schema/);
    expect(describeOption(option({ schemaSupport: 'UNKNOWN' })).join(' '))
      .not.toMatch(/schema/);
  });

  it('mentions mandatory reasoning only when it is mandatory', () => {
    expect(describeOption(option({ reasoningMandatory: true })).join(' ')).toContain('always reasons');
    expect(describeOption(option({ reasoningMandatory: false })).join(' ')).not.toContain('reasons');
    expect(describeOption(option({ reasoningMandatory: null })).join(' ')).not.toContain('reasons');
  });

  it('omits the price pair when only one half was published', () => {
    expect(describeOption(option({ completionPriceUsdPerMillion: null })).join(' '))
      .not.toContain('/M');
  });
});

describe('describeShortlist', () => {
  /*
   * Trois vides à ne pas confondre. Seul « rien ne correspond » dit quelque chose des modèles, et
   * il doit rappeler ce qui a été exigé — sinon un filtre trop serré se lit comme une panne.
   */
  it('tells apart not-asked, could-not-ask and nothing-matches', () => {
    expect(describeShortlist(null).tone).toBe('idle');

    const broken = describeShortlist(list({ available: false, models: [], error: 'HTTP 502' }));
    expect(broken.tone).toBe('unavailable');
    expect(broken.text).toContain('502');

    const empty = describeShortlist(list({ models: [] }));
    expect(empty.tone).toBe('empty');
    expect(empty.text).toContain('supports structured outputs');
  });

  it('states the criteria beside the count, so a filtered view is not read as the whole', () => {
    const ready = describeShortlist(list());
    expect(ready.tone).toBe('ready');
    expect(ready.text).toContain('1 model');
    expect(ready.text).toContain('emits text');
  });

  it('pluralises the count', () => {
    expect(describeShortlist(list({ models: [option(), option({ id: 'a/b' })] })).text)
      .toContain('2 models');
  });
});

describe('optionSlugs', () => {
  it('is empty when the catalogue could not be read, so no slug is suggested on a guess', () => {
    expect(optionSlugs(null)).toEqual([]);
    expect(optionSlugs(list({ available: false, models: [option()] }))).toEqual([]);
  });

  it('offers the slugs of an available list', () => {
    expect(optionSlugs(list())).toEqual(['openai/gpt-4o-mini']);
  });
});

describe('formatting', () => {
  it('keeps a sub-cent cost legible instead of rounding it to zero', () => {
    expect(formatProjectedCost(0.0069)).toBe('$0.006900');
    expect(formatProjectedCost(1.5)).toBe('$1.50');
    expect(formatProjectedCost(0)).toBe('$0.00');
  });

  it('renders prices per million and leaves an absent one absent', () => {
    expect(formatPricePerMillion(0.15)).toBe('$0.150/M');
    expect(formatPricePerMillion(12)).toBe('$12.00/M');
    expect(formatPricePerMillion(null)).toBeNull();
  });

  it('compacts a context window', () => {
    expect(formatContext(128_000)).toBe('128k ctx');
    expect(formatContext(512)).toBe('512 ctx');
    expect(formatContext(null)).toBeNull();
  });
});

describe('PROJECTION_NOTE', () => {
  /*
   * L'étiquette est le contrat de ce module : ailleurs un montant affiché est un montant qu'un
   * fournisseur a assumé, ici non. Elle doit dire les deux choses.
   */
  it('says the figure is projected and that it can understate', () => {
    expect(PROJECTION_NOTE).toContain('Projected');
    expect(PROJECTION_NOTE).toContain('understate');
  });
});
