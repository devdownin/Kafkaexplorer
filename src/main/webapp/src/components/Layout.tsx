import React, { useState, useEffect, useRef } from 'react';
import { NavLink, Link, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isHealthy, setHealthy] = useState(true);
  const [clusterName, setClusterName] = useState('DOCKER CLUSTER');
  const navigate = useNavigate();
  const [searchQuery, setSearchQuery] = useState('');
  const [searchTopics, setSearchTopics] = useState<string[]>([]);
  const [searchTables, setSearchTables] = useState<string[]>([]);
  const [showSearch, setShowSearch] = useState(false);
  const searchRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const checkHealth = async () => {
      try {
        const response = await axios.get('/api/dashboard');
        setHealthy(response.data.health);
        setSearchTopics(response.data.topics ?? []);
        setSearchTables(response.data.tables ?? []);
        if (response.data.clusterName) setClusterName(response.data.clusterName);
      } catch (err) {
        setHealthy(false);
      }
    };
    checkHealth();
    const interval = setInterval(checkHealth, 30000);

    const onClickOutside = (e: MouseEvent) => {
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
        setShowSearch(false);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => { clearInterval(interval); document.removeEventListener('mousedown', onClickOutside); };
  }, []);

  const navItems = [
    { name: 'Dashboard', path: '/', icon: 'dashboard' },
    { name: 'SQL Editor', path: '/query', icon: 'code' },
    { name: 'Compare', path: '/compare', icon: 'compare_arrows' },
    { name: 'Metrics', path: '/metrics', icon: 'monitoring' },
    { name: 'Audit', path: '/audit', icon: 'assignment' },
    { name: 'Lineage', path: '/lineage', icon: 'account_tree' },
    { name: 'Stream Flow', path: '/stream-flow', icon: 'waves' },
    { name: 'Cluster', path: '/cluster', icon: 'hub' },
  ];

  const q = searchQuery.toLowerCase().trim();
  const matchedTopics = q ? searchTopics.filter(t => t.toLowerCase().includes(q)).slice(0, 6) : [];
  const matchedTables = q ? searchTables.filter(t => t.toLowerCase().includes(q)).slice(0, 4) : [];

  return (
    <div className="flex h-screen overflow-hidden bg-background-light dark:bg-background-dark font-display text-slate-900 dark:text-slate-100 antialiased">
      {/* Sidebar Navigation */}
      <aside className="w-64 border-r border-slate-200 dark:border-primary/10 bg-white dark:bg-background-dark flex flex-col shrink-0">
        <div className="p-6 flex flex-col gap-1">
          <Link to="/" className="text-primary text-lg font-bold tracking-tight">Kafka SQL Explorer</Link>
          <p className="text-slate-500 dark:text-primary/60 text-xs uppercase font-bold tracking-widest">Data Infrastructure</p>
        </div>
        <nav className="flex-1 px-4 space-y-1">
          {navItems.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              className={({ isActive }) => cn(
                "flex items-center gap-3 px-3 py-2 rounded-lg transition-colors group",
                isActive 
                  ? "bg-primary/10 text-primary"
                  : "text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-primary/5 hover:text-primary"
              )}
            >
              <span className="material-symbols-outlined text-[22px]">{item.icon}</span>
              <span className="text-sm font-medium">{item.name}</span>
            </NavLink>
          ))}
        </nav>
        <div className="p-4 border-t border-slate-200 dark:border-primary/10">
          <div className="flex items-center gap-3 px-2 py-2">
            <div className="w-8 h-8 rounded-full bg-primary/20 flex items-center justify-center text-primary">
              <span className="material-symbols-outlined text-sm">person</span>
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-xs font-bold truncate">Admin User</p>
              <p className="text-[10px] text-slate-500 dark:text-slate-400 truncate">admin@prod-cluster.io</p>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Top Bar */}
        <header className="h-14 border-b border-slate-200 dark:border-primary/10 bg-white dark:bg-background-dark flex items-center justify-between px-6 shrink-0">
          <div className="flex items-center gap-4">
            <div className={cn(
              "flex items-center gap-2 px-3 py-1 rounded-full border",
              isHealthy
                ? "bg-emerald-500/10 border-emerald-500/20"
                : "bg-red-500/10 border-red-500/20"
            )}>
              <span className="relative flex h-2 w-2">
                <span className={cn(
                  "animate-ping absolute inline-flex h-full w-full rounded-full opacity-75",
                  isHealthy ? "bg-emerald-400" : "bg-red-400"
                )}></span>
                <span className={cn(
                  "relative inline-flex rounded-full h-2 w-2",
                  isHealthy ? "bg-emerald-500" : "bg-red-500"
                )}></span>
              </span>
              <span className={cn(
                "text-xs font-bold uppercase tracking-wider",
                isHealthy ? "text-emerald-500" : "text-red-500"
              )}>
                {isHealthy ? `Connected - ${clusterName}` : 'Disconnected'}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-4">
            <div ref={searchRef} className="relative">
              <div className="flex items-center bg-slate-100 dark:bg-primary/5 rounded-lg px-3 py-1.5 border border-slate-200 dark:border-primary/10 focus-within:border-primary/40 transition-colors">
                <span className="material-symbols-outlined text-slate-400 text-lg mr-2">search</span>
                <input
                  className="bg-transparent border-none focus:ring-0 text-sm w-48 p-0 outline-none"
                  placeholder="Search topics, tables..."
                  type="text"
                  value={searchQuery}
                  onChange={e => { setSearchQuery(e.target.value); setShowSearch(true); }}
                  onFocus={() => setShowSearch(true)}
                />
                {searchQuery && (
                  <button onClick={() => { setSearchQuery(''); setShowSearch(false); }} className="text-slate-400 hover:text-slate-200 ml-1">
                    <span className="material-symbols-outlined text-base">close</span>
                  </button>
                )}
              </div>
              {showSearch && q && (matchedTopics.length > 0 || matchedTables.length > 0) && (
                <div className="absolute top-full right-0 mt-1 w-72 bg-background-dark border border-primary/20 rounded-xl shadow-2xl z-50 overflow-hidden">
                  {matchedTopics.length > 0 && (
                    <div>
                      <p className="px-3 py-1.5 text-[10px] font-bold text-slate-500 uppercase tracking-widest border-b border-primary/10">Kafka Topics</p>
                      {matchedTopics.map(t => (
                        <button
                          key={t}
                          onClick={() => { navigate(`/topic/${t}`); setSearchQuery(''); setShowSearch(false); }}
                          className="w-full flex items-center gap-2 px-3 py-2 hover:bg-primary/10 transition-colors text-left"
                        >
                          <span className="material-symbols-outlined text-primary text-base">topic</span>
                          <span className="text-sm font-mono text-slate-200 truncate">{t}</span>
                        </button>
                      ))}
                    </div>
                  )}
                  {matchedTables.length > 0 && (
                    <div className={matchedTopics.length > 0 ? 'border-t border-primary/10' : ''}>
                      <p className="px-3 py-1.5 text-[10px] font-bold text-slate-500 uppercase tracking-widest border-b border-primary/10">Flink Tables</p>
                      {matchedTables.map(t => (
                        <button
                          key={t}
                          onClick={() => { navigate(`/query?sql=${encodeURIComponent(`SELECT * FROM ${t} LIMIT 50`)}`); setSearchQuery(''); setShowSearch(false); }}
                          className="w-full flex items-center gap-2 px-3 py-2 hover:bg-primary/10 transition-colors text-left"
                        >
                          <span className="material-symbols-outlined text-slate-400 text-base">grid_view</span>
                          <span className="text-sm font-mono text-slate-200 truncate">{t}</span>
                          <span className="ml-auto text-[10px] text-slate-500">Open in editor</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {showSearch && q && matchedTopics.length === 0 && matchedTables.length === 0 && (
                <div className="absolute top-full right-0 mt-1 w-64 bg-background-dark border border-primary/20 rounded-xl shadow-2xl z-50 px-4 py-3 text-xs text-slate-500">
                  No results for "<span className="text-slate-300">{q}</span>"
                </div>
              )}
            </div>
            <button className="p-2 text-slate-500 hover:text-primary transition-colors">
              <span className="material-symbols-outlined">notifications</span>
            </button>
            <button onClick={() => navigate('/help')} className="p-2 text-slate-500 hover:text-primary transition-colors" title="Help">
              <span className="material-symbols-outlined">help</span>
            </button>
            <button onClick={() => navigate('/config')} className="p-2 text-slate-500 hover:text-primary transition-colors" title="Settings">
              <span className="material-symbols-outlined">settings</span>
            </button>
          </div>
        </header>

        {/* Page Content */}
        <div className="flex-1 overflow-y-auto custom-scrollbar relative">
          {children}
        </div>
      </main>
    </div>
  );
};

export default Layout;
