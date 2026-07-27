import { useEffect, useState } from 'react';
import type { FC, InputHTMLAttributes } from 'react';
import { Input } from './Field';

export interface NumberInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'> {
  value: number;
  onChange: (value: number) => void;
  /** Valeur retenue quand le champ est laissé vide ou illisible au blur. */
  fallback: number;
  min?: number;
  max?: number;
  invalid?: boolean;
}

/**
 * Champ numérique qui laisse taper.
 *
 * Le motif précédent — `parseInt(e.target.value, 10) || 4096` appliqué à chaque frappe — rendait
 * le champ inutilisable dès qu'on le vidait pour ressaisir : la valeur sautait au défaut en cours
 * de frappe, et un `0` en première position aussi (0 est falsy). On garde donc la chaîne brute
 * pendant la saisie et on ne convertit qu'au blur.
 */
export const NumberInput: FC<NumberInputProps> = ({
  value, onChange, fallback, min, max, invalid, ...props
}) => {
  const [draft, setDraft] = useState(String(value));

  // Suit les changements venus d'ailleurs (chargement de la config, reset), sans écraser la
  // saisie en cours lorsque le brouillon désigne déjà cette valeur.
  useEffect(() => {
    setDraft(current => (Number(current) === value ? current : String(value)));
  }, [value]);

  const commit = () => {
    const parsed = Number(draft);
    if (draft.trim() === '' || !Number.isFinite(parsed)) {
      setDraft(String(fallback));
      onChange(fallback);
      return;
    }
    let next = parsed;
    if (min != null) next = Math.max(min, next);
    if (max != null) next = Math.min(max, next);
    setDraft(String(next));
    onChange(next);
  };

  return (
    <Input
      type="number"
      inputMode="numeric"
      min={min}
      max={max}
      invalid={invalid}
      value={draft}
      onChange={e => {
        setDraft(e.target.value);
        // Remonte immédiatement une valeur exploitable, sans jamais réécrire le champ.
        const parsed = Number(e.target.value);
        if (e.target.value.trim() !== '' && Number.isFinite(parsed)) onChange(parsed);
      }}
      onBlur={commit}
      {...props}
    />
  );
};

export default NumberInput;
