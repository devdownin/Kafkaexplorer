import { useEffect, useRef, useState } from 'react';
import type { FC } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolvePageName } from '../navigation';

interface HeaderProps {
  onMenuClick?: () => void;
  isHealthy: boolean;
  clusterName: string;
  searchTopics: string[];
  searchTables: string[];
}

/**
 * En-tête d'application : fil d'Ariane (cluster / page), état de connexion,
 * recherche globale (topics + tables Flink), aide et réglages.
 * La logique de recherche est identique à l'implémentation historique.
 */
const Header: FC<HeaderProps> = ({ onMenuClick, isHealthy, clusterName, searchTopics, searchTables }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const pageName = resolvePageName(location.pathname);

  const [searchQuery, setSearchQuery] = useState('');
  const [showSearch, setShowSearch] = useState(false);
  const searchRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) setShowSearch(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const q = searchQuery.toLowerCase().trim();
  const matchedTopics = q ? searchTopics.filter((t) => t.toLowerCase().includes(q)).slice(0, 6) : [];
  const matchedTables = q ? searchTables.filter((t) => t.toLowerCase().includes(q)).slice(0, 4) : [];

  return (
    <header className="header-border flex justify-between items-center gap-3 px-4 md:px-6 h-14 sticky top-0 z-30 bg-surface/80 backdrop-blur-md">
      <div className="flex items-center gap-2 min-w-0">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Open navigation"
          className="md:hidden p-1.5 -ml-1.5 rounded-md hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[22px]">menu</span>
        </button>

        {/* État de connexion */}
        <span
          className={`inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full border text-[12px] font-medium ${
            isHealthy
              ? 'bg-success/10 border-success/25 text-success'
              : 'bg-error/10 border-error/25 text-error'
          }`}
          title={isHealthy ? `Connected — ${clusterName}` : 'Disconnected'}
        >
          <span className={`w-1.5 h-1.5 rounded-full ${isHealthy ? 'bg-success' : 'bg-error'}`} />
          <span className="hidden sm:inline max-w-[180px] truncate">
            {isHealthy ? clusterName : 'Disconnected'}
          </span>
        </span>

        <nav aria-label="Breadcrumb" className="hidden md:flex items-center gap-2 min-w-0">
          <span aria-hidden="true" className="text-outline-variant text-[13px]">/</span>
          <span className="text-[13px] font-medium text-on-surface truncate">{pageName || 'Dashboard'}</span>
        </nav>
      </div>

      <div className="flex items-center gap-1">
        {/* Recherche globale */}
        <div ref={searchRef} className="relative">
          <div className="flex items-center h-8 bg-surface-container-low rounded-md px-2.5 border border-outline-variant/70 focus-within:border-primary/50 transition-colors">
            <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[17px] mr-1.5">search</span>
            <input
              className="bg-transparent border-none text-[13px] w-32 sm:w-52 p-0 outline-none text-on-surface placeholder:text-outline"
              placeholder="Search topics, tables…"
              type="text"
              aria-label="Search topics and tables"
              value={searchQuery}
              onChange={(e) => { setSearchQuery(e.target.value); setShowSearch(true); }}
              onFocus={() => setShowSearch(true)}
            />
            {searchQuery && (
              <button
                onClick={() => { setSearchQuery(''); setShowSearch(false); }}
                className="text-outline hover:text-on-surface ml-1"
                aria-label="Clear search"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-[16px]">close</span>
              </button>
            )}
          </div>

          {showSearch && q && (matchedTopics.length > 0 || matchedTables.length > 0) && (
            <div className="absolute top-full right-0 mt-1.5 w-72 bg-surface-container-high border border-outline-variant rounded-xl shadow-2xl z-50 overflow-hidden animate-slide-up">
              {matchedTopics.length > 0 && (
                <div>
                  <p className="px-3 py-1.5 text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em] border-b border-outline-variant/60">Kafka Topics</p>
                  {matchedTopics.map((t) => (
                    <button
                      key={t}
                      onClick={() => { navigate(`/topic/${t}`); setSearchQuery(''); setShowSearch(false); }}
                      className="w-full flex items-center gap-2 px-3 py-2 hover:bg-primary/10 transition-colors text-left"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-primary text-[17px]">topic</span>
                      <span className="text-[13px] font-mono text-on-surface truncate">{t}</span>
                    </button>
                  ))}
                </div>
              )}
              {matchedTables.length > 0 && (
                <div className={matchedTopics.length > 0 ? 'border-t border-outline-variant/60' : ''}>
                  <p className="px-3 py-1.5 text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em] border-b border-outline-variant/60">Flink Tables</p>
                  {matchedTables.map((t) => (
                    <button
                      key={t}
                      onClick={() => { navigate(`/query?sql=${encodeURIComponent(`SELECT * FROM ${t} LIMIT 50`)}`); setSearchQuery(''); setShowSearch(false); }}
                      className="w-full flex items-center gap-2 px-3 py-2 hover:bg-primary/10 transition-colors text-left"
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-[17px]">grid_view</span>
                      <span className="text-[13px] font-mono text-on-surface truncate">{t}</span>
                      <span className="ml-auto text-[11px] text-outline">Open</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
          {showSearch && q && matchedTopics.length === 0 && matchedTables.length === 0 && (
            <div className="absolute top-full right-0 mt-1.5 w-64 bg-surface-container-high border border-outline-variant rounded-xl shadow-2xl z-50 px-4 py-3 text-[12px] text-on-surface-variant animate-slide-up">
              No results for “<span className="text-on-surface">{q}</span>”
            </div>
          )}
        </div>

        <button
          onClick={() => navigate('/help')}
          className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
          title="Help"
          aria-label="Help"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[19px]">help</span>
        </button>
        <button
          onClick={() => navigate('/config')}
          className="p-1.5 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
          title="Settings"
          aria-label="Settings"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[19px]">settings</span>
        </button>
      </div>
    </header>
  );
};

export default Header;
