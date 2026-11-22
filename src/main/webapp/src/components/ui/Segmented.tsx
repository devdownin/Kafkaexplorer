// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useLayoutEffect, useRef, useState } from 'react';
import { cn } from './cn';

export interface SegmentedProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  options: ReadonlyArray<{ value: T; label: string }>;
  ariaLabel: string;
}

/**
 * Compact segmented control. The selected pill is one element that *slides* to the chosen option
 * (position and width are measured), with the same overshoot easing as `Switch`; reduced motion
 * removes the transition globally. The first paint places it without animating, so a control
 * mounted on a stored value does not glide in from the left.
 *
 * Each option stays a real `<button aria-pressed>`, so the pill is decoration (`aria-hidden`) and
 * the state a reader needs is carried by the buttons, not by the moving part.
 */
export function Segmented<T extends string>({ value, onChange, options, ariaLabel }: SegmentedProps<T>) {
  const group = useRef<HTMLDivElement>(null);
  const buttons = useRef(new Map<T, HTMLButtonElement>());
  const [pill, setPill] = useState<{ left: number; width: number } | null>(null);
  const [animate, setAnimate] = useState(false);

  useLayoutEffect(() => {
    const place = () => {
      const el = buttons.current.get(value);
      // Measured, not computed: labels differ in width. jsdom has no layout (zeros), which draws nothing.
      if (el && el.offsetWidth > 0) setPill({ left: el.offsetLeft, width: el.offsetWidth });
    };
    place();
    const observer = typeof ResizeObserver === 'undefined' || !group.current ? null : new ResizeObserver(place);
    if (observer && group.current) observer.observe(group.current);
    return () => observer?.disconnect();
  }, [value, options]);

  // Animate only once a position exists to move away from.
  useLayoutEffect(() => { if (pill && !animate) requestAnimationFrame(() => setAnimate(true)); }, [pill, animate]);

  return (
    <div
      ref={group}
      role="group"
      aria-label={ariaLabel}
      className="relative inline-flex bg-surface-container-low border border-outline-variant rounded-lg p-0.5"
    >
      {pill && (
        <span
          aria-hidden="true"
          className={cn(
            'absolute top-0.5 bottom-0.5 rounded-md bg-surface-container-highest shadow-sm',
            animate && 'transition-[transform,width] duration-250 ease-[cubic-bezier(.34,1.3,.64,1)]',
          )}
          style={{ left: 0, width: pill.width, transform: `translateX(${pill.left}px)` }}
        />
      )}
      {options.map(o => (
        <button
          key={o.value}
          ref={el => { if (el) buttons.current.set(o.value, el); else buttons.current.delete(o.value); }}
          type="button"
          onClick={() => onChange(o.value)}
          aria-pressed={value === o.value}
          className={cn(
            'relative px-3 h-7 text-[12px] font-medium rounded-md transition-colors',
            // Without the pill (no layout yet) the selected option still reads as selected.
            value === o.value ? cn('text-on-surface', !pill && 'bg-surface-container-highest shadow-sm') : 'text-on-surface-variant hover:text-on-surface',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export default Segmented;
