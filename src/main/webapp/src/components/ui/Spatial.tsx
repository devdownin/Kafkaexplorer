// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { forwardRef, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes } from 'react';
import { cn } from './cn';

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
  flat: 'shadow-none',
  raised: '',
  floating: 'shadow-2xl',
};
export const GlassSurface = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassSurface(
  { material = 'regular', elevation = 'raised', className, ...props }, ref,
) {
  return <div ref={ref} className={cn('rounded-[var(--radius-panel)]', materials[material], elevations[elevation], className)} {...props} />;
});
export const GlassCard = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassCard(props, ref) {
  return <GlassSurface ref={ref} {...props} className={cn('rounded-[var(--radius-card)] p-5', props.className)} />;
});
export const GlassPanel = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassPanel(props, ref) {
  return <GlassSurface ref={ref} {...props} className={cn('p-5', props.className)} />;
});
export const GlassSidebar = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassSidebar(props, ref) {
  return <GlassSurface ref={ref} {...props} material={props.material ?? 'regular'} />;
});
export const GlassHeader = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassHeader(props, ref) {
  return <GlassSurface ref={ref} {...props} material={props.material ?? 'thin'} />;
});
export const GlassPopover = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassPopover(props, ref) {
  return <GlassSurface ref={ref} {...props} material={props.material ?? 'thick'} className={cn('rounded-2xl', props.className)} />;
});
export const GlassModal = forwardRef<HTMLDivElement, GlassSurfaceProps>(function GlassModal(props, ref) {
  return <GlassSurface ref={ref} {...props} material={props.material ?? 'thick'} elevation="floating" className={cn('kse-modal', props.className)} />;
});
export const FloatingToolbar = forwardRef<HTMLDivElement, GlassSurfaceProps>(function FloatingToolbar(props, ref) {
  return <GlassSurface ref={ref} {...props} material={props.material ?? 'thin'} className={cn('flex items-center gap-2 rounded-2xl p-2', props.className)} />;
});
export const SpatialButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement>>(function SpatialButton(
  { className, type = 'button', ...props }, ref,
) {
  return <button ref={ref} type={type} className={cn('kse-interactive rounded-xl px-3 py-2 bg-surface-container-high text-on-surface focus-visible:outline-2', className)} {...props} />;
});
export const SpatialInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function SpatialInput(
  { className, ...props }, ref,
) {
  return <input ref={ref} className={cn('kse-input w-full bg-surface-container-low border border-outline-variant px-3 py-2 text-on-surface placeholder:text-outline', className)} {...props} />;
});
