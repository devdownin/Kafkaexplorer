/*
 * Recovery from a lazy chunk that no longer exists on the server.
 *
 * Every page in this app is `React.lazy`, and Vite emits content-hashed filenames
 * (`Metrics-axy9HlH6.js`). A browser holding the previous build's `index.html` — a tab left
 * open across a redeploy, or a heuristically cached document — asks for a chunk name that
 * the new build does not have, the dynamic import rejects, and react-router surfaces its
 * default error page: a stack trace, followed by advice addressed to a developer. An
 * operator watching a cluster gets that instead of the Metrics page, and nothing on the
 * screen says the one thing that fixes it.
 *
 * Reloading fixes it deterministically: the document is re-fetched, the new `index.html`
 * names the new chunks. So the recovery is automatic — but exactly once, because if the
 * asset is missing for any other reason (a broken deployment, a proxy serving 404s) an
 * unguarded reload is an infinite loop pointed at a server that is already unwell.
 */

/** Key under which the last automatic reload is recorded. `sessionStorage`, not `local`:
 *  the guard should last for this tab's visit and not outlive the incident. */
const RELOAD_KEY = 'kse:stale-chunk-reload';

/** A second reload within this window is refused and the error is shown instead. Long
 *  enough to cover a slow reload of a large bundle, short enough that a redeploy an hour
 *  later still recovers on its own. */
export const RELOAD_GUARD_MS = 30_000;

/*
 * Each engine words this differently, and there is no error *type* to match on — only the
 * message. Chrome and Edge say "Failed to fetch dynamically imported module", Firefox
 * "error loading dynamically imported module", Safari "Importing a module script failed".
 * Vite's own CSS preloader adds "Unable to preload CSS". Matching the union is the only
 * portable option; matching too widely would reload the page on unrelated failures, so
 * each pattern is anchored on wording specific to module loading.
 */
const STALE_CHUNK_PATTERNS = [
  /failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /importing a module script failed/i,
  /unable to preload css/i,
];

/** Pulls a comparable string out of whatever was thrown — an Error, a plain string, a
 *  react-router ErrorResponse, or an object with a `message`. */
function messageOf(error: unknown): string {
  if (typeof error === 'string') return error;
  if (error instanceof Error) return error.message;
  if (error && typeof error === 'object') {
    const candidate = (error as { message?: unknown }).message;
    if (typeof candidate === 'string') return candidate;
  }
  return '';
}

/** True when the failure is a lazy chunk the server no longer serves. */
export function isStaleChunkError(error: unknown): boolean {
  const message = messageOf(error);
  if (!message) return false;
  return STALE_CHUNK_PATTERNS.some((pattern) => pattern.test(message));
}

/**
 * Whether an automatic reload is allowed, recording it when it is.
 *
 * Storage is injected so this is testable without a browser, and every access is guarded:
 * `sessionStorage` throws in some privacy modes, and a recovery path that itself throws
 * would replace a recoverable error with an unrecoverable one.
 */
export function shouldReload(
  now: number = Date.now(),
  storage: Pick<Storage, 'getItem' | 'setItem'> | null = safeSessionStorage(),
): boolean {
  if (!storage) return false;
  try {
    const previous = Number(storage.getItem(RELOAD_KEY));
    if (Number.isFinite(previous) && previous > 0 && now - previous < RELOAD_GUARD_MS) {
      return false;
    }
    storage.setItem(RELOAD_KEY, String(now));
    return true;
  } catch {
    // Storage unavailable: refuse the reload rather than risk looping with no way to
    // remember that we already tried.
    return false;
  }
}

function safeSessionStorage(): Pick<Storage, 'getItem' | 'setItem'> | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Registers the recovery. Vite fires `vite:preloadError` on the window when a lazily
 * imported chunk fails to load, before the rejection reaches React — catching it here
 * recovers without the user ever seeing an error screen.
 *
 * `preventDefault()` stops Vite's own default (rethrowing) once we have decided to reload.
 */
export function installStaleChunkRecovery(target: Window = window): void {
  target.addEventListener('vite:preloadError', (event) => {
    if (!shouldReload()) return;
    (event as Event).preventDefault();
    target.location.reload();
  });
}
