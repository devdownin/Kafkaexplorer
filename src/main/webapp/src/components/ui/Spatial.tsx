// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { forwardRef, type HTMLAttributes } from 'react';
import { cn } from './cn';

/**
 * Glass is for layer 3 only — transient planes that float over the content: dialogs,
 * the command palette, popovers. Cards, tables and editors are opaque reading planes
 * (`Card`, `Table`), and the sidebar and header carry their own material (`.kse-sidebar`,
 * `.kse-header`). Buttons and fields are `Button` and `Field`: a second control set beside
 * them drifts from the tokens and skips their accessibility wiring, which is why the
 * `GlassCard`/`SpatialButton`/`SpatialInput` family that no page used was removed.
 */
export type GlassMaterial = 'thin' | 'regular' | 'thick';
export type SpatialElevation = 'flat' | 'raised' | 'floating';
export interface GlassSurfaceProps extends HTMLAttributes<HTMLDivElement> {
  material?: GlassMaterial;
  elevation?: SpatialElevation;
}
const materials: Record<GlassMaterial, string> = {
  thin: 'kse-glass-thin',
  regular: 'kse-glass-regular',
  thick: 'kse-glass-thick',
};
const elevations: Record<SpatialElevation, string> = {
  flat: 'kse-elevation-flat',
  raised: 'kse-elevation-raised',
  floating: 'kse-elevation-floating',
};
export const GlassSurface = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassSurface(
  { material = 'regular', elevation = 'raised', className, ...props }, ref,
) {
  return <div ref={ref} className={cn('rounded-[var(--radius-panel)]', materials[material], elevations[elevation], className)} {...props} />;
});
/** Tooltips and small floating panels: thick glass, raised. */
export const GlassPopover = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassPopover(
  { material = 'thick', className, ...props }, ref,
) {
  return <GlassSurface ref={ref} material={material} className={cn('rounded-2xl', className)} {...props} />;
});
/** Dialog cards (confirmations, command palette): thick glass, always floating. */
export const GlassModal = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassModal(
  { material = 'thick', className, ...props }, ref,
) {
  return <GlassSurface ref={ref} {...props} material={material} elevation="floating" className={cn('kse-modal', className)} />;
});
