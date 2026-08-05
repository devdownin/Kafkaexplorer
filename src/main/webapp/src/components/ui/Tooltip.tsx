import { cloneElement, isValidElement, useId, useState } from 'react';
import type { FC, KeyboardEvent, ReactElement, ReactNode } from 'react';
import { cn } from './cn';

export interface TooltipProps {
  /** L'explication. Une phrase : au-delà, c'est de la documentation, pas une infobulle. */
  content: ReactNode;
  placement?: 'top' | 'bottom';
  className?: string;
  /** Le déclencheur — un élément unique, qui reçoit `aria-describedby`. */
  children: ReactElement;
}

/**
 * Infobulle du design system.
 *
 * `title=""` est ce que l'app utilisait partout, et il ne suffit pas : il n'apparaît qu'à la
 * souris, jamais au clavier ni au toucher, son délai n'est pas réglable, son rendu appartient au
 * navigateur, et les lecteurs d'écran ne l'annoncent pas de façon fiable. Une explication qui
 * n'atteint qu'une partie des utilisateurs n'explique rien.
 *
 * Celle-ci s'ouvre au survol **et au focus**, se ferme sur `Échap`, et reste dans le DOM en
 * permanence pour que `aria-describedby` pointe toujours sur quelque chose — une description
 * montée seulement à l'ouverture ne serait lue par personne.
 */
export const Tooltip: FC<TooltipProps> = ({ content, placement = 'top', className, children }) => {
  const id = useId();
  const [open, setOpen] = useState(false);

  const dismissOnEscape = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && open) {
      // Sans `stopPropagation` : fermer l'infobulle ne doit pas confisquer l'Échap de la page.
      setOpen(false);
    }
  };

  const trigger = isValidElement(children)
    ? cloneElement(children as ReactElement<{ 'aria-describedby'?: string }>, {
      'aria-describedby': id,
    })
    : children;

  return (
    <span
      className="relative inline-flex"
      onPointerEnter={() => setOpen(true)}
      onPointerLeave={() => setOpen(false)}
      onFocusCapture={() => setOpen(true)}
      onBlurCapture={() => setOpen(false)}
      onKeyDown={dismissOnEscape}
    >
      {trigger}
      <span
        id={id}
        role="tooltip"
        className={cn(
          'absolute left-1/2 -translate-x-1/2 z-50 w-max max-w-[18rem] px-2.5 py-1.5 rounded-lg',
          'bg-surface-container-highest text-on-surface text-[11px] leading-relaxed',
          'border border-outline-variant shadow-lg transition-opacity duration-100',
          placement === 'top' ? 'bottom-full mb-1.5' : 'top-full mt-1.5',
          open ? 'opacity-100' : 'opacity-0 pointer-events-none',
          className,
        )}
      >
        {content}
      </span>
    </span>
  );
};

export interface HelpTipProps {
  content: ReactNode;
  /** Ce que le bouton annonce : « More information about the scan budget ». */
  label: string;
  placement?: 'top' | 'bottom';
}

/**
 * Le petit « ⓘ » à poser à côté d'un contrôle qui n'est pas lui-même un bon déclencheur — un
 * `<select>` ou une case à cocher n'invitent pas à être survolés pour être compris.
 */
export const HelpTip: FC<HelpTipProps> = ({ content, label, placement = 'top' }) => (
  <Tooltip content={content} placement={placement}>
    <button
      type="button"
      aria-label={label}
      className="inline-flex items-center justify-center text-outline hover:text-on-surface transition-colors rounded-full"
    >
      <span aria-hidden="true" className="material-symbols-outlined text-[15px]">info</span>
    </button>
  </Tooltip>
);

export default Tooltip;
