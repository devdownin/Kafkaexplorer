import { useEffect } from 'react';
import type { FC } from 'react';
import { useRouteError } from 'react-router-dom';
import { Button } from './ui';
import { isStaleChunkError, shouldReload } from '../staleChunk';

/*
 * The route-level error screen.
 *
 * Without an `errorElement`, react-router renders its own: the raw message, a stack trace,
 * and "💿 Hey developer 👋 — you can provide a way better UX than this". That text is
 * addressed to whoever built the app, and it is shown to whoever is using it. This replaces
 * it with something the reader can act on.
 *
 * It distinguishes the one error that has a known cure. A lazily imported page that the
 * server no longer has is what a redeploy does to an open tab, and reloading fixes it —
 * so this reloads, once, guarded by `shouldReload`. Everything else is reported as an
 * error, with the message kept: a screen that says only "something went wrong" is the
 * reason people open the console.
 */
const RouteError: FC = () => {
  const error = useRouteError();
  const stale = isStaleChunkError(error);

  useEffect(() => {
    // The `vite:preloadError` listener normally catches this first; this is the second
    // line, for a rejection that reached the router instead. `shouldReload` is the same
    // guard, so the two paths cannot reload in turn.
    if (stale && shouldReload()) window.location.reload();
  }, [stale]);

  const message =
    error instanceof Error ? error.message
    : typeof error === 'string' ? error
    : 'An unexpected error occurred.';

  if (stale) {
    return (
      <div className="flex flex-col items-center justify-center h-full min-h-[60vh] text-center px-6">
        <span aria-hidden="true" className="material-symbols-outlined text-[40px] text-primary/80">
          deployed_code_update
        </span>
        <h1 className="mt-4 text-lg font-semibold text-on-surface">A new version was deployed</h1>
        <p className="mt-1 text-[13px] text-on-surface-variant max-w-sm">
          This page belongs to a build that is no longer on the server. Reloading picks up the
          new one — nothing is wrong with your cluster.
        </p>
        <Button className="mt-5" onClick={() => window.location.reload()}>
          Reload
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center h-full min-h-[60vh] text-center px-6">
      <span aria-hidden="true" className="material-symbols-outlined text-[40px] text-error">
        error
      </span>
      <h1 className="mt-4 text-lg font-semibold text-on-surface">This page failed to load</h1>
      <p className="mt-1 text-[13px] text-on-surface-variant max-w-lg break-words">{message}</p>
      <div className="mt-5 flex items-center gap-2">
        <Button onClick={() => window.location.reload()}>Reload</Button>
        <Button variant="ghost" onClick={() => { window.location.href = '/'; }}>
          Back to Dashboard
        </Button>
      </div>
    </div>
  );
};

export default RouteError;
