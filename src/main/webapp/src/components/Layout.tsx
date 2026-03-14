import React, { useState, useEffect } from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Terminal,
  Settings,
  Activity,
  Wifi,
  WifiOff,
  Menu,
  X,
  Search,
  Database
} from 'lucide-react';
import axios from 'axios';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isSidebarOpen, setSidebarOpen] = useState(true);
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
    { name: 'Dashboard', path: '/', icon: LayoutDashboard },
    { name: 'SQL Workbench', path: '/query', icon: Terminal },
    { name: 'Lineage', path: '/lineage', icon: Activity },
    { name: 'Configuration', path: '/config', icon: Settings },
  ];

  return (
    <div className="flex h-screen bg-background-darker overflow-hidden font-sans">
      {/* Sidebar */}
      <aside className={cn(
        "bg-background-dark border-r border-white/5 transition-all duration-300 flex flex-col z-50",
        isSidebarOpen ? "w-64" : "w-20"
      )}>
        <div className="h-16 flex items-center px-6 border-b border-white/5 shrink-0">
          <Database className="text-primary w-6 h-6 shrink-0" />
          {isSidebarOpen && <span className="ml-3 font-bold text-slate-100 tracking-tight">KAFKA<span className="text-primary">EXPLORER</span></span>}
        </div>

        <nav className="flex-1 py-6 px-4 space-y-2 overflow-y-auto custom-scrollbar">
          {navItems.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              className={({ isActive }) => cn(
                "flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all group",
                isActive 
                  ? "bg-primary/10 text-primary border border-primary/20" 
                  : "text-slate-500 hover:text-slate-200 hover:bg-white/5 border border-transparent"
              )}
            >
              <item.icon className="w-5 h-5 shrink-0" />
              {isSidebarOpen && <span className="text-sm font-medium">{item.name}</span>}
            </NavLink>
          ))}
        </nav>

        <div className="p-4 border-t border-white/5">
          <div className={cn(
            "flex items-center gap-3 px-3 py-2 rounded-xl bg-black/20 border border-white/5",
            !isSidebarOpen && "justify-center"
          )}>
            {isHealthy ? (
              <Wifi className="w-4 h-4 text-emerald-500" />
            ) : (
              <WifiOff className="w-4 h-4 text-red-500" />
            )}
            {isSidebarOpen && (
              <span className={cn(
                "text-[10px] font-bold uppercase tracking-wider",
                isHealthy ? "text-emerald-500" : "text-red-500"
              )}>
                {isHealthy ? 'Connected' : 'Disconnected'}
              </span>
            )}
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col min-w-0 overflow-hidden relative">
        {/* Topbar */}
        <header className="h-16 border-b border-white/5 flex items-center justify-between px-8 bg-background-dark/30 backdrop-blur-sm shrink-0">
          <button 
            onClick={() => setSidebarOpen(!isSidebarOpen)}
            className="p-2 text-slate-400 hover:text-white transition-colors"
          >
            {isSidebarOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>

          <div className="flex items-center gap-4">
            <div className="relative group hidden sm:block">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-500 group-focus-within:text-primary transition-colors" />
              <input 
                type="text" 
                placeholder="Search resources..." 
                className="bg-black/30 border border-white/10 rounded-full pl-10 pr-4 py-1.5 text-xs outline-none focus:border-primary/50 focus:ring-1 focus:ring-primary/20 w-64 transition-all"
              />
            </div>
            <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-primary to-emerald-400 border border-white/10" />
          </div>
        </header>

        <div className="flex-1 overflow-y-auto custom-scrollbar">
          {children}
        </div>
      </main>
    </div>
  );
};

export default Layout;
