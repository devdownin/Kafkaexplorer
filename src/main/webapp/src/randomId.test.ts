import { describe, it, expect } from 'vitest';
import { randomId } from './randomId';

/** The backend accepts a client-supplied query id only when it matches this — anything
 *  else and it mints its own, leaving the cancel endpoint unable to find the job. */
const BACKEND_QUERY_ID = /^[A-Za-z0-9_-]{8,64}$/;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** A crypto stand-in exposing only getRandomValues — what a browser gives you on an
 *  insecure origin, where randomUUID is simply absent. */
const insecureContext = {
  getRandomValues: <T extends ArrayBufferView>(array: T): T => {
    const bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = (i * 37 + 11) % 256;
    return array;
  },
} as unknown as Crypto;

describe('randomId', () => {
  it('uses randomUUID when the origin is secure', () => {
    const secure = { randomUUID: () => '11111111-2222-4333-8444-555555555555' } as unknown as Crypto;
    expect(randomId(secure)).toBe('11111111-2222-4333-8444-555555555555');
  });

  // The reported bug: on http://<host> the property does not exist, and calling it threw
  // before the try block, stranding the page in "executing" with a dead Stop button.
  it('falls back to getRandomValues when randomUUID is absent', () => {
    const id = randomId(insecureContext);
    expect(id).toMatch(UUID_V4);
  });

  it('falls back when randomUUID exists but throws', () => {
    const hostile = {
      randomUUID: () => { throw new Error('SecurityError'); },
      getRandomValues: insecureContext.getRandomValues,
    } as unknown as Crypto;
    expect(randomId(hostile)).toMatch(UUID_V4);
  });

  it('still returns an id when no crypto is available at all', () => {
    expect(randomId(undefined)).toMatch(UUID_V4);
  });

  it('never throws, whatever it is handed — that is the whole contract', () => {
    const broken = {
      get randomUUID() { throw new Error('boom'); },
      get getRandomValues() { throw new Error('boom'); },
    } as unknown as Crypto;
    expect(() => randomId(broken)).not.toThrow();
    expect(randomId(broken)).toMatch(UUID_V4);
  });

  // Shape is not cosmetic here: a rejected id is silently replaced server-side and the
  // cancel endpoint then addresses a job that does not exist under that name.
  it.each([
    ['secure', { randomUUID: () => 'abcdef01-2345-4678-89ab-cdef01234567' } as unknown as Crypto],
    ['insecure', insecureContext],
    ['no crypto', undefined],
  ])('produces an id the backend will accept (%s)', (_case, source) => {
    expect(randomId(source)).toMatch(BACKEND_QUERY_ID);
  });

  it('does not repeat itself across calls', () => {
    const ids = new Set(Array.from({ length: 200 }, () => randomId(undefined)));
    expect(ids.size).toBe(200);
  });
});
