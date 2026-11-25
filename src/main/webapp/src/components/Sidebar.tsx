// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useRef, useState, type FC } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { NAV_ITEMS, CONFIG_ITEM, HELP_ITEM, groupNavItems } from '../navigation';

interface SidebarProps {
  isCollapsed: boolean;
  onToggle: () => void;
  mobileOpen?: boolean;
  onMobileClose?: () => void;
}

/** Logo : flux d'événements stylisé (nœuds → partitions). */
const StreamMark: FC<{ size?: number }> = ({ size = 20 }) => (
  <svg width={size} height={size} viewBox="0 0 22 22" fill="none" aria-hidden="true">
    <circle cx="4"  cy="11" r="2" fill="currentColor" />
    <circle cx="11" cy="5"  r="1.7" fill="currentColor" opacity="0.7" />
    <circle cx="11" cy="17" r="1.7" fill="currentColor" opacity="0.7" />
    <circle cx="18" cy="8"  r="1.7" fill="currentColor" opacity="0.55" />
    <circle cx="18" cy="15" r="1.7" fill="currentColor" opacity="0.55" />
    <path d="M5.6 10 9.5 5.8M5.6 12 9.5 16.2M12.5 5.4 16.4 7.6M12.5 16.6 16.4 14.6" stroke="currentColor" strokeWidth="0.9" opacity="0.4" />
  </svg>
);

const Sidebar: FC<SidebarProps> = ({ isCollapsed, onToggle, mobileOpen = false, onMobileClose }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const sections = groupNavItems(NAV_ITEMS);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => new Set());
  const activeGroup = sections.find(section => section.group && section.items.some(item =>
    item.path === location.pathname || (item.path !== '/' && location.pathname.startsWith(`${item.path}/`)),
  ))?.group ?? (location.pathname.startsWith('/topic/') ? 'Explore' : undefined);
  const navRef = useRef<HTMLElement>(null);
  const [moreBelow, setMoreBelow] = useState(false);
  const measureNav = () => {
    const nav = navRef.current;
    if (nav) setMoreBelow(nav.scrollHeight - nav.scrollTop - nav.clientHeight > 4);
  };
  useEffect(() => {
    const nav = navRef.current;
    if (!nav || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measureNav);
    observer.observe(nav);
    // The content too: its height settles after the first paint (fonts, icons), which moves the edge.
    Array.from(nav.children).forEach(child => observer.observe(child));
    measureNav();
    return () => observer.disconnect();
  }, [isCollapsed]);

  const linkClasses = (isActive: boolean) =>
    `group flex items-center rounded-xl transition-colors duration-150 cursor-pointer w-full ` +
    `${isCollapsed ? 'px-0 py-2.5 justify-center' : 'px-3 py-2'} ` +
    (isActive
      ? 'nav-active text-on-surface'
      : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high/70');

  return (
    <aside
      className={`kse-sidebar fixed left-0 top-0 bottom-0 lg:left-3 lg:top-3 lg:bottom-3 h-auto flex flex-col px-3 py-4 z-50 transition-all duration-300 w-64 ${
        isCollapsed ? 'lg:w-[68px]' : 'lg:w-64'
      } ${mobileOpen ? 'translate-x-0' : '-translate-x-full'} lg:translate-x-0`}
    >
      {/* Logo */}
      <div className={`flex items-center mb-6 ${isCollapsed ? 'justify-center' : 'justify-between px-1'}`}>
        {!isCollapsed ? (
          <NavLink to="/" onClick={onMobileClose} className="flex items-center gap-2.5 animate-fade-in">
            <span className="text-primary shrink-0"><StreamMark size={20} /></span>
            <span className="text-[15px] font-semibold tracking-tight text-on-surface leading-none">Kafka Explorer</span>
          </NavLink>
        ) : (
          <span className="text-primary"><StreamMark size={20} /></span>
        )}
        {!isCollapsed && (
          <button
            onClick={onToggle}
            aria-label="Collapse sidebar"
            className="hidden lg:inline-flex p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors shrink-0"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_close</span>
          </button>
        )}
        {/* Fermeture du drawer mobile */}
        <button
          onClick={onMobileClose}
          aria-label="Close navigation"
          className="lg:hidden p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors shrink-0"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[20px]">close</span>
        </button>
      </div>

      {isCollapsed && (
        <button
          onClick={onToggle}
          aria-label="Expand sidebar"
          title="Expand"
          className="hidden lg:inline-flex mx-auto mb-4 p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_open</span>
        </button>
      )}

      {/* CTA principale : nouvelle requête SQL */}
      <button
        onClick={() => { navigate('/query'); onMobileClose?.(); }}
        title="New query"
        className={`mb-4 rounded-xl kse-btn-prominent kse-interactive text-on-primary font-medium flex items-center justify-center transition-all active:scale-[0.99] ${
          isCollapsed ? 'w-10 h-10 mx-auto' : 'py-2 px-3 gap-2'
        }`}
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">add</span>
        {!isCollapsed && <span className="text-[13px]">New query</span>}
      </button>

      {/* The list scrolls when the window is short; a last entry cut flat looks like a defect, so an
          edge fades while there is more below. */}
      <nav ref={navRef} onScroll={measureNav}
        className={`flex-1 overflow-y-auto space-y-1 min-h-0 ${moreBelow ? 'kse-fade-bottom' : ''}`} aria-label="Primary">
        {sections.map((section, i) => (
          <div key={section.group ?? `top-${i}`} className={i > 0 ? 'pt-3' : ''}>
            {section.group && !isCollapsed && (
              <button
                type="button"
                className="kse-nav-group-trigger"
                aria-expanded={!(collapsedGroups.has(section.group) && activeGroup !== section.group)}
                aria-controls={`kse-nav-${section.group.toLowerCase()}`}
                disabled={activeGroup === section.group}
                onClick={() => setCollapsedGroups(current => {
                  const next = new Set(current);
                  if (next.has(section.group!)) next.delete(section.group!);
                  else next.add(section.group!);
                  return next;
                })}
              >
                <span>{section.group}</span>
                <span className="flex items-center gap-2">
                  <span className="kse-nav-group-count">{section.items.length}</span>
                  <span aria-hidden="true" className="material-symbols-outlined kse-nav-group-chevron">
                    {collapsedGroups.has(section.group) && activeGroup !== section.group ? 'chevron_right' : 'expand_more'}
                  </span>
                </span>
              </button>
            )}
            {section.group && isCollapsed && i > 0 && (
              <div className="mx-3 mb-3 border-t border-outline-variant/60" aria-hidden="true" />
            )}
            <div
              id={section.group ? `kse-nav-${section.group.toLowerCase()}` : undefined}
              className="space-y-0.5"
              hidden={Boolean(section.group && !isCollapsed && collapsedGroups.has(section.group) && activeGroup !== section.group)}
            >
              {section.items.map((item) => (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === '/'}
                  onClick={onMobileClose}
                  title={isCollapsed ? item.name : undefined}
                  className={({ isActive }) => linkClasses(isActive)}
                >
                  <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${isCollapsed ? 'mr-0' : 'mr-2.5'}`}>
                    {item.icon}
                  </span>
                  {!isCollapsed && <span className="text-[13px] font-medium">{item.name}</span>}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <div className="mt-auto pt-3 border-t border-outline-variant/60 space-y-0.5">
        {[CONFIG_ITEM, HELP_ITEM].map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            onClick={onMobileClose}
            title={isCollapsed ? item.name : undefined}
            className={({ isActive }) => linkClasses(isActive)}
          >
            <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${isCollapsed ? 'mr-0' : 'mr-2.5'}`}>
              {item.icon}
            </span>
            {!isCollapsed && <span className="text-[13px] font-medium">{item.name}</span>}
          </NavLink>
        ))}
      </div>
    </aside>
  );
};

export default Sidebar;
