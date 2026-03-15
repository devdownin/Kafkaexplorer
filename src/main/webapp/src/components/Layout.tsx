import React, { useState, useEffect } from 'react';
import { NavLink, Link } from 'react-router-dom';
import axios from 'axios';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isHealthy, setHealthy] = useState(true);

  useEffect(() => {
    const checkHealth = async () => {
      try {
        const response = await axios.get('/api/dashboard');
        setHealthy(response.data.health);
      } catch (err) {
        setHealthy(false);
      }
    };
    checkHealth();
    const interval = setInterval(checkHealth, 30000);
    return () => clearInterval(interval);
  }, []);

  const navItems = [
    { name: 'Dashboard', path: '/', icon: 'dashboard' },
    { name: 'SQL Editor', path: '/query', icon: 'code' },
    { name: 'Compare', path: '/compare', icon: 'compare_arrows' },
    { name: 'Audit', path: '/audit', icon: 'assignment' },
    { name: 'Lineage', path: '/lineage', icon: 'account_tree' },
    { name: 'Stream Flow', path: '/stream-flow', icon: 'waves' },
  ];

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
                {isHealthy ? 'Connected - Prod Cluster' : 'Disconnected'}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-4">
            <div className="flex items-center bg-slate-100 dark:bg-primary/5 rounded-lg px-3 py-1.5 border border-slate-200 dark:border-primary/10">
              <span className="material-symbols-outlined text-slate-400 text-lg mr-2">search</span>
              <input className="bg-transparent border-none focus:ring-0 text-sm w-48 p-0" placeholder="Global search..." type="text"/>
            </div>
            <button className="p-2 text-slate-500 hover:text-primary transition-colors">
              <span className="material-symbols-outlined">notifications</span>
            </button>
            <button className="p-2 text-slate-500 hover:text-primary transition-colors">
              <span className="material-symbols-outlined">settings</span>
            </button>
          </div>
        </header>

        {/* Page Content */}
        <div className="flex-1 overflow-y-auto custom-scrollbar">
          {children}
        </div>
      </main>
    </div>
  );
};

export default Layout;
