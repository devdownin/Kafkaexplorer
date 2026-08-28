// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useState } from 'react';
import type { FC, InputHTMLAttributes } from 'react';
import { Input } from './Field';
import { cn } from './cn';

export interface PasswordInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  invalid?: boolean;
  wrapperClassName?: string;
}

/**
 * Champ secret avec bouton « afficher ».
 *
 * Les mots de passe de keystore et les secrets d'API se saisissaient à l'aveugle : une faute de
 * frappe ne se manifestait qu'en échec de connexion, sans rien pour la diagnostiquer.
 * `autoComplete="new-password"` empêche le navigateur de préremplir ces champs avec des
 * identifiants sans rapport.
 */
export const PasswordInput: FC<PasswordInputProps> = ({
  invalid, className, wrapperClassName, ...props
}) => {
  const [visible, setVisible] = useState(false);
  return (
    <div className={cn('relative', wrapperClassName)}>
      <Input
        type={visible ? 'text' : 'password'}
        autoComplete="new-password"
        spellCheck={false}
        invalid={invalid}
        className={cn('pr-9 font-mono', className)}
        {...props}
      />
      <button
        type="button"
        onClick={() => setVisible(v => !v)}
        aria-label={visible ? 'Hide value' : 'Show value'}
        aria-pressed={visible}
        /*
         * 24 x 24, mesuré : `layout-probe.mjs` le comptait à **18 x 24** — quatre pixels de trop
         * peu sur la largeur, la même famille que `Checkbox` (13 x 13) et `Switch` (36 x 20), et
         * sur un contrôle partagé par tous les champs secrets de l'application. La cible est
         * élargie autour du glyphe, qui garde ses 18 px : ici le `flex` centre le dessin dans une
         * boîte plus grande, ce qui est exactement ce qu'un `padding` ne fait pas de façon fiable
         * sur un contrôle remplacé.
         */
        className="absolute right-1.5 top-1/2 -translate-y-1/2 flex h-6 w-6 items-center
          justify-center text-outline hover:text-on-surface"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">
          {visible ? 'visibility_off' : 'visibility'}
        </span>
      </button>
    </div>
  );
};

export default PasswordInput;
