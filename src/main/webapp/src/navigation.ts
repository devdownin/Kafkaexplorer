// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Source unique de vérité pour la navigation : chaque écran a UN nom, UNE
 * route et UNE icône, réutilisés partout (Sidebar, fil d'Ariane du Header).
 * Toute divergence de nommage doit se corriger ici, pas localement.
 *
 * Le regroupement réduit la charge cognitive : Explorer (données brutes) →
 * Observabilité (santé/mesures) → Analyse (graphes, mining).
 */
export interface NavItem {
  name: string;
  icon: string;
  path: string;
  /** Libellé du groupe de sidebar. Absent = hors groupe (épinglé en haut). */
  group?: string;
}

export const NAV_ITEMS: NavItem[] = [
  { name: 'Dashboard',      icon: 'dashboard',       path: '/' },

  { name: 'SQL Editor',     icon: 'terminal',        path: '/query',          group: 'Explore' },
  { name: 'Compare',        icon: 'compare_arrows',  path: '/compare',        group: 'Explore' },
  { name: 'Stream Flow',    icon: 'waves',           path: '/stream-flow',    group: 'Explore' },

  { name: 'Metrics',        icon: 'monitoring',      path: '/metrics',        group: 'Observe' },
  { name: 'Audit',          icon: 'fact_check',      path: '/audit',          group: 'Observe' },
  { name: 'Cluster',        icon: 'hub',             path: '/cluster',        group: 'Observe' },

  { name: 'Lineage',        icon: 'account_tree',    path: '/lineage',        group: 'Analyze' },
  { name: 'Data Model',     icon: 'schema',          path: '/data-model',     group: 'Analyze' },
  { name: 'Process Mining', icon: 'query_stats',     path: '/process-mining', group: 'Analyze' },
];

export const CONFIG_ITEM: NavItem = { name: 'Settings', icon: 'settings', path: '/config' };
export const HELP_ITEM: NavItem = { name: 'Help', icon: 'help', path: '/help' };

/** Route exacte → item, pour le fil d'Ariane du Header. */
export const NAV_BY_PATH: Record<string, NavItem> = Object.fromEntries(
  [...NAV_ITEMS, CONFIG_ITEM, HELP_ITEM].map((item) => [item.path, item]),
);

/** Résout le titre de page depuis un pathname (gère les routes dynamiques). */
export function resolvePageName(pathname: string): string {
  if (NAV_BY_PATH[pathname]) return NAV_BY_PATH[pathname].name;
  if (pathname.startsWith('/topic/')) return 'Topic Explorer';
  if (pathname.startsWith('/metrics/')) return 'Metrics Guide';
  return '';
}

/** Regroupe les entrées en sections ordonnées (group → items). */
export function groupNavItems(items: NavItem[]): { group?: string; items: NavItem[] }[] {
  const sections: { group?: string; items: NavItem[] }[] = [];
  for (const item of items) {
    const last = sections[sections.length - 1];
    if (last && last.group === item.group) last.items.push(item);
    else sections.push({ group: item.group, items: [item] });
  }
  return sections;
}
