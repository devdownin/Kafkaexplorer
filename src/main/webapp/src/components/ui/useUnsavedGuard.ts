import { useEffect, useRef } from 'react';
import { useBlocker } from 'react-router-dom';
import { useConfirm } from './ConfirmDialog';

export interface UnsavedGuardOptions {
  title?: string;
  description?: string;
  confirmLabel?: string;
  cancelLabel?: string;
}

const DEFAULTS: Required<UnsavedGuardOptions> = {
  title: 'Leave without saving?',
  description: 'The changes on this page have not been saved. Leaving now discards them.',
  confirmLabel: 'Leave',
  cancelLabel: 'Stay',
};

/**
 * Demande confirmation avant de quitter un écran dont la saisie n'est pas enregistrée.
 *
 * `beforeunload` ne couvre que le rechargement et la fermeture d'onglet : une navigation interne
 * (un clic dans la barre latérale, la palette de commandes, le bouton Précédent) démonte la page
 * sans que le navigateur soit jamais consulté. `useBlocker` est le seul point où react-router
 * laisse intercepter cette navigation *avant* qu'elle n'ait lieu — d'où le routeur « data » dans
 * `App.tsx`. Les deux sont posés ensemble : ils couvrent deux sorties différentes.
 *
 * La question passe par `useConfirm`, donc par le dialogue du design system : accessible au
 * clavier, focus géré, même rendu que les autres confirmations de l'application.
 *
 * @param when `true` tant qu'il y a quelque chose à perdre.
 */
export function useUnsavedGuard(when: boolean, options: UnsavedGuardOptions = {}): void {
  const confirm = useConfirm();
  const blocker = useBlocker(when);
  /*
   * Une seule question à la fois : `blocker.state` reste `blocked` pendant que la promesse est en
   * vol, et le rendu qui suit l'ouverture du dialogue relancerait l'effet sans ce garde-fou.
   */
  const asking = useRef(false);

  useEffect(() => {
    if (blocker.state !== 'blocked' || asking.current) return;
    asking.current = true;
    const opts = { ...DEFAULTS, ...options };
    let cancelled = false;
    confirm({
      title: opts.title,
      description: opts.description,
      confirmLabel: opts.confirmLabel,
      cancelLabel: opts.cancelLabel,
      tone: 'danger',
      icon: 'warning',
    }).then((leave) => {
      asking.current = false;
      // La page peut avoir été démontée entre-temps (fermeture d'onglet, navigation forcée) :
      // toucher au blocker alors ne ferait que lever une erreur dans une promesse orpheline.
      if (cancelled) return;
      if (leave) blocker.proceed?.();
      else blocker.reset?.();
    });
    return () => { cancelled = true; };
    // `options` est un littéral reconstruit à chaque rendu : le suivre relancerait l'effet en
    // boucle. Seul l'état du blocker décide de poser la question.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocker, confirm]);

  useEffect(() => {
    if (!when) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [when]);
}
