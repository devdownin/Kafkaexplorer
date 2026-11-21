// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC, HTMLAttributes, ReactNode, TdHTMLAttributes, ThHTMLAttributes } from 'react';
import { cn } from './cn';

/**
 * Primitives de table du design system : carte arrondie + en-tête discret +
 * lignes séparées par un filet 1 px avec hover. Composition :
 *
 *   <Table>
 *     <TableHead><tr><Th>…</Th></tr></TableHead>
 *     <TableBody><TableRow><Td>…</Td></TableRow></TableBody>
 *   </Table>
 */

export interface TableProps extends HTMLAttributes<HTMLTableElement> {
  /** Conteneur scrollable horizontal (activé par défaut). */
  scrollContainerClassName?: string;
  /**
   * Nombre de lignes du corps. Au-delà de `scrollThreshold`, le conteneur
   * plafonne sa hauteur (`maxBodyHeight`) et défile verticalement avec un
   * en-tête collant. Laisser indéfini pour une table non bornée.
   */
  rowCount?: number;
  /** Seuil de lignes déclenchant le défilement vertical (défaut 25). */
  scrollThreshold?: number;
  /** Hauteur max du corps défilant (défaut '30rem'). */
  maxBodyHeight?: number | string;
  children: ReactNode;
}

export const Table: FC<TableProps> = ({
  className, scrollContainerClassName, rowCount, scrollThreshold = 25, maxBodyHeight = '30rem', children, ...props
}) => {
  const scroll = rowCount != null && rowCount > scrollThreshold;
  const maxHeight = typeof maxBodyHeight === 'number' ? `${maxBodyHeight}px` : maxBodyHeight;
  return (
    <div
      className={cn(
        'kse-data-table bg-surface-container rounded-xl border border-outline-variant/60',
        // `kse-scroll-table` rend l'en-tête collant (voir index.css) quand on borne la hauteur.
        scroll ? 'overflow-auto custom-scrollbar kse-scroll-table' : 'overflow-x-auto custom-scrollbar',
        scrollContainerClassName,
      )}
      style={scroll ? { maxHeight } : undefined}
    >
      <table className={cn('w-full text-left border-collapse text-on-surface', className)} {...props}>
        {children}
      </table>
    </div>
  );
};

export const TableHead: FC<HTMLAttributes<HTMLTableSectionElement>> = ({ className, children, ...props }) => (
  <thead className={cn('bg-surface-container-high/70', className)} {...props}>
    {children}
  </thead>
);

export const Th: FC<ThHTMLAttributes<HTMLTableCellElement>> = ({ className, children, ...props }) => (
  <th
    scope="col"
    className={cn('px-4 py-2.5 text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant whitespace-nowrap', className)}
    {...props}
  >
    {children}
  </th>
);

export const TableBody: FC<HTMLAttributes<HTMLTableSectionElement>> = ({ className, children, ...props }) => (
  <tbody className={cn('divide-y divide-outline-variant/40', className)} {...props}>
    {children}
  </tbody>
);

export const TableRow: FC<HTMLAttributes<HTMLTableRowElement>> = ({ className, children, ...props }) => (
  <tr className={cn('hover:bg-surface-container-high/45 transition-colors duration-150', className)} {...props}>
    {children}
  </tr>
);

export const Td: FC<TdHTMLAttributes<HTMLTableCellElement>> = ({ className, children, ...props }) => (
  <td className={cn('px-4 py-3 text-[12px] text-on-surface align-middle break-words', className)} {...props}>
    {children}
  </td>
);

export default Table;
