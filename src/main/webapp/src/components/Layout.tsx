// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useState } from 'react';
import type { FC, ReactNode } from 'react';
import axios from 'axios';
import Sidebar from './Sidebar';
import Header from './Header';
import CommandPalette from './CommandPalette';
import { setCatalog } from '../catalogStore';
import { DESKTOP_QUERY } from '../breakpoints';
import type { Health } from './connectionStatus';
import type { DashboardResponse } from '../api/types';

/*
 * Le rail de navigation en flux — donc l'écran considéré comme « desktop » — commence à `lg`,
 * pas à `md`. C'était 768 px, et `MOBILE-LAYOUT-SCOPE.md` a mesuré ce que ça coûtait : à cette
 * largeur exacte la navigation cessait d'être un tiroir hors-flux pour prendre 256 px dans le
 * flux, pendant que le navigateur de schéma de l'éditeur SQL gardait ses 288 px — l'éditeur
 * tombait de 64 px à 5 px en passant de 640 à 768. Une tablette en paysage était donc moins bien
 * lotie qu'un téléphone. Personne n'avait choisi ça : deux décisions de largeur indépendantes se
 * rencontraient. Le tiroir tient jusqu'à 1024 px, où les 256 px sont enfin payables.
 *
 * Les classes `lg:` de `Sidebar`, `Header` et du conteneur de contenu ci-dessous décrivent le même
 * seuil : elles doivent bouger ensemble, sinon le rail et le bouton qui l'ouvre se contredisent.
 *
 * La constante elle-même vit dans `breakpoints.ts` : la page Data Model choisit sa disposition au
 * même seuil, et deux fichiers portant chacun leur copie de `'(min-width: 1024px)'` sont la
 * manière dont la règle ci-dessus se perd.
 */

/**
 * Shell applicatif : sidebar (repliable en desktop, drawer en mobile) + header
 * + viewport de contenu à défilement interne (conserve les pages « app » en
 * pleine hauteur : éditeur Monaco, graphes, tableaux longs).
 *
 * Sonde `/api/dashboard` toutes les 30 s pour l'état de connexion et alimente
 * la recherche globale (topics + tables Flink) — logique identique à l'origine.
 */
const Layout: FC<{ children: ReactNode }> = ({ children }) => {
  // `null` = pas encore sondé. C'était `true`, donc la pastille s'affichait verte avant la
  // moindre vérification : « connecté » annoncé sur la foi de rien, et maintenu pendant tout le
  // temps que met un broker injoignable à ne pas répondre.
  const [isHealthy, setHealthy] = useState<Health>(null);
  // Vide, pas « Docker Cluster » : avant la première réponse de `/api/dashboard`, le nom du
  // cluster n'est pas connu, et un nom en dur est faux partout sauf sur le poste où il a été
  // écrit. Le header affiche « Connecting… » tant que cette valeur est vide.
  const [clusterName, setClusterName] = useState('');
  const [bootstrapServers, setBootstrapServers] = useState('');
  const [searchTopics, setSearchTopics] = useState<string[]>([]);
  const [searchTables, setSearchTables] = useState<string[]>([]);

  const [isCollapsed, setIsCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [isDesktop, setIsDesktop] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(DESKTOP_QUERY).matches,
  );

  useEffect(() => {
    const checkHealth = async () => {
      try {
        const response = await axios.get<DashboardResponse>('/api/dashboard');
        setHealthy(response.data.health);
        setSearchTopics(response.data.topics ?? []);
        setSearchTables(response.data.tables ?? []);
        // Partage la même liste avec les formulaires (autocomplétion des noms de topics),
        // sans requête supplémentaire.
        setCatalog(response.data.topics ?? [], response.data.tables ?? []);
        if (response.data.clusterName) setClusterName(response.data.clusterName);
        if (response.data.bootstrapServers) setBootstrapServers(response.data.bootstrapServers);
      } catch {
        setHealthy(false);
      }
    };
    checkHealth();
    const interval = setInterval(checkHealth, 30000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const mq = window.matchMedia(DESKTOP_QUERY);
    const handler = (e: MediaQueryListEvent) => {
      setIsDesktop(e.matches);
      if (e.matches) setMobileOpen(false);
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  // Raccourci global ⌘K / Ctrl+K : ouvre/ferme la palette de commandes.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const effectiveCollapsed = isDesktop ? isCollapsed : false;

  return (
    <div className="h-screen overflow-hidden bg-background bg-scene">
      <Sidebar
        isCollapsed={effectiveCollapsed}
        onToggle={() => setIsCollapsed((c) => !c)}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
      />

      {mobileOpen && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 lg:hidden animate-fade-in"
          onClick={() => setMobileOpen(false)}
          aria-hidden="true"
        />
      )}

      <div
        className={`h-screen flex flex-col min-w-0 transition-all duration-300 ml-0 ${
          effectiveCollapsed ? 'lg:ml-[68px]' : 'lg:ml-64'
        }`}
      >
        <Header
          onMenuClick={() => setMobileOpen(true)}
          onSearchClick={() => setPaletteOpen(true)}
          isHealthy={isHealthy}
          clusterName={clusterName}
          bootstrapServers={bootstrapServers}
        />
        <main className="flex-1 overflow-y-auto custom-scrollbar relative">
          {children}
        </main>
      </div>

      {/* Montée seulement ouverte : le remontage remet la recherche à zéro, sans effet dédié. */}
      {paletteOpen && <CommandPalette
        onClose={() => setPaletteOpen(false)}
        topics={searchTopics}
        tables={searchTables}
      />}
    </div>
  );
};

export default Layout;
