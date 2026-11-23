// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/** Local shortcuts deliberately do not intercept the application's global Ctrl/⌘+K palette. */
export type StrataShortcut = 'help' | 'search' | 'overview' | 'focus' | 'compare' | 'replay' | 'escape';

export function strataShortcut(
  event: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey' | 'repeat'>,
  insideControl: boolean,
): StrataShortcut | null {
  if (event.ctrlKey || event.metaKey || event.altKey || event.repeat) return null;
  if (insideControl) return event.key === 'Escape' ? 'escape' : null;
  switch (event.key.toLowerCase()) {
    case '?': return 'help';
    case '/': return 'search';
    case 'o': return 'overview';
    case 'f': return 'focus';
    case 'c': return 'compare';
    case ' ': return 'replay';
    case 'escape': return 'escape';
    default: return null;
  }
}
