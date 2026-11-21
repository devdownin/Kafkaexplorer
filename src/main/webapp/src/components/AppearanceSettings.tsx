// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useState } from 'react';
import {
  ACCENTS, DENSITIES, applyAccent, applyDensity, readAccent, readDensity,
  type Accent, type Density,
} from '../appearance';
import { Segmented } from './ui/Segmented';
import { cn } from './ui/cn';

/**
 * Accent colour and density. Both apply at once and live in this browser only — which the card
 * says, because everything else on this page is a deployment setting saved with the form below.
 */
export function AppearanceSettings() {
  const [accent, setAccent] = useState<Accent>(readAccent);
  const [density, setDensity] = useState<Density>(readDensity);

  return (
    <section aria-labelledby="appearance-title" className="kse-surface p-4 md:p-5 space-y-4">
      <div>
        <h2 id="appearance-title" className="text-sm font-semibold text-on-surface">Appearance</h2>
        <p className="text-[12px] text-on-surface-variant mt-0.5">
          Applied immediately and kept in this browser only — not part of the configuration saved below.
        </p>
      </div>
      <div className="space-y-1.5">
        <p id="accent-label" className="text-[12px] font-medium text-on-surface-variant">Accent</p>
        <div role="radiogroup" aria-labelledby="accent-label" className="flex flex-wrap gap-2">
          {ACCENTS.map(a => (
            <button
              key={a.id}
              type="button"
              role="radio"
              aria-checked={accent === a.id}
              aria-label={a.label}
              title={a.label}
              onClick={() => { setAccent(a.id); applyAccent(a.id); }}
              className={cn(
                'kse-control h-8 w-8 rounded-full border-2 flex items-center justify-center',
                accent === a.id ? 'border-on-surface' : 'border-transparent hover:border-outline-variant',
              )}
            >
              <span aria-hidden="true" className="block h-5 w-5 rounded-full" style={{ backgroundColor: a.swatch }} />
            </button>
          ))}
        </div>
      </div>
      <div className="space-y-1.5">
        <p className="text-[12px] font-medium text-on-surface-variant">Density</p>
        <Segmented ariaLabel="Density" value={density}
          onChange={d => { setDensity(d); applyDensity(d); }}
          options={DENSITIES.map(d => ({ value: d.value, label: d.label }))} />
        <p className="text-[12px] text-on-surface-variant">Compact tightens table rows, buttons and fields.</p>
      </div>
    </section>
  );
}

export default AppearanceSettings;
