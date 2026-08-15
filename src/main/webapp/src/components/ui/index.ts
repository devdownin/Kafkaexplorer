// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Kafka Explorer UI — kit de composants du design system (aligné sur Spectra).
 * Basé sur les tokens de `tailwind.config.js` + `index.css` ; à privilégier
 * pour toute nouvelle surface afin d'uniformiser boutons, cartes, badges,
 * tables, formulaires et états vides.
 */
export { Button } from './Button';
export type { ButtonProps, ButtonVariant, ButtonSize } from './Button';
export { Card, CardHeader } from './Card';
export type { CardProps, CardHeaderProps } from './Card';
export { Badge } from './Badge';
export type { BadgeProps, BadgeTone } from './Badge';
export { EmptyState } from './EmptyState';
export type { EmptyStateProps } from './EmptyState';
export { ErrorPanel } from './ErrorPanel';
export { Tooltip, HelpTip } from './Tooltip';
export type { TooltipProps, HelpTipProps } from './Tooltip';
export type { ErrorPanelProps } from './ErrorPanel';
export { PageHeader } from './PageHeader';
export type { PageHeaderProps } from './PageHeader';
export { Stat } from './Stat';
export type { StatProps } from './Stat';
export { Field, Input, Select, Textarea } from './Field';
export type { FieldProps, InputProps, SelectProps, TextareaProps } from './Field';
export { Combobox } from './Combobox';
export type { ComboboxProps } from './Combobox';
export { TopicInput } from './TopicInput';
export type { TopicInputProps } from './TopicInput';
export { NumberInput } from './NumberInput';
export type { NumberInputProps } from './NumberInput';
export { PasswordInput } from './PasswordInput';
export type { PasswordInputProps } from './PasswordInput';
export { Table, TableHead, TableBody, TableRow, Th, Td } from './Table';
export type { TableProps } from './Table';
export { Skeleton, SkeletonText, StatSkeleton, StatGridSkeleton, TableSkeleton, CardSkeleton } from './Skeleton';
export type { SkeletonProps, SkeletonTextProps, SkeletonGridProps, TableSkeletonProps } from './Skeleton';
export { Spinner, ProgressBar } from './Spinner';
export type { SpinnerProps } from './Spinner';
export { ConfirmProvider, useConfirm } from './ConfirmDialog';
export type { ConfirmOptions } from './ConfirmDialog';
export { useUnsavedGuard } from './useUnsavedGuard';
export type { UnsavedGuardOptions } from './useUnsavedGuard';
export { useVirtualRows } from './useVirtualRows';
export type { VirtualWindow } from './useVirtualRows';
export { ScrollList } from './ScrollList';
export type { ScrollListProps } from './ScrollList';
export { cn } from './cn';
