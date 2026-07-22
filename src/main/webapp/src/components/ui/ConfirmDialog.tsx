import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { FC, ReactNode } from 'react';
import { Button } from './Button';

export interface ConfirmOptions {
  title: string;
  description?: ReactNode;
  /** Libellé du bouton de confirmation (défaut « Confirm »). */
  confirmLabel?: string;
  cancelLabel?: string;
  /** `danger` pour les actions destructives, `primary` sinon. */
  tone?: 'primary' | 'danger';
  /** Icône Material Symbols affichée dans l'en-tête. */
  icon?: string;
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn>(async () => false);

/** Ouvre une boîte de confirmation et attend la décision de l'utilisateur. */
export const useConfirm = (): ConfirmFn => useContext(ConfirmContext);

/**
 * Fournit `useConfirm()` : une API impérative renvoyant une promesse booléenne.
 *
 *   const confirm = useConfirm();
 *   if (await confirm({ title: 'Kill job?', tone: 'danger' })) { … }
 *
 * Un seul dialogue est rendu à la fois ; Échap et le fond annulent, le bouton
 * de confirmation reçoit le focus à l'ouverture, et le focus est restauré à la
 * fermeture (accessibilité clavier).
 */
export const ConfirmProvider: FC<{ children: ReactNode }> = ({ children }) => {
  const [options, setOptions] = useState<ConfirmOptions | null>(null);
  const resolverRef = useRef<((value: boolean) => void) | null>(null);
  const confirmBtnRef = useRef<HTMLButtonElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  const confirm = useCallback<ConfirmFn>((opts) => {
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    setOptions(opts);
    return new Promise<boolean>((resolve) => { resolverRef.current = resolve; });
  }, []);

  const settle = useCallback((value: boolean) => {
    resolverRef.current?.(value);
    resolverRef.current = null;
    setOptions(null);
    // Restaure le focus sur l'élément déclencheur.
    previouslyFocused.current?.focus?.();
  }, []);

  useEffect(() => {
    if (!options) return;
    confirmBtnRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); settle(false); }
      if (e.key === 'Enter') { e.preventDefault(); settle(true); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [options, settle]);

  const danger = options?.tone === 'danger';

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {options && (
        <div
          className="fixed inset-0 z-[60] flex items-center justify-center p-4 glass-overlay animate-fade-in"
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-title"
          onClick={() => settle(false)}
        >
          <div
            className="w-full max-w-sm bg-surface-container border border-outline-variant rounded-xl shadow-2xl p-5 animate-slide-up"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start gap-3">
              <div className={`w-9 h-9 rounded-lg flex items-center justify-center shrink-0 ${danger ? 'bg-error/10 text-error' : 'bg-primary/10 text-primary'}`}>
                <span aria-hidden="true" className="material-symbols-outlined text-[20px]">{options.icon ?? (danger ? 'warning' : 'help')}</span>
              </div>
              <div className="min-w-0">
                <h2 id="confirm-title" className="text-[15px] font-semibold text-on-surface">{options.title}</h2>
                {options.description && (
                  <p className="text-[13px] text-on-surface-variant mt-1 leading-relaxed">{options.description}</p>
                )}
              </div>
            </div>
            <div className="flex items-center justify-end gap-2 mt-5">
              <Button variant="ghost" onClick={() => settle(false)}>{options.cancelLabel ?? 'Cancel'}</Button>
              <Button ref={confirmBtnRef} variant={danger ? 'danger' : 'primary'} onClick={() => settle(true)}>
                {options.confirmLabel ?? 'Confirm'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
};

export default ConfirmProvider;
