// Composant de la page SQL Editor, sorti de `QueryWorkbench.tsx` — voir `ResultsGrid.tsx`.
import React, { useEffect } from 'react';
import { Button, ErrorPanel } from '../ui';
import type { QueryErrorInfo } from '../../pages/queryError';

export interface DdlPreviewModalProps {
  topic: string;
  ddl: string | null;
  error: QueryErrorInfo | null;
  loading: boolean;
  onClose: () => void;
  onRetry: () => void;
  onCopy: () => void;
  onInsert: () => void;
}

/**
 * Aperçu du DDL généré pour un topic.
 *
 * C'était une boîte maison sans échappement ni rendu du focus : ouverte au clavier, elle laissait
 * le focus derrière elle, et `Escape` — le geste réflexe — ne faisait rien. Le reste de
 * l'application ferme sur `Escape` (`ConfirmDialog`, le modal de Metrics, la palette de commandes).
 * L'échec d'inférence s'affiche **dans** la boîte, là où le SQL peut être corrigé, et non dans un
 * toast qui s'efface derrière elle en emportant la seule chose utile : la raison.
 */
export const DdlPreviewModal: React.FC<DdlPreviewModalProps> = ({
  topic, ddl, error, loading, onClose, onRetry, onCopy, onInsert,
}) => {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
  <div className="fixed inset-0 glass-overlay z-50 flex items-center justify-center p-8"
    role="dialog" aria-modal="true" aria-label="DDL preview"
    onClick={onClose}>
    <div onClick={e => e.stopPropagation()}
      className="bg-surface-container border border-outline-variant rounded-xl w-full max-w-2xl max-h-[80vh] flex flex-col shadow-2xl">
      <div className="flex items-center justify-between p-4 border-b border-outline-variant">
        <div>
          <h2 className="text-[14px] font-semibold text-on-surface">DDL Preview</h2>
          <p className="text-[11px] text-on-surface-variant font-mono mt-0.5">{topic}</p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close"
          className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors">
          <span className="material-symbols-outlined text-[20px]">close</span>
        </button>
      </div>
      <div className="flex-1 overflow-auto p-4">
        {loading
          ? <div className="flex items-center gap-2 text-on-surface-variant py-4"><span className="material-symbols-outlined animate-spin text-[16px]">progress_activity</span><span className="text-[13px]">Inferring schema…</span></div>
          : ddl
            ? <pre className="text-[12px] font-mono text-on-surface whitespace-pre-wrap leading-relaxed">{ddl}</pre>
            : error
              ? <ErrorPanel error={error}
                  onRetry={onRetry} />
              : <p className="text-[13px] text-on-surface-variant">Failed to generate DDL</p>
        }
      </div>
      <div className="p-4 border-t border-outline-variant flex items-center justify-end gap-2">
        <Button variant="outline" size="sm" icon="content_copy" disabled={!ddl}
          onClick={onCopy}>
          Copy
        </Button>
        <Button variant="primary" size="sm" icon="edit_note" disabled={!ddl}
          onClick={onInsert}>
          Insert in editor
        </Button>
      </div>
    </div>
  </div>
  );
};
