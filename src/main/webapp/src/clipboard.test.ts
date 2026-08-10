import { describe, it, expect, vi } from 'vitest';
import { copyText } from './clipboard';

/** A document whose execCommand reports what it was asked and whether it succeeded. */
function fakeDoc(execResult: boolean | (() => boolean)) {
  const doc = document.implementation.createHTMLDocument('t');
  const exec = vi.fn(() => (typeof execResult === 'function' ? execResult() : execResult));
  (doc as unknown as { execCommand: () => boolean }).execCommand = exec;
  return { doc, exec };
}

describe('copyText', () => {
  it('uses the async clipboard when the origin is secure', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    await expect(copyText('SELECT 1', { clipboard: { writeText } })).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith('SELECT 1');
  });

  // The reported family of bug: on http://<host> the property is absent entirely, and the
  // previous code dereferenced it inside a click handler — the button did nothing, silently.
  it('falls back to execCommand when there is no clipboard at all', async () => {
    const { doc, exec } = fakeDoc(true);
    await expect(copyText('SELECT 1', { clipboard: undefined, doc })).resolves.toBe(true);
    expect(exec).toHaveBeenCalledWith('copy');
  });

  it('falls back when writeText rejects — a permissions policy, or an unfocused document', async () => {
    const { doc, exec } = fakeDoc(true);
    const writeText = vi.fn().mockRejectedValue(new Error('NotAllowedError'));
    await expect(copyText('x', { clipboard: { writeText }, doc })).resolves.toBe(true);
    expect(exec).toHaveBeenCalledWith('copy');
  });

  it('falls back when writeText throws synchronously', async () => {
    const { doc } = fakeDoc(true);
    const writeText = vi.fn(() => { throw new TypeError('not a function'); });
    await expect(copyText('x', { clipboard: { writeText } as never, doc })).resolves.toBe(true);
  });

  // Reporting the truth is the point: a caller that toasts "Copied" on a failure is the
  // same lie the Stop button told.
  it('reports false when both paths fail', async () => {
    const { doc } = fakeDoc(false);
    await expect(copyText('x', { clipboard: undefined, doc })).resolves.toBe(false);
  });

  it('reports false rather than throwing when execCommand blows up', async () => {
    const { doc } = fakeDoc(() => { throw new Error('boom'); });
    await expect(copyText('x', { clipboard: undefined, doc })).resolves.toBe(false);
  });

  it('reports false when there is no document to fall back to', async () => {
    await expect(copyText('x', { clipboard: undefined, doc: undefined })).resolves.toBe(false);
  });

  it('leaves no textarea behind, on success or on failure', async () => {
    const { doc } = fakeDoc(true);
    await copyText('kept clean', { clipboard: undefined, doc });
    expect(doc.querySelectorAll('textarea')).toHaveLength(0);

    const { doc: failing } = fakeDoc(() => { throw new Error('boom'); });
    await copyText('kept clean', { clipboard: undefined, doc: failing });
    expect(failing.querySelectorAll('textarea')).toHaveLength(0);
  });

  it('copies the exact text, including newlines', async () => {
    const { doc } = fakeDoc(true);
    const sql = 'SELECT *\nFROM demo.orders\nWHERE id = 1';
    let seen = '';
    (doc as unknown as { execCommand: () => boolean }).execCommand = () => {
      seen = doc.querySelector('textarea')?.value ?? '';
      return true;
    };
    await copyText(sql, { clipboard: undefined, doc });
    expect(seen).toBe(sql);
  });
});
