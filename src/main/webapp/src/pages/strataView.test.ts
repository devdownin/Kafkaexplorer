// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import { readStrataView, shareStrataUrl, writeStrataView, strataRenderBudget } from './strataView';

describe('Kafka Strata shareable views', () => {
  it('round-trips a focus/compare view without affecting unrelated query parameters', () => {
    const view = { mode: 'compare' as const, topic: 'orders', peer: 'invoices', focus: 'orders',
      filter: 'ord', bucket: 4, quality: 'detail' as const };
    const url = writeStrataView(new URLSearchParams('from=explorer&sv.topic=old'), view);
    expect(url.get('from')).toBe('explorer');
    expect(readStrataView(url)).toEqual(view);
    expect([...url.keys()].filter(key => key === 'sv.topic')).toHaveLength(1);
  });

  it('strips unrelated query parameters and URL fragments from shared links', () => {
    const link = shareStrataUrl('https://example.test/strata?messageKey=secret&token=abc#oauth-fragment', {
      mode: 'compare', topic: 'orders', peer: 'invoices', focus: null,
      filter: '', bucket: 11, quality: 'auto',
    });
    expect(link).toContain('sv.topic=orders');
    expect(link).toContain('sv.peer=invoices');
    expect(link).not.toContain('secret');
    expect(link).not.toContain('token');
    expect(link).not.toContain('#');
  });

  it('rejects invalid links and does not restore mismatched focus or same-topic peers', () => {
    const invalid = readStrataView(new URLSearchParams(
      'sv.topic=orders&sv.peer=orders&sv.focus=other&sv.bucket=-1&sv.quality=extreme&sv.mode=x',
    ));
    expect(invalid).toMatchObject({
      topic: 'orders', peer: null, focus: null, bucket: 11, quality: 'auto', mode: 'investigate',
    });
    expect(readStrataView(new URLSearchParams('sv.topic=' + 'X'.repeat(250))).topic).toBeNull();
  });

  it('does not serialize a peer outside Compare or leak unrelated trace data', () => {
    const params = writeStrataView(new URLSearchParams('sv.peer=old'), {
      mode: 'investigate', topic: 'orders', peer: 'other', focus: null, filter: '',
      bucket: 11, quality: 'auto',
    });
    expect(params.get('sv.peer')).toBeNull();
    expect(params.has('messageKey')).toBe(false);
  });
});

describe('Kafka Strata GPU budget', () => {
  it('reduces device pixel ratio and geometry on a dense cluster', () => {
    expect(strataRenderBudget('auto', 48, 3)).toEqual({
      pixelRatio: 1, frameIntervalMs: 67, ringSegments: 24,
    });
    expect(strataRenderBudget('auto', 8, 3).ringSegments).toBe(48);
    expect(strataRenderBudget('economy', 1, 2).pixelRatio).toBe(1);
    expect(strataRenderBudget('detail', 48, 3).pixelRatio).toBe(2);
  });
});
