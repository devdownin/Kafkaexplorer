import { useEffect, useState } from 'react';
import type { FC, ReactNode } from 'react';
import axios from 'axios';
import Sidebar from './Sidebar';
import Header from './Header';
import CommandPalette from './CommandPalette';

const DESKTOP_QUERY = '(min-width: 768px)';

/**
 * Shell applicatif : sidebar (repliable en desktop, drawer en mobile) + header
 * + viewport de contenu à défilement interne (conserve les pages « app » en
 * pleine hauteur : éditeur Monaco, graphes, tableaux longs).
 *
 * Sonde `/api/dashboard` toutes les 30 s pour l'état de connexion et alimente
 * la recherche globale (topics + tables Flink) — logique identique à l'origine.
 */
const Layout: FC<{ children: ReactNode }> = ({ children }) => {
  const [isHealthy, setHealthy] = useState(true);
  const [clusterName, setClusterName] = useState('Docker Cluster');
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
        const response = await axios.get('/api/dashboard');
        setHealthy(response.data.health);
        setSearchTopics(response.data.topics ?? []);
        setSearchTables(response.data.tables ?? []);
        if (response.data.clusterName) setClusterName(response.data.clusterName);
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
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 md:hidden animate-fade-in"
          onClick={() => setMobileOpen(false)}
          aria-hidden="true"
        />
      )}

      <div
        className={`h-screen flex flex-col min-w-0 transition-all duration-300 ml-0 ${
          effectiveCollapsed ? 'md:ml-[68px]' : 'md:ml-64'
        }`}
      >
        <Header
          onMenuClick={() => setMobileOpen(true)}
          onSearchClick={() => setPaletteOpen(true)}
          isHealthy={isHealthy}
          clusterName={clusterName}
        />
        <main className="flex-1 overflow-y-auto custom-scrollbar relative">
          {children}
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        topics={searchTopics}
        tables={searchTables}
      />
    </div>
  );
};

export default Layout;
