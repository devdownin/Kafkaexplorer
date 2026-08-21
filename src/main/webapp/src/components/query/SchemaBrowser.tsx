// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

// Composant de la page SQL Editor, sorti de `QueryWorkbench.tsx` — voir `ResultsGrid.tsx`.
import React from 'react';
import { Input, ScrollList, cn } from '../ui';
import { toTableName } from '../../pages/sqlScope';
import type { QueryInitResponse } from '../../api/types';

export interface SavedQuery { id: string; name: string; sql: string; savedAt: number }

/**
 * La réponse de `GET /api/query/init`. Alias de `QueryInitResponse`, dont la forme vit dans
 * api/types.ts où check-api-types.py la résout contre le record Java : cette page en portait une
 * copie littérale sous un autre nom, ce qui est précisément la duplication qui se périme sans
 * bruit. Le nom local est conservé, il est déjà importé ailleurs.
 */
export type SchemaInfo = QueryInitResponse;

/**
 * Un bouton-icône de la colonne, réellement 24 x 24.
 *
 * `layout-probe.mjs` compte les cibles sous les 24 x 24 CSS px de la WCAG 2.5.8, et ce fichier en
 * ajoutait une par table Flink : le gabarit d'une icône `text-sm` fait environ 18 px. Élargir la
 * seule zone de clic en gardant l'icône serait la version tentante, et c'est la remarque que porte
 * déjà `Checkbox` — ici ce sont de vrais `<button>`, donc `min-w`/`min-h` plus un centrage flex
 * agrandissent la boîte pour de bon, sans grossir le dessin.
 *
 * Les deux boutons de la ligne le portent, pas seulement le nouveau : ils forment une paire, et
 * n'en corriger qu'un laisserait deux cibles voisines de tailles différentes.
 */
const ICON_HIT = 'focus-visible:opacity-100 text-outline transition-all shrink-0 ml-1 '
  + 'min-w-6 min-h-6 inline-flex items-center justify-center rounded';
/** Les actions d'une ligne de table, révélées au survol de `group/tbl`. */
const ICON_BUTTON = `opacity-0 group-hover/tbl:opacity-100 ${ICON_HIT}`;
/*
 * La même cible pour une ligne de topic. Le nom du groupe Tailwind fait partie de la classe, donc
 * une seule constante ne pouvait pas servir les deux : le bouton « Preview DDL » avait sa propre
 * chaîne écrite à la main, sans `min-w-6 min-h-6`, et se rendait à **14 x 24** — vingt-huit fois
 * sur cet écran. C'est précisément ce que le commentaire ci-dessus annonçait comme à éviter, deux
 * cibles voisines de tailles différentes, et il le disait au-dessus de la constante que le voisin
 * n'utilisait pas.
 */
const TOPIC_ICON_BUTTON = `opacity-0 group-hover/topic:opacity-100 ${ICON_HIT}`;

export interface SchemaBrowserProps {
  schema: SchemaInfo | null;
  schemaLoading: boolean;
  onRefresh: () => void;
  width: number;
  onResizeStart: (e: React.PointerEvent) => void;
  onResizeKey: (e: React.KeyboardEvent) => void;
  widthMin: number;
  widthMax: number;
  expandedTables: Record<string, boolean>;
  tableSchemas: Record<string, Record<string, string>>;
  onToggleTable: (table: string) => void;
  onSelectFrom: (table: string) => void;
  /**
   * Intitulé de l'action pour une cible donnée. Le clic pose un `SELECT` ou un `INSERT INTO`
   * selon le mode d'exécution, et un bouton annonçant « SELECT » dans les deux cas décrirait la
   * moitié du temps autre chose que ce qu'il fait — c'est le seul nom que reçoit un lecteur
   * d'écran. La page décide, ce composant rend.
   */
  actionLabelFor: (target: string) => string;
  /**
   * Supprime une table du catalogue Flink et cesse de la conserver.
   *
   * Redémarrer était le seul moyen de vider le catalogue en mémoire ; comme les `CREATE TABLE`
   * écrits ici sont maintenant rejoués au démarrage, cette issue disparaîtrait sans ce geste — et
   * un magasin qui ne saurait que grossir serait un défaut pire que celui qu'il corrige.
   */
  onDropTable: (table: string) => void;
  onPreviewDdl: (topic: string) => void;
  savedQueries: SavedQuery[];
  onLoadSaved: (q: SavedQuery) => void;
  onDeleteSaved: (id: string) => void;
  saveInputVisible: boolean;
  saveInputName: string;
  onSaveInputChange: (v: string) => void;
  onSaveOpen: () => void;
  onSaveCancel: () => void;
  onSaveConfirm: () => void;
  activeTabName: string;
}

/**
 * Navigateur de schéma : tables Flink, topics Kafka, requêtes sauvegardées, et l'état du moteur.
 *
 * Il rend ce que la page lui donne et ne décide rien : c'est la page qui charge le catalogue, ouvre
 * le SQL et gère la disposition. Les entrées sont de vrais `<button>` — elles étaient des
 * `<div onClick>`, donc toute cette colonne était inatteignable au clavier.
 */
export const SchemaBrowser = React.forwardRef<HTMLElement, SchemaBrowserProps>(function SchemaBrowser({
  schema, schemaLoading, onRefresh, width, onResizeStart, onResizeKey, widthMin, widthMax,
  expandedTables, tableSchemas, onToggleTable, onSelectFrom, onDropTable, onPreviewDdl, actionLabelFor,
  savedQueries, onLoadSaved, onDeleteSaved,
  saveInputVisible, saveInputName, onSaveInputChange, onSaveOpen, onSaveCancel, onSaveConfirm,
  activeTabName,
}, ref) {
  return (
  <aside ref={ref} className="relative flex-shrink-0 flex flex-col border-r border-outline-variant/60 bg-surface-container-low" style={{ width }}>
    {/* Sidebar drag handle */}
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize the schema browser"
      aria-valuenow={Math.round(width)}
      aria-valuemin={widthMin}
      aria-valuemax={widthMax}
      tabIndex={0}
      onPointerDown={onResizeStart}
      onKeyDown={onResizeKey}
      style={{ touchAction: 'none' }}
      className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 focus-visible:bg-primary/60 transition-colors z-10"
    />

    <div className="p-4 flex items-center gap-3 border-b border-outline-variant/60">
      <div className="size-8 bg-primary/15 text-primary rounded-lg flex items-center justify-center shrink-0">
        <span className="material-symbols-outlined text-[18px]">database</span>
      </div>
      <div className="min-w-0">
        <h1 className="text-[14px] font-semibold tracking-tight text-on-surface">SQL Workbench</h1>
        <p className="text-[11px] text-on-surface-variant">Flink SQL Engine</p>
      </div>
    </div>

    <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">Schema Browser</h2>
        <button onClick={onRefresh} disabled={schemaLoading} aria-label="Refresh schema" className="p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors disabled:opacity-50" title="Refresh">
          <span className={`material-symbols-outlined text-[18px] ${schemaLoading ? 'animate-spin' : ''}`}>{schemaLoading ? 'progress_activity' : 'refresh'}</span>
        </button>
      </div>

      {/* Ce qui n'a pas répondu, et pourquoi. Une liste vide sans motif se lit comme « ce
          cluster n'a rien », qui est une tout autre information. */}
      {(schema?.kafkaError || schema?.flinkError) && (
        <div className="mb-3 rounded-lg border border-warning/30 bg-warning/10 px-2.5 py-2" role="status">
          {schema.kafkaError && (
            <div className="flex items-start gap-1.5">
              <span aria-hidden="true" className="material-symbols-outlined text-warning text-[14px] mt-px shrink-0">warning</span>
              <p className="text-[11px] text-on-surface-variant leading-snug min-w-0">
                <span className="font-semibold text-warning">Kafka: </span>
                <span className="break-words">{schema.kafkaError}</span>
              </p>
            </div>
          )}
          {schema.flinkError && (
            <div className={cn('flex items-start gap-1.5', schema.kafkaError && 'mt-1.5')}>
              <span aria-hidden="true" className="material-symbols-outlined text-warning text-[14px] mt-px shrink-0">warning</span>
              <p className="text-[11px] text-on-surface-variant leading-snug min-w-0">
                <span className="font-semibold text-warning">Flink: </span>
                <span className="break-words">{schema.flinkError}</span>
              </p>
            </div>
          )}
        </div>
      )}

      <div className="space-y-1">
        {/* Flink Tables */}
        <details className="group" open>
          <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
            <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
            <span className="material-symbols-outlined text-base text-on-surface-variant">grid_view</span>
            <span className="text-sm font-medium">Flink Tables</span>
            <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.tables.length ?? 0}</span>
          </summary>
          {schema?.tables.length === 0 && (
            <p className="text-[10px] text-outline px-2 py-2 pl-6">
              {schema.flinkError ? 'Tables could not be listed — see above' : 'No Flink tables registered yet'}
            </p>
          )}
          <ScrollList count={schema?.tables.length ?? 0} className="pl-4 pt-1 space-y-0.5">
            {schema?.tables.map(table => (
              <div key={table}>
                <div className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/tbl">
                  {/* Un `<div onClick>` n'est ni tabulable ni actionnable au clavier : toute la
                      barre latérale était inatteignable sans souris. */}
                  <button type="button" onClick={() => onToggleTable(table)}
                    aria-expanded={!!expandedTables[table]}
                    className="flex-1 flex items-center gap-1 min-w-0 text-left rounded">
                    <span className={`material-symbols-outlined text-xs text-on-surface-variant transition-transform duration-200 shrink-0 ${expandedTables[table] ? 'rotate-90' : ''}`}>chevron_right</span>
                    {/* `truncate` sans `title`, c'est du texte que rien ne permet de lire — le
                        défaut que W7 a trouvé sur les cartes de métriques. Les deux boutons
                        d'action à droite rendent la troncature plus fréquente, donc la ligne
                        porte le nom complet. */}
                    <span className="text-xs text-on-surface truncate font-mono" title={table}>{table}</span>
                  </button>
                  <button type="button" onClick={() => onSelectFrom(table)}
                    className={cn(ICON_BUTTON, 'hover:text-primary')} title={actionLabelFor('this table')} aria-label={actionLabelFor(table)}>
                    <span className="material-symbols-outlined text-sm">play_arrow</span>
                  </button>
                  <button type="button" onClick={() => onDropTable(table)}
                    className={cn(ICON_BUTTON, 'hover:text-error')}
                    title="Drop this table" aria-label={`Drop table ${table}`}>
                    <span className="material-symbols-outlined text-sm">delete</span>
                  </button>
                </div>
                {expandedTables[table] && tableSchemas[table] && (
                  <div className="ml-6 pl-3 border-l border-primary/20 py-1 space-y-1">
                    {Object.entries(tableSchemas[table]).map(([col, type]) => (
                      <div key={col} className="flex justify-between items-center text-[10px] py-0.5">
                        <span className="text-on-surface-variant truncate pr-2 font-mono" title={col}>{col}</span>
                        <span className="text-primary/60 font-mono uppercase shrink-0">{type}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </ScrollList>
        </details>

        {/* Kafka Topics */}
        <details className="group" open>
          <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
            <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
            <span className="material-symbols-outlined text-base text-on-surface-variant">topic</span>
            <span className="text-sm font-medium">Kafka Topics</span>
            <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{schema?.topics.length ?? 0}</span>
          </summary>
          <div className="pl-4 pt-1 space-y-0.5">
            {schemaLoading ? (
              <div className="flex items-center gap-2 py-3 px-2 text-on-surface-variant">
                <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                <span className="text-xs">Loading…</span>
              </div>
            ) : schema?.topics.length === 0 ? (
              // « Aucun topic avec des messages » est une affirmation sur le cluster ; elle
              // n'a de sens que si on a pu le lire.
              <p className="text-[10px] text-outline px-2 py-2">
                {schema.kafkaError ? 'Topics could not be listed — see above' : 'No topics with messages'}
              </p>
            ) : (
              <ScrollList count={schema?.topics.length ?? 0} className="space-y-0.5">
                {schema?.topics.map(topic => (
                  <div key={topic} className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/topic">
                    <button type="button" onClick={() => onSelectFrom(toTableName(topic))}
                      className="flex-1 min-w-0 text-left rounded" title={topic} aria-label={actionLabelFor(topic)}>
                      {/* Même règle que la liste des tables juste au-dessus, qui l'appliquait
                          déjà : un nom `truncate` sans `title` est du texte que rien ne permet
                          de lire. Un nom de topic est plus long qu'un nom de table (il garde ses
                          points là où `toTableName` les remplace) et la barre latérale est
                          étroite, donc c'est ici que la troncature mord le plus. Le `title` va
                          sur le bouton, pas sur le `span` : c'est lui qui occupe la ligne, donc
                          la zone que l'on survole. Le nom accessible porte déjà le topic entier
                          (`SELECT from <topic>`), donc ce qui manquait n'était que la souris. */}
                      <span className="text-xs text-on-surface-variant hover:text-primary font-mono truncate block">{topic}</span>
                    </button>
                    <button type="button" onClick={e => { e.stopPropagation(); onPreviewDdl(topic); }}
                      className={cn(TOPIC_ICON_BUTTON, 'hover:text-primary')}
                      title="Preview DDL" aria-label={`Preview the generated DDL for ${topic}`}>
                      <span className="material-symbols-outlined text-sm">code</span>
                    </button>
                  </div>
                ))}
              </ScrollList>
            )}
            <p className="text-[10px] text-outline px-2 pt-1">Only topics with messages shown</p>
          </div>
        </details>

        {/* ── Saved Queries ── */}
        <details className="group">
          <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
            <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
            <span className="material-symbols-outlined text-base text-on-surface-variant">bookmark</span>
            <span className="text-sm font-medium">Saved Queries</span>
            <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{savedQueries.length}</span>
          </summary>
          <div className="pl-4 pt-2 space-y-1">
            {/* Save current query */}
            {saveInputVisible ? (
              <div className="flex items-center gap-1 px-2 pb-2">
                <Input
                  autoFocus
                  aria-label="Saved query name"
                  value={saveInputName}
                  onChange={e => onSaveInputChange(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') onSaveConfirm(); if (e.key === 'Escape') onSaveCancel(); }}
                  placeholder={activeTabName}
                  className="flex-1 h-8 text-[12px]"
                />
                <button type="button" onClick={onSaveConfirm} aria-label="Confirm save" className="p-1 rounded-md text-primary hover:bg-surface-container-high">
                  <span className="material-symbols-outlined text-[18px]">check</span>
                </button>
                <button type="button" onClick={onSaveCancel} aria-label="Cancel" className="p-1 rounded-md text-outline hover:text-on-surface hover:bg-surface-container-high">
                  <span className="material-symbols-outlined text-[18px]">close</span>
                </button>
              </div>
            ) : (
              <button type="button" onClick={onSaveOpen}
                className="flex items-center gap-1.5 w-full px-2 py-1.5 text-[12px] font-medium text-on-surface-variant hover:text-on-surface border border-dashed border-outline-variant hover:border-outline rounded-md transition-colors">
                <span className="material-symbols-outlined text-[16px]">add</span>Save current query
              </button>
            )}
            {savedQueries.length === 0 && !saveInputVisible && (
              <p className="text-[11px] text-outline px-2 py-1">No saved queries yet</p>
            )}
            <ScrollList count={savedQueries.length} className="space-y-1">
              {savedQueries.map(q => (
                <div key={q.id} className="flex items-center gap-1 py-1 px-2 rounded hover:bg-primary/5 transition-colors group/saved">
                  <div onClick={() => onLoadSaved(q)} className="flex-1 min-w-0 cursor-pointer">
                    <p className="text-xs text-on-surface truncate font-medium">{q.name}</p>
                    <p className="text-[10px] text-outline">{new Date(q.savedAt).toLocaleDateString()}</p>
                  </div>
                  <button type="button" onClick={() => onDeleteSaved(q.id)}
                    className="opacity-0 group-hover/saved:opacity-100 text-outline hover:text-error transition-all shrink-0" title="Delete" aria-label="Delete this saved query">
                    <span className="material-symbols-outlined text-sm">delete</span>
                  </button>
                </div>
              ))}
            </ScrollList>
          </div>
        </details>
      </div>
    </div>

    <div className="p-3 border-t border-outline-variant/60 flex items-center gap-2 text-[11px] text-on-surface-variant">
      {/* Trois états, pas deux : un broker qui répond à la sonde mais refuse l'appel de
          métadonnées n'est pas « hors ligne », et envoyer quelqu'un vérifier une connexion qui
          marche est la pire des pistes. */}
      <span className={cn('w-1.5 h-1.5 rounded-full shrink-0',
        !schema?.health ? 'bg-outline' : (schema.kafkaError || schema.flinkError) ? 'bg-warning' : 'bg-success')} />
      <span>{!schema?.health ? 'Engine offline'
        : (schema.kafkaError || schema.flinkError) ? 'Engine degraded' : 'Engine connected'}</span>
      <span className="ml-auto tabular-nums shrink-0">{schema?.tables.length ?? 0} tables · {schema?.topics.length ?? 0} topics</span>
    </div>
    </aside>
  );
});
