// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useRef, useState } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { NAV_ITEMS, CONFIG_ITEM, HELP_ITEM } from '../navigation';
import { buildTraceLinkForKey } from '../pages/streamFlow';

interface CommandPaletteProps {
  onClose: () => void;
  topics: string[];
  tables: string[];
}

interface Command {
  id: string;
  label: string;
  hint?: string;
  icon: string;
  group: 'Actions' | 'Pages' | 'Topics' | 'Tables';
  keywords?: string;
  run: () => void;
}

/**
 * Palette de commandes globale (⌘K / Ctrl+K) : recherche unifiée sur les
 * actions rapides, les pages, les topics Kafka et les tables Flink, entièrement
 * pilotable au clavier (↑ ↓ Entrée Échap). Remplace la recherche inline du
 * header — une seule surface de recherche pour toute l'application.
 */
const CommandPalette: FC<CommandPaletteProps> = ({ onClose, topics, tables }) => {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [rawActive, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  /*
   * La palette n'est montée que lorsqu'elle est ouverte (voir `Layout`), donc elle repart
   * naturellement d'une saisie vide : c'est le remontage qui réinitialise, là où un effet devait
   * remettre à zéro deux états à chaque ouverture. Il ne reste qu'à donner le focus, une fois le
   * portail monté — ce qui ne pose aucun état.
   */
  useEffect(() => {
    const frame = requestAnimationFrame(() => inputRef.current?.focus());
    return () => cancelAnimationFrame(frame);
  }, []);

  const go = (path: string) => { navigate(path); onClose(); };

  const commands = useMemo<Command[]>(() => {
    const actions: Command[] = [
      { id: 'act-query',   label: 'New SQL query',   icon: 'add',            group: 'Actions', keywords: 'editor run sql', run: () => go('/query') },
      { id: 'act-metric',  label: 'Add metric',      icon: 'monitoring',     group: 'Actions', keywords: 'prometheus gauge counter', run: () => go('/metrics') },
      { id: 'act-audit',   label: 'Run cluster audit', icon: 'fact_check',   group: 'Actions', keywords: 'health scan', run: () => go('/audit') },
      { id: 'act-flow',    label: 'Trace stream flow', icon: 'waves',        group: 'Actions', keywords: 'message key lineage', run: () => go('/stream-flow') },
      { id: 'act-config',  label: 'Open settings',   icon: 'settings',       group: 'Actions', keywords: 'config kafka llm connection', run: () => go(CONFIG_ITEM.path) },
    ];
    const pages: Command[] = [...NAV_ITEMS, CONFIG_ITEM, HELP_ITEM].map(item => ({
      id: `page-${item.path}`,
      label: item.name,
      hint: 'Page',
      icon: item.icon,
      group: 'Pages',
      run: () => go(item.path),
    }));
    const typed = query.trim();
    // Ce que l'opérateur tape dans la palette est très souvent un identifiant — une commande, une
    // clé de corrélation — et pas un nom de page. Tant qu'il ne désigne pas un topic connu, on
    // propose de le tracer : la palette devient le point d'entrée d'une enquête, depuis n'importe
    // quel écran, au lieu d'un raccourci vers un formulaire vide à remplir.
    if (typed && !topics.includes(typed)) {
      actions.unshift({
        id: 'act-trace-key',
        label: `Trace "${typed}" across topics`,
        hint: 'Stream Flow',
        icon: 'route',
        group: 'Actions',
        keywords: `${typed} trace key flow`,
        run: () => go(buildTraceLinkForKey(typed)),
      });
    }

    const q = query.toLowerCase().trim();
    // Les topics/tables ne s'affichent qu'à la recherche (trop nombreux sinon).
    const topicCmds: Command[] = q
      ? topics.filter(t => t.toLowerCase().includes(q)).slice(0, 8).map(t => ({
          id: `topic-${t}`, label: t, hint: 'Kafka topic', icon: 'topic', group: 'Topics',
          run: () => go(`/topic/${t}`),
        }))
      : [];
    const tableCmds: Command[] = q
      ? tables.filter(t => t.toLowerCase().includes(q)).slice(0, 6).map(t => ({
          id: `table-${t}`, label: t, hint: 'Flink table', icon: 'grid_view', group: 'Tables',
          run: () => go(`/query?sql=${encodeURIComponent(`SELECT * FROM ${t} LIMIT 50`)}`),
        }))
      : [];
    return [...actions, ...pages, ...topicCmds, ...tableCmds];
    // eslint-disable-next-line react-hooks/exhaustive-deps -- go/navigate are stable
  }, [query, topics, tables]);

  const filtered = useMemo(() => {
    const q = query.toLowerCase().trim();
    if (!q) return commands.filter(c => c.group === 'Actions' || c.group === 'Pages');
    return commands.filter(c =>
      c.label.toLowerCase().includes(q) || c.keywords?.toLowerCase().includes(q) || c.group === 'Topics' || c.group === 'Tables',
    );
  }, [commands, query]);

  // Borné à la lecture : la liste rétrécit en tapant, et corriger l'index par un effet coûtait
  // un rendu de plus pour montrer, entre-temps, un surlignage hors de la liste.
  const active = Math.min(rawActive, Math.max(0, filtered.length - 1));

  // Fait défiler l'élément actif dans la vue.
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);


  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(a + 1, filtered.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); filtered[active]?.run(); }
    else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
  };

  // Sépare les groupes pour l'affichage tout en gardant l'index global continu.
  let runningIndex = -1;
  const groups: Command['group'][] = ['Actions', 'Pages', 'Topics', 'Tables'];

  return (
    <div
      className="fixed inset-0 z-[70] flex items-start justify-center pt-[12vh] px-4 glass-overlay animate-fade-in"
      role="dialog"
      aria-modal="true"
      aria-label="Command palette"
      onClick={onClose}
    >
      <div
        className="w-full max-w-xl bg-surface-container border border-outline-variant rounded-xl shadow-2xl overflow-hidden animate-slide-up"
        onClick={e => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        {/* Champ de recherche */}
        <div className="flex items-center gap-2.5 px-4 h-12 border-b border-outline-variant/60">
          <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[20px]">search</span>
          <input
            ref={inputRef}
            value={query}
            onChange={e => { setQuery(e.target.value); setActive(0); }}
            placeholder="Search pages, topics, tables or actions…"
            aria-label="Search"
            className="flex-1 bg-transparent border-none outline-none text-[14px] text-on-surface placeholder:text-outline"
          />
          <kbd className="text-[10px] text-outline border border-outline-variant rounded px-1.5 py-0.5">Esc</kbd>
        </div>

        {/* Résultats */}
        <div ref={listRef} className="max-h-[52vh] overflow-y-auto custom-scrollbar py-2">
          {filtered.length === 0 ? (
            <div className="px-4 py-10 text-center text-[13px] text-on-surface-variant">
              No results for “<span className="text-on-surface">{query}</span>”
            </div>
          ) : (
            groups.map(group => {
              const items = filtered.filter(c => c.group === group);
              if (items.length === 0) return null;
              return (
                <div key={group} className="px-2 pb-1">
                  <p className="px-2 py-1.5 text-[11px] font-medium uppercase tracking-[0.05em] text-outline select-none">{group}</p>
                  {items.map(cmd => {
                    runningIndex += 1;
                    const idx = runningIndex;
                    const isActive = idx === active;
                    return (
                      <button
                        key={cmd.id}
                        data-idx={idx}
                        onClick={() => cmd.run()}
                        onMouseMove={() => setActive(idx)}
                        aria-selected={isActive}
                        className={`w-full flex items-center gap-3 px-2.5 py-2 rounded-lg text-left transition-colors ${
                          isActive ? 'bg-primary/10 text-on-surface' : 'text-on-surface-variant hover:text-on-surface'
                        }`}
                      >
                        <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${isActive ? 'text-primary' : 'text-on-surface-variant'}`}>{cmd.icon}</span>
                        <span className={`flex-1 min-w-0 truncate text-[13px] ${cmd.group === 'Topics' || cmd.group === 'Tables' ? 'font-mono' : 'font-medium text-on-surface'}`}>{cmd.label}</span>
                        {cmd.hint && <span className="text-[11px] text-outline shrink-0">{cmd.hint}</span>}
                        {isActive && <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-outline shrink-0">keyboard_return</span>}
                      </button>
                    );
                  })}
                </div>
              );
            })
          )}
        </div>

        {/* Pied : rappels clavier */}
        <div className="flex items-center gap-4 px-4 h-9 border-t border-outline-variant/60 text-[11px] text-outline">
          <span className="flex items-center gap-1"><kbd className="border border-outline-variant rounded px-1">↑</kbd><kbd className="border border-outline-variant rounded px-1">↓</kbd> navigate</span>
          <span className="flex items-center gap-1"><kbd className="border border-outline-variant rounded px-1">↵</kbd> open</span>
        </div>
      </div>
    </div>
  );
};

export default CommandPalette;
