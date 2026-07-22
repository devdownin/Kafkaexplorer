import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** Concatène et déduplique les classes Tailwind (clsx + tailwind-merge). */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
