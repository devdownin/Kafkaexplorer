import { useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';

/**
 * Brouillons de formulaire, conservés d'une page à l'autre.
 *
 * Les pages sont chargées par `React.lazy` et montées par la route : changer d'écran les démonte,
 * et tout `useState` local meurt avec. Ce qui n'a pas été explicitement conservé est perdu — un
 * pipeline Process Mining à quatre étapes, une métrique à moitié écrite, des réglages de connexion
 * saisis mais pas encore enregistrés.
 *
 * Le mécanisme est le même que celui des onglets SQL (`kse:tabs`) : `localStorage`, écrit au fil de
 * la frappe, relu au montage. Il a l'avantage de survivre aussi à un rechargement et à un onglet
 * fermé par accident, là où garder les pages montées ne survivrait ni à l'un ni à l'autre.
 */
const PREFIX = 'kse:draft:';

/**
 * Un brouillon reste un confort : au-delà de cette taille il n'est pas écrit du tout, plutôt que
 * de faire échouer l'écriture sur le quota et de risquer d'emporter les autres clés avec elle.
 */
export const MAX_DRAFT_BYTES = 512_000;

export const draftKey = (name: string): string => `${PREFIX}${name}`;

export function readDraft<T>(name: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(draftKey(name));
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    // Quota, mode privé, JSON corrompu : on repart du formulaire vide plutôt que de planter.
    return fallback;
  }
}

export function writeDraft(name: string, value: unknown): void {
  try {
    const raw = JSON.stringify(value);
    if (raw === undefined) return;
    if (raw.length > MAX_DRAFT_BYTES) {
      // Trop gros pour être gardé : effacer ce qui traîne, sinon le brouillon relu serait une
      // version périmée de ce que l'écran affiche — pire que pas de brouillon du tout.
      clearDraft(name);
      return;
    }
    localStorage.setItem(draftKey(name), raw);
  } catch {
    // idem : l'échec d'écriture ne doit jamais interrompre la saisie.
  }
}

export function clearDraft(name: string): void {
  try {
    localStorage.removeItem(draftKey(name));
  } catch {
    // rien à faire de plus.
  }
}

/**
 * `useState`, mais le brouillon survit au démontage de la page.
 *
 * @param name clé du brouillon, sans le préfixe.
 * @param initial valeur de départ quand aucun brouillon n'existe.
 */
export function usePersistentState<T>(
  name: string,
  initial: T,
): [T, Dispatch<SetStateAction<T>>] {
  const [value, setValue] = useState<T>(() => readDraft(name, initial));

  useEffect(() => {
    writeDraft(name, value);
  }, [name, value]);

  return [value, setValue];
}
