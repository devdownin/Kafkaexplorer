// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Runs `read` every `periodMs` **while the tab is visible**, and once when it becomes visible again.
 *
 * A bare `setInterval` keeps asking the backend behind a tab nobody is looking at — for the metrics
 * list that is a request every 15 s for the life of a forgotten tab, and for the forecast panel
 * three, one of which re-prepares a series from PostgreSQL. Coming back reads at once, so a tab that
 * slept does not show the figures of an hour ago for the length of a period. Returns the cleanup.
 */
export function startVisiblePolling(read: () => void, periodMs: number): () => void {
  const readIfVisible = () => {
    if (document.visibilityState === 'visible') read();
  };
  const timer = window.setInterval(readIfVisible, periodMs);
  document.addEventListener('visibilitychange', readIfVisible);
  return () => {
    window.clearInterval(timer);
    document.removeEventListener('visibilitychange', readIfVisible);
  };
}
