// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useRef } from 'react';

const shortcuts = [
  { key: '/', action: 'Focus topic search' },
  { key: '?', action: 'Open keyboard help' },
  { key: 'F', action: 'Focus the selected topic' },
  { key: 'O', action: 'Return to cluster overview' },
  { key: 'C', action: 'Compare two topics' },
  { key: 'Space', action: 'Play or pause the temporal replay' },
  { key: 'Escape', action: 'Close help, leave the focused topic, then leave comparison' },
  { key: '← / →', action: 'Navigate the time slider; resize handles when focused' },
  { key: 'Shift + Enter', action: 'Focus a topic from the mini-map or list' },
  { key: 'Ctrl/⌘ + K', action: 'Open the application-wide command palette' },
];

export default function StrataShortcutDialog({ onClose }: { onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopPropagation(); onClose();
      }
      if (event.key === 'Tab') {
        // The close button is the only interactive control inside this dialog.
        event.preventDefault();
        closeRef.current?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previous?.focus();
    };
  }, [onClose]);

  return <div className="fixed inset-0 z-[80] flex items-center justify-center bg-black/45 p-3 sm:p-6"
    onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <section role="dialog" aria-modal="true" aria-labelledby="strata-shortcuts-title"
      aria-describedby="strata-shortcuts-subtitle"
      className="w-full max-w-lg max-h-[85vh] overflow-auto rounded-2xl border border-outline-variant bg-surface p-5 shadow-2xl text-on-surface">
      <header className="flex items-start justify-between gap-3">
        <div>
          <h2 id="strata-shortcuts-title" className="text-lg font-semibold">Keyboard navigation</h2>
          <p id="strata-shortcuts-subtitle" className="mt-1 text-sm text-on-surface-variant">
            Shortcuts work outside editable fields, buttons and other active controls.
          </p>
        </div>
        <button type="button" ref={closeRef} onClick={onClose}
          className="rounded-lg border border-outline-variant px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-primary">
          Close
        </button>
      </header>
      <dl className="mt-5 grid grid-cols-[minmax(96px,auto)_minmax(0,1fr)] gap-x-4 gap-y-3 text-sm">
        {shortcuts.map(item => <div key={item.key} className="col-span-2 grid grid-cols-subgrid items-center gap-4">
          <dt><kbd className="inline-flex min-h-7 items-center rounded-md border border-outline-variant bg-surface-container-low px-2 py-1 font-mono text-xs">{item.key}</kbd></dt>
          <dd className="text-on-surface-variant">{item.action}</dd>
        </div>)}
      </dl>
      <p className="mt-5 border-t border-outline-variant pt-3 text-xs text-on-surface-variant">
        The topic navigator, diagnostic inspector and timeline remain usable without WebGL.
      </p>
    </section>
  </div>;
}
