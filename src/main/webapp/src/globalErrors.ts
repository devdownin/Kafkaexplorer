// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Making an uncaught error visible.
 *
 * Two production bugs were reported this way: "the Stop button is inactive" and "the Copy
 * button does nothing". Neither description was accurate — in both cases a `TypeError` was
 * thrown inside a click handler and the application said nothing at all, so the only
 * symptom left was a control that appeared not to be wired up. The diagnosis went the wrong
 * way because the screen was silent, not because the error was subtle.
 *
 * Nothing listened for `error` or `unhandledrejection`. This does, so any future failure of
 * that shape reports itself instead of being inferred from a button that seems dead.
 *
 * A toast is a deliberate compromise. The project's own rule is that a failure needing
 * action deserves a panel rather than a toast — but an uncaught error has no screen that
 * owns it, and there is nowhere to put a panel. So it is surfaced as a toast *and* always
 * written to the console: visible enough that nobody reports "the button is inactive"
 * again, with the detail kept where it can be read afterwards.
 */

import { isStaleChunkError } from './staleChunk';

/** How long the same message stays suppressed. An error inside a render or a resize
 *  observer can fire dozens of times a second; one toast is a report, thirty is a denial
 *  of service against the reader. */
export const DEDUPE_MS = 5_000;

export interface DedupeState {
  message: string | null;
  at: number;
}

/*
 * Noise that must not reach the user:
 *   - ResizeObserver loop warnings, which browsers emit routinely around charts and
 *     virtualised tables and which are benign by specification;
 *   - "Script error.", the opaque placeholder a cross-origin script yields, carrying no
 *     information anyone can act on. There are no third-party scripts here — Monaco is
 *     self-hosted — so this should never fire; it is listed because if it ever does, it
 *     tells the reader nothing.
 */
const IGNORED = [
  /resizeobserver loop/i,
  /^script error\.?$/i,
];

function messageOf(reason: unknown): string {
  if (typeof reason === 'string') return reason;
  if (reason instanceof Error) return reason.message || reason.name;
  if (reason && typeof reason === 'object') {
    const candidate = (reason as { message?: unknown }).message;
    if (typeof candidate === 'string') return candidate;
  }
  return '';
}

/**
 * The message to show, or `null` when the failure should not be surfaced.
 *
 * A stale-chunk error returns `null` on purpose: `staleChunk.ts` reloads the page for it,
 * and a toast about a module that failed to load would describe a problem already being
 * fixed, in words the reader cannot act on.
 */
export function describeGlobalError(reason: unknown): string | null {
  const message = messageOf(reason).trim();
  if (!message) return null;
  if (isStaleChunkError(reason)) return null;
  if (IGNORED.some((pattern) => pattern.test(message))) return null;
  return message;
}

/** Whether to report now, given what was last reported. Pure, so the suppression window
 *  is testable without waiting for it. */
export function shouldReport(message: string, now: number, state: DedupeState): boolean {
  if (state.message === message && now - state.at < DEDUPE_MS) return false;
  return true;
}

export interface ErrorReporterOptions {
  target?: Pick<Window, 'addEventListener' | 'removeEventListener'>;
  now?: () => number;
  log?: (message: string, reason: unknown) => void;
}

/**
 * Wires the two listeners. Returns a cleanup function, so a React effect can detach them
 * and a second mount cannot double-report.
 */
export function installGlobalErrorReporting(
  report: (message: string) => void,
  options: ErrorReporterOptions = {},
): () => void {
  const target = options.target ?? globalThis.window;
  const now = options.now ?? Date.now;
  const log = options.log ?? ((message, reason) => console.error('[uncaught]', message, reason));
  if (!target?.addEventListener) return () => {};

  const state: DedupeState = { message: null, at: 0 };

  const handle = (reason: unknown) => {
    const message = describeGlobalError(reason);
    if (!message) return;
    // Logged whatever the suppression decides: the console is the record, the toast is
    // only the notification.
    log(message, reason);
    const at = now();
    if (!shouldReport(message, at, state)) return;
    state.message = message;
    state.at = at;
    report(message);
  };

  const onError = (event: Event) => handle((event as ErrorEvent).error ?? (event as ErrorEvent).message);
  const onRejection = (event: Event) => handle((event as PromiseRejectionEvent).reason);

  target.addEventListener('error', onError);
  target.addEventListener('unhandledrejection', onRejection);

  return () => {
    target.removeEventListener('error', onError);
    target.removeEventListener('unhandledrejection', onRejection);
  };
}
