// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

// Composant de la page SQL Editor, sorti de `QueryWorkbench.tsx` — voir `ResultsGrid.tsx`.
import React, { useMemo, useState } from 'react';
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
/*
 * L'action d'une ligne de topic, révélée au survol. Le bouton « Preview DDL » avait sa propre
 * chaîne écrite à la main, sans `min-w-6 min-h-6`, et se rendait à **14 x 24** — vingt-huit fois
 * sur cet écran : la cible minimale est dans `ICON_HIT`, pour qu'aucune ligne ne la réécrive.
 */
const TOPIC_ICON_BUTTON = `opacity-0 group-hover/topic:opacity-100 ${ICON_HIT}`;

/**
 * Ce qui rend lisible un nom que la colonne tronque.
 *
 * `truncate` coupe à la largeur de la barre latérale (288 px par défaut, 200 au minimum) et le
 * `title` posé à côté ne rend le reste qu'à la souris, après le délai du navigateur, dans un rendu
 * qui ne nous appartient pas. Au survol — et au focus clavier, que le `title` n'atteint jamais —
 * la ligne cesse de tronquer et le nom passe à la ligne : il est lu en entier, sur place.
 *
 * `break-all` parce qu'un nom de topic n'offre pas d'espace où revenir à la ligne, et le passage
 * en `whitespace-normal` suffit à désamorcer l'ellipse, qui ne s'applique qu'à une ligne unique.
 *
 * Une infobulle du design system a été essayée d'abord et écartée : sa bulle est positionnée en
 * absolu dans un conteneur qui défile (`overflow-y: auto` coupe aussi l'axe horizontal), donc elle
 * se fait couper là où c'est le plus étroit — et son contenu redirait le nom que `aria-label`
 * énonce déjà, ce qui le ferait annoncer deux fois au clavier. Ici, rien n'est dupliqué : c'est le
 * même texte qui cesse d'être coupé.
 */
const REVEAL_ON_HOVER = 'group-hover/name:whitespace-normal group-hover/name:break-all '
  + 'group-focus-within/name:whitespace-normal group-focus-within/name:break-all';

/** Les colonnes d'une table dépliée, et sa suppression quand elle est enregistrée. */
function Columns({ table, columns, registered, onDropTable }: {
  table: string; columns: Record<string, string> | undefined; registered: boolean;
  onDropTable: (table: string) => void;
}) {
  const entries = Object.entries(columns ?? {});
  return (
    <div className="ml-6 pl-3 border-l border-primary/20 py-1 space-y-1">
      {entries.length === 0 ? (
        <p className="text-[10px] text-outline py-0.5">
          {registered ? 'No columns reported.' : 'Columns appear once the topic has been read — click its name.'}
        </p>
      ) : entries.map(([col, type]) => (
        <div key={col} className="flex justify-between items-center text-[10px] py-0.5">
          <span className="text-on-surface-variant truncate pr-2 font-mono" title={col}>{col}</span>
          <span className="text-primary/60 font-mono uppercase shrink-0">{type}</span>
        </div>
      ))}
      {registered && (
        <button type="button" onClick={() => onDropTable(table)} aria-label={`Drop table ${table}`}
          className="min-h-6 inline-flex items-center gap-1 text-[10px] text-outline hover:text-error rounded">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">delete</span>Drop table
        </button>
      )}
    </div>
  );
}

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
 *
 * **Mémoïsé**, pour la raison exacte qui avait déjà valu à `ResultsGrid` de l'être : chaque frappe
 * dans l'éditeur appelle `updateSql`, donc `setTabs`, donc re-rend la page entière — et cette
 * colonne rend *toutes* les tables et *tous* les topics, `ScrollList` bornant la hauteur mais
 * montant chaque enfant. Sur un cluster de trois cents topics, cela fait quelques milliers
 * d'éléments React réconciliés par caractère tapé, pour un rendu identique tant que ni le
 * catalogue, ni les schémas dépliés, ni les requêtes sauvegardées n'ont bougé. La page fait sa
 * moitié du travail : elle passe des gestionnaires d'identité stable (`useStableCallback`), sans
 * quoi la comparaison de `React.memo` échouerait sur une lambda neuve à chaque rendu.
 */
const SchemaBrowserView = React.forwardRef<HTMLElement, SchemaBrowserProps>(function SchemaBrowser({
  schema, schemaLoading, onRefresh, width, onResizeStart, onResizeKey, widthMin, widthMax,
  expandedTables, tableSchemas, onToggleTable, onSelectFrom, onDropTable, onPreviewDdl, actionLabelFor,
  savedQueries, onLoadSaved, onDeleteSaved,
  saveInputVisible, saveInputName, onSaveInputChange, onSaveOpen, onSaveCancel, onSaveConfirm,
  activeTabName,
}, ref) {
  const [filter, setFilter] = useState('');
  const q = filter.trim().toLowerCase();
  const registered = useMemo(() => new Set(schema?.tables ?? []), [schema]);
  const topicTables = useMemo(() => new Set((schema?.topics ?? []).map(toTableName)), [schema]);
  const shownTopics = useMemo(() => (schema?.topics ?? []).filter(t =>
    !q || t.toLowerCase().includes(q) || toTableName(t).toLowerCase().includes(q)), [schema, q]);
  const otherTables = useMemo(() => (schema?.tables ?? []).filter(t =>
    !topicTables.has(t) && (!q || t.toLowerCase().includes(q))), [schema, topicTables, q]);
  const shownSaved = useMemo(() => savedQueries.filter(sq =>
    !q || sq.name.toLowerCase().includes(q) || sq.sql.toLowerCase().includes(q)), [savedQueries, q]);
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

    {/* L'en-tête répétait le titre de la page et annonçait « Flink SQL Engine » quel que soit le
        moteur ; sa place va au filtre (SQL-EDITOR-AUDIT.md, X8 et X10). Sur un vrai cluster la liste
        compte des milliers de topics, et rien ne permettait d'en trouver un sans faire défiler. */}
    <h1 className="sr-only">SQL Workbench</h1>
    <div className="p-3 flex items-center gap-2 border-b border-outline-variant/60">
      <Input
        type="search"
        aria-label="Filter tables and topics"
        placeholder="Filter tables and topics"
        value={filter}
        onChange={e => setFilter(e.target.value)}
        onKeyDown={e => { if (e.key === 'Escape') setFilter(''); }}
        className="flex-1 h-8 text-[12px]"
      />
      <button onClick={onRefresh} disabled={schemaLoading} aria-label="Refresh schema" className="min-w-6 min-h-6 p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors disabled:opacity-50" title="Refresh">
        <span className={`material-symbols-outlined text-[18px] ${schemaLoading ? 'animate-spin' : ''}`}>{schemaLoading ? 'progress_activity' : 'refresh'}</span>
      </button>
    </div>

    <div className="flex-1 overflow-y-auto custom-scrollbar p-4">
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
            <ScrollList count={shownSaved.length} className="space-y-1">
              {shownSaved.map(q => (
                <div key={q.id} className="flex items-center gap-1 py-1 px-2 rounded hover:bg-primary/5 transition-colors group/saved">
                  {/* Un `<button>`, comme les tables et les topics au-dessus. C'était le dernier
                      `<div onClick>` de cette colonne : la passe d'accessibilité avait converti
                      les deux listes voisines et laissé celle-ci, donc rouvrir une requête
                      sauvegardée restait impossible sans souris — sans arrêt de tabulation, sans
                      rôle, et sans nom annoncé. */}
                  <button type="button" onClick={() => onLoadSaved(q)}
                    aria-label={`Open the saved query ${q.name}`}
                    className="flex-1 min-w-0 text-left rounded">
                    {/* `<span>` et non `<p>` : le contenu d'un bouton est du contenu de phrase,
                        et un paragraphe n'en est pas. */}
                    <span className="block text-xs text-on-surface truncate font-medium">{q.name}</span>
                    <span className="block text-[10px] text-outline">{new Date(q.savedAt).toLocaleDateString()}</span>
                  </button>
                  <button type="button" onClick={() => onDeleteSaved(q.id)}
                    className="opacity-0 group-hover/saved:opacity-100 text-outline hover:text-error transition-all shrink-0" title="Delete" aria-label="Delete this saved query">
                    <span className="material-symbols-outlined text-sm">delete</span>
                  </button>
                </div>
              ))}
            </ScrollList>
          </div>
        </details>
        {/* Les topics — une ligne par topic. Il y avait deux listes du même objet : « Flink Tables »
            (`demo_orders_1_received`) et « Kafka Topics » (`demo.orders.1.received`), où le même clic
            dépliait les colonnes dans l'une et écrivait un SELECT dans l'autre. Ici le nom lit le
            topic, le chevron montre ses colonnes, et la suppression de la table enregistrée est dans
            le dépliant, plus à 4 px du bouton qui lit. */}
        <details className="group" open>
          <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
            <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
            <span className="material-symbols-outlined text-base text-on-surface-variant">topic</span>
            <span className="text-sm font-medium">Topics</span>
            <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{shownTopics.length}</span>
          </summary>
          <div className="pl-4 pt-1 space-y-0.5">
            {schemaLoading ? (
              <div className="flex items-center gap-2 py-3 px-2 text-on-surface-variant">
                <span className="material-symbols-outlined text-sm animate-spin">refresh</span>
                <span className="text-xs">Loading…</span>
              </div>
            ) : schema?.topics.length === 0 ? (
              <p className="text-[10px] text-outline px-2 py-2">
                {schema.kafkaError ? 'Topics could not be listed — see above' : 'No topics with messages'}
              </p>
            ) : shownTopics.length === 0 ? (
              <p className="text-[10px] text-outline px-2 py-2">No topic matches “{filter.trim()}”</p>
            ) : (
              <ScrollList count={shownTopics.length} className="space-y-0.5">
                {shownTopics.map(topic => {
                  const table = toTableName(topic);
                  return (
                    <div key={topic}>
                      <div className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/topic group/name">
                        <button type="button" onClick={() => onToggleTable(table)}
                          aria-expanded={!!expandedTables[table]} aria-label={`Show the columns of ${topic}`}
                          className="min-w-6 min-h-6 -ml-1 inline-flex items-center justify-center rounded text-on-surface-variant shrink-0">
                          <span className={`material-symbols-outlined text-xs transition-transform duration-200 ${expandedTables[table] ? 'rotate-90' : ''}`}>chevron_right</span>
                        </button>
                        <button type="button" onClick={() => onSelectFrom(table)}
                          className="flex-1 min-w-0 text-left rounded" title={topic} aria-label={actionLabelFor(topic)}>
                          <span className={cn('text-xs text-on-surface-variant hover:text-primary font-mono truncate block', REVEAL_ON_HOVER)}>{topic}</span>
                        </button>
                        <button type="button" onClick={e => { e.stopPropagation(); onPreviewDdl(topic); }}
                          className={cn(TOPIC_ICON_BUTTON, 'hover:text-primary')}
                          title="Preview DDL" aria-label={`Preview the generated DDL for ${topic}`}>
                          <span className="material-symbols-outlined text-sm">code</span>
                        </button>
                      </div>
                      {expandedTables[table] && (
                        <Columns table={table} columns={tableSchemas[table]}
                          registered={registered.has(table)} onDropTable={onDropTable} />
                      )}
                    </div>
                  );
                })}
              </ScrollList>
            )}
            <p className="text-[10px] text-outline px-2 pt-1">Only topics with messages shown</p>
          </div>
        </details>

        {/* Les tables que ce catalogue tient d'ailleurs que d'un topic — écrites à la main. */}
        {otherTables.length > 0 && (
          <details className="group" open>
            <summary className="flex items-center gap-2 py-1.5 px-2 rounded hover:bg-primary/10 cursor-pointer list-none">
              <span className="material-symbols-outlined text-sm text-primary group-open:rotate-90 transition-transform">chevron_right</span>
              <span className="material-symbols-outlined text-base text-on-surface-variant">grid_view</span>
              <span className="text-sm font-medium">Other tables</span>
              <span className="ml-auto text-[10px] bg-surface-container-highest px-1.5 py-0.5 rounded-full text-on-surface-variant tabular-nums">{otherTables.length}</span>
            </summary>
            <ScrollList count={otherTables.length} className="pl-4 pt-1 space-y-0.5">
              {otherTables.map(table => (
                <div key={table}>
                  <div className="flex items-center py-1 px-2 rounded hover:bg-primary/5 transition-colors group/tbl group/name">
                    <button type="button" onClick={() => onToggleTable(table)}
                      aria-expanded={!!expandedTables[table]} aria-label={`Show the columns of ${table}`}
                      className="min-w-6 min-h-6 -ml-1 inline-flex items-center justify-center rounded text-on-surface-variant shrink-0">
                      <span className={`material-symbols-outlined text-xs transition-transform duration-200 ${expandedTables[table] ? 'rotate-90' : ''}`}>chevron_right</span>
                    </button>
                    <button type="button" onClick={() => onSelectFrom(table)}
                      className="flex-1 min-w-0 text-left rounded" title={table} aria-label={actionLabelFor(table)}>
                      <span className={cn('text-xs text-on-surface truncate block font-mono', REVEAL_ON_HOVER)}>{table}</span>
                    </button>
                  </div>
                  {expandedTables[table] && (
                    <Columns table={table} columns={tableSchemas[table]} registered onDropTable={onDropTable} />
                  )}
                </div>
              ))}
            </ScrollList>
          </details>
        )}

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

export const SchemaBrowser = React.memo(SchemaBrowserView);
