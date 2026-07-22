import type { FC } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
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
  const sections = groupNavItems(NAV_ITEMS);

  const linkClasses = (isActive: boolean) =>
    `group flex items-center rounded-lg transition-colors duration-150 cursor-pointer w-full ` +
    `${isCollapsed ? 'px-0 py-2.5 justify-center' : 'px-3 py-2'} ` +
    (isActive
      ? 'nav-active text-on-surface'
      : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high/70');

  return (
    <aside
      className={`fixed left-0 top-0 h-full flex flex-col px-3 py-4 z-50 bg-surface-container-low transition-all duration-300 w-64 ${
        isCollapsed ? 'md:w-[68px]' : 'md:w-64'
      } ${mobileOpen ? 'translate-x-0' : '-translate-x-full'} md:translate-x-0 border-r border-outline-variant/60`}
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
            className="hidden md:inline-flex p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors shrink-0"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_close</span>
          </button>
        )}
        {/* Fermeture du drawer mobile */}
        <button
          onClick={onMobileClose}
          aria-label="Close navigation"
          className="md:hidden p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors shrink-0"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[20px]">close</span>
        </button>
      </div>

      {isCollapsed && (
        <button
          onClick={onToggle}
          aria-label="Expand sidebar"
          title="Expand"
          className="hidden md:inline-flex mx-auto mb-4 p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_open</span>
        </button>
      )}

      {/* CTA principale : nouvelle requête SQL */}
      <button
        onClick={() => { navigate('/query'); onMobileClose?.(); }}
        title="New query"
        className={`mb-5 bg-primary text-on-primary rounded-lg font-medium flex items-center justify-center transition-all hover:bg-primary-fixed active:scale-[0.99] ${
          isCollapsed ? 'w-10 h-10 mx-auto' : 'py-2 px-3 gap-2'
        }`}
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">add</span>
        {!isCollapsed && <span className="text-[13px]">New query</span>}
      </button>

      <nav className="flex-1 overflow-y-auto space-y-1 min-h-0" aria-label="Primary">
        {sections.map((section, i) => (
          <div key={section.group ?? `top-${i}`} className={i > 0 ? 'pt-4' : ''}>
            {section.group && !isCollapsed && (
              <p className="px-3 pb-1.5 text-[11px] font-medium uppercase tracking-[0.05em] text-outline select-none">
                {section.group}
              </p>
            )}
            {section.group && isCollapsed && i > 0 && (
              <div className="mx-3 mb-3 border-t border-outline-variant/60" aria-hidden="true" />
            )}
            <div className="space-y-0.5">
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
