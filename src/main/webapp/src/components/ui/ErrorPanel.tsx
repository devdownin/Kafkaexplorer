import { useState } from 'react';
import type { FC, ReactNode } from 'react';
import { cn } from './cn';
import type { QueryErrorInfo } from '../../pages/queryError';

export interface ErrorPanelProps {
  /** Ce que `describeApiError` a tiré de l'échec : titre lisible, conseil, message brut. */
  error: QueryErrorInfo;
  /** Action de réessai, quand l'appel qui a échoué peut être relancé tel quel. */
  onRetry?: () => void;
  /** Bouton supplémentaire (« Ouvrir les réglages », « Voir le job »…). */
  action?: ReactNode;
  onDismiss?: () => void;
  className?: string;
}

/**
 * Panneau d'erreur du design system.
 *
 * Un `toast` disparaît au bout de quelques secondes : il convient à une confirmation, pas à un
 * échec sur lequel on doit agir. Le message brut du serveur — la vraie raison du refus, celle qui
 * dit *quelle* colonne SQL est inconnue — était par ailleurs jeté par des `catch { toast('Failed
 * to save') }`. Ce panneau garde les trois niveaux que l'éditeur SQL avait déjà pour lui seul :
 * un titre lisible, un conseil, et le texte d'origine repliable.
 */
export const ErrorPanel: FC<ErrorPanelProps> = ({ error, onRetry, action, onDismiss, className }) => {
  const [rawOpen, setRawOpen] = useState(false);
  const showRaw = Boolean(error.raw) && error.raw !== error.title;

  return (
    <div
      role="alert"
      className={cn(
        'rounded-lg border border-error/25 bg-error/10 px-3 py-2.5 text-[12px] text-error',
        className,
      )}
    >
      <div className="flex items-start gap-2">
        <span aria-hidden="true" className="material-symbols-outlined text-[16px] mt-0.5 shrink-0">error</span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold leading-snug">{error.title}</p>
          {error.hint && <p className="mt-0.5 text-error/80 leading-relaxed">{error.hint}</p>}
          {error.location && (
            <p className="mt-0.5 text-error/70">
              Line {error.location.line}, column {error.location.column}
            </p>
          )}

          {(onRetry || action || showRaw) && (
            <div className="mt-2 flex flex-wrap items-center gap-3">
              {onRetry && (
                <button
                  type="button"
                  onClick={onRetry}
                  className="inline-flex items-center gap-1 font-medium hover:underline"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[14px]">refresh</span>
                  Retry
                </button>
              )}
              {action}
              {showRaw && (
                <button
                  type="button"
                  onClick={() => setRawOpen(open => !open)}
                  aria-expanded={rawOpen}
                  className="text-error/80 hover:text-error hover:underline"
                >
                  {rawOpen ? 'Hide raw error' : 'Show raw error'}
                </button>
              )}
            </div>
          )}

          {rawOpen && showRaw && (
            <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded bg-surface-container/60 p-2 font-mono text-[11px] text-on-surface-variant">
              {error.raw}
            </pre>
          )}
        </div>
        {onDismiss && (
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Dismiss error"
            className="shrink-0 text-error/70 hover:text-error"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[16px]">close</span>
          </button>
        )}
      </div>
    </div>
  );
};

export default ErrorPanel;
