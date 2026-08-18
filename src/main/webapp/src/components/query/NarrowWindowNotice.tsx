// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * « Cet écran demande une fenêtre plus large » — et surtout : voici ceux qui n'en demandent pas.
 *
 * Sous `lg`, l'éditeur SQL rend cinq pixels de Monaco (mesure de `MOBILE-LAYOUT-SCOPE.md`, reprise
 * dans `WIDE_WINDOW_MIN_PX`). La page n'était pas cassée au sens habituel — rien ne déborde, rien
 * ne se chevauche — elle était simplement inutilisable sans le dire, ce qui est pire : l'opérateur
 * conclut que l'application est en panne. Le bandeau ne bloque rien, il nomme.
 *
 * Il se ferme, et la fermeture tient (localStorage, ce navigateur) : quelqu'un qui sait déjà n'a
 * pas à relire l'avertissement à chaque ouverture.
 */

import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  NARROW_ALTERNATIVES, WIDE_WINDOW_MIN_PX, dismissNarrowNotice, readNarrowNoticeDismissed,
} from '../../pages/queryWorkbench';

export const NarrowWindowNotice: React.FC = () => {
  const [dismissed, setDismissed] = useState(() => readNarrowNoticeDismissed());
  if (dismissed) return null;

  return (
    // `lg:hidden` plutôt qu'un matchMedia : c'est une décision de mise en page, et le seuil est
    // celui du shell — un second listener qui pourrait diverger du CSS n'apporterait rien.
    <aside
      aria-label="This screen needs a wider window"
      className="lg:hidden shrink-0 border-b border-outline-variant/60 bg-surface-container-low px-4 py-3"
    >
      <div className="flex items-start gap-2">
        <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-warning mt-0.5">
          width_wide
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-semibold text-on-surface">This screen needs a wider window</p>
          <p className="text-[12px] text-on-surface-variant mt-0.5 leading-relaxed">
            Writing SQL needs about {WIDE_WINDOW_MIN_PX} px of window; below that the editor is a few pixels wide.
            You can still run and read a query here — these screens work at this width:
          </p>
          <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
            {NARROW_ALTERNATIVES.map(alternative => (
              <li key={alternative.to} className="text-[12px]">
                <Link to={alternative.to} className="text-primary hover:underline">{alternative.label}</Link>
                <span className="text-on-surface-variant"> — {alternative.answers}</span>
              </li>
            ))}
          </ul>
        </div>
        <button
          type="button"
          onClick={() => { dismissNarrowNotice(); setDismissed(true); }}
          aria-label="Dismiss this notice"
          title="Dismiss on this browser"
          className="shrink-0 p-1.5 rounded-md text-outline hover:text-on-surface hover:bg-surface-container-high transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">close</span>
        </button>
      </div>
    </aside>
  );
};

export default NarrowWindowNotice;
