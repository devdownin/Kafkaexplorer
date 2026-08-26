// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { FC, KeyboardEvent } from 'react';
import { cn } from './cn';
import { Input } from './Field';

export interface ComboboxProps {
  value: string;
  onChange: (value: string) => void;
  /** Valeurs proposées. Une valeur hors liste reste acceptée : c'est une aide, pas une contrainte. */
  options: string[];
  placeholder?: string;
  id?: string;
  invalid?: boolean;
  disabled?: boolean;
  className?: string;
  'aria-label'?: string;
  'aria-describedby'?: string;
  /** Nombre maximum de suggestions affichées (défaut 8). */
  maxVisible?: number;
  /** Appelé sur Entrée quand aucune suggestion n'est surlignée — soumission du formulaire. */
  onEnter?: () => void;
}

/**
 * Champ texte avec suggestions filtrées au clavier (pattern ARIA combobox).
 *
 * Volontairement **non contraignant** : la saisie libre reste valide et la liste ne fait
 * qu'accélérer la frappe. Les noms de topics se tapaient jusqu'ici à la main dans plusieurs
 * formulaires, et une faute de frappe donnait un résultat vide sans le moindre indice.
 *
 * Un `<datalist>` natif aurait coûté moins de code, mais son menu n'est pas stylable et rend
 * mal en thème sombre — l'app a par ailleurs déjà ce motif dans `CommandPalette`.
 */
export const Combobox: FC<ComboboxProps> = ({
  value, onChange, options, placeholder, id, invalid, disabled, className,
  maxVisible = 8, onEnter, ...aria
}) => {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const listId = `${inputId}-listbox`;
  const [open, setOpen] = useState(false);
  /**
   * Ouverture *délibérée* (chevron, flèche bas) : la liste entière, sans filtre.
   *
   * Sans ça, une valeur déjà choisie se filtre elle-même : le pool tombe à une proposition
   * strictement égale à la saisie, qui est supprimée juste après — la liste devenait donc vide
   * dès qu'un topic était sélectionné, et le chevron un bouton mort. Dans l'éditeur de métrique,
   * où le topic est pré-rempli à l'ouverture, plus rien ne permettait d'en changer par la liste.
   */
  const [browseAll, setBrowseAll] = useState(false);
  /**
   * Index surligné. Il est *borné à la lecture* plutôt que corrigé par un effet : la liste
   * rétrécit en tapant, et remettre l'index dans ses bornes après coup coûtait un rendu de plus
   * pour afficher, le temps de celui-ci, un surlignage sur du vide.
   */
  const [rawActive, setActive] = useState(-1);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  const matches = useMemo(() => {
    // Parcourir la liste, c'est la voir en entier : elle défile, et taper la refiltre aussitôt.
    if (browseAll) return options;
    const needle = value.trim().toLowerCase();
    const pool = needle
      ? options.filter(option => option.toLowerCase().includes(needle))
      : options;
    // Une seule proposition strictement égale à la saisie n'apprend rien : on n'ouvre pas pour ça.
    if (pool.length === 1 && pool[0].toLowerCase() === needle) return [];
    return pool.slice(0, maxVisible);
  }, [options, value, maxVisible, browseAll]);

  const active = Math.min(rawActive, matches.length - 1);

  /** Ouvre la liste complète, en surlignant la valeur courante si elle en fait partie. */
  const openAll = () => {
    const needle = value.trim().toLowerCase();
    setBrowseAll(true);
    setOpen(true);
    setActive(options.findIndex(option => option.toLowerCase() === needle));
  };

  // La liste complète peut être longue : le surlignage doit rester visible au clavier.
  useEffect(() => {
    if (!open || active < 0) return;
    (listRef.current?.children[active] as HTMLElement | undefined)?.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  // Clic à l'extérieur : referme sans toucher à la valeur saisie.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!wrapperRef.current?.contains(event.target as Node)) { setOpen(false); setBrowseAll(false); }
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  const commit = (option: string) => {
    onChange(option);
    setOpen(false);
    setBrowseAll(false);
    setActive(-1);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' && !open) {
      if (options.length === 0) return;
      event.preventDefault();
      // Liste vide sous le filtre (valeur déjà choisie) : on ouvre tout de même, en entier.
      if (matches.length === 0) openAll();
      else { setOpen(true); setActive(0); }
      return;
    }
    if (!open || matches.length === 0) {
      if (event.key === 'Enter' && onEnter) { event.preventDefault(); onEnter(); }
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive(a => (a + 1) % matches.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive(a => (a <= 0 ? matches.length - 1 : a - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (active >= 0) commit(matches[active]);
      else onEnter?.();
    } else if (event.key === 'Escape') {
      // Ne pas laisser Échap remonter : il fermerait aussi le dialogue parent, le cas échéant.
      event.stopPropagation();
      setOpen(false);
      setBrowseAll(false);
      setActive(-1);
    } else if (event.key === 'Tab') {
      setOpen(false);
      setBrowseAll(false);
    }
  };

  const expanded = open && matches.length > 0;

  return (
    <div ref={wrapperRef} className={cn('relative', className)}>
      <Input
        id={inputId}
        role="combobox"
        aria-expanded={expanded}
        aria-controls={expanded ? listId : undefined}
        aria-autocomplete="list"
        aria-activedescendant={expanded && active >= 0 ? `${inputId}-option-${active}` : undefined}
        autoComplete="off"
        spellCheck={false}
        invalid={invalid}
        disabled={disabled}
        placeholder={placeholder}
        value={value}
        // Taper, c'est chercher : on repasse au filtre, quelle que soit la façon dont c'était ouvert.
        onChange={e => { onChange(e.target.value); setBrowseAll(false); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        className="font-mono pr-8"
        {...aria}
      />
      {options.length > 0 && !disabled && (
        <button
          type="button"
          // Pas de tabulation en plus (la flèche bas ouvre depuis le champ), mais plus
          // aria-hidden non plus : c'est le seul chemin à la souris vers la liste complète.
          tabIndex={-1}
          aria-label={expanded ? 'Hide suggestions' : 'Show all suggestions'}
          title={expanded ? 'Hide suggestions' : 'Show all suggestions'}
          onClick={() => { if (expanded) { setOpen(false); setBrowseAll(false); } else openAll(); }}
          className="absolute right-2 top-1/2 -translate-y-1/2 min-w-6 min-h-6 inline-flex items-center justify-center rounded text-outline hover:text-on-surface"
        >
          <span className="material-symbols-outlined text-[18px]">
            {expanded ? 'expand_less' : 'expand_more'}
          </span>
        </button>
      )}
      {expanded && (
        <ul
          ref={listRef}
          id={listId}
          role="listbox"
          className="absolute z-20 mt-1 w-full max-h-64 overflow-auto custom-scrollbar rounded-lg border border-outline-variant bg-surface-container-high shadow-lg py-1"
        >
          {matches.map((option, index) => (
            <li
              key={option}
              id={`${inputId}-option-${index}`}
              role="option"
              aria-selected={index === active}
              // pointerdown, pas click : le blur de l'input refermerait la liste avant le clic.
              onPointerDown={e => { e.preventDefault(); commit(option); }}
              onMouseEnter={() => setActive(index)}
              // La liste est aussi large que le champ et tronque : sans ceci, un nom de topic
              // un peu long n'est lisible nulle part — l'éditeur de métrique la rend dans un
              // volet de 288 px, et c'est là qu'on choisit le topic de la métrique. C'est la
              // convention des autres listes de topics (`DataModel`), et le seul usage de
              // `title` que ce dépôt garde : révéler une valeur tronquée, jamais expliquer.
              // Élargir la liste ne servirait à rien ici, le volet défile verticalement, donc
              // il rogne aussi ce qui dépasse à droite.
              title={option}
              className={cn(
                'px-3 py-1.5 text-[12px] font-mono cursor-pointer truncate',
                index === active ? 'bg-primary/15 text-on-surface' : 'text-on-surface-variant',
              )}
            >
              {option}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default Combobox;
