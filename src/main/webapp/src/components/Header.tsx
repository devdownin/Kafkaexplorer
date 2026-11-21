// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolvePageName } from '../navigation';
import { connectionLabel, connectionTitle, connectionTone, type Health } from './connectionStatus';

const PILL_TONES = {
  unknown: 'bg-surface-container-high border-outline-variant text-on-surface-variant',
  healthy: 'bg-success/10 border-success/25 text-success',
  unhealthy: 'bg-error/10 border-error/25 text-error',
} as const;

const DOT_TONES = {
  unknown: 'bg-outline',
  healthy: 'bg-success',
  unhealthy: 'bg-error',
} as const;

interface HeaderProps {
  theme: 'light' | 'dark';
  /** Strengthen the optical separation when the content plane has scrolled. */
  scrolled?: boolean;
  onThemeToggle: () => void;
  onMenuClick?: () => void;
  /** Ouvre la palette de commandes (⌘K). */
  onSearchClick?: () => void;
  /** `null` tant que le premier sondage n'a pas répondu — voir `connectionStatus.ts`. */
  isHealthy: Health;
  /** Libellé choisi au déploiement (`explorer.cluster-name`), ou vide tant que rien n'a répondu. */
  clusterName: string;
  /** Adresse d'amorçage effective, telle que le serveur l'utilise réellement. */
  bootstrapServers?: string;
}

/**
 * En-tête d'application : fil d'Ariane (cluster / page), état de connexion,
 * déclencheur de la palette de commandes (⌘K), aide et réglages.
 *
 * La pastille dit à quoi on est connecté, donc elle ne doit rien affirmer qu'elle n'ait vérifié.
 * Le libellé est un nom d'affichage — il valait « KRAFT 4.2 » par défaut, soit une version de
 * Kafka annoncée sans jamais avoir été demandée au broker, et inchangée après un repointage à
 * chaud par `POST /api/config`. Ce qui est vérifiable voyage à côté : l'adresse d'amorçage
 * effective, lue dans la configuration en cours d'exécution, en infobulle.
 */
const Header: FC<HeaderProps> = ({ onMenuClick, onSearchClick, isHealthy, clusterName, bootstrapServers, theme, onThemeToggle, scrolled = false }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const pageName = resolvePageName(location.pathname);
  const tone = connectionTone({ isHealthy });

  return (
    <header className={`kse-header ${scrolled ? 'kse-header-scrolled' : ''} flex justify-between items-center gap-3 px-4 md:px-6 h-14 shrink-0 sticky top-3 z-30 mx-3 md:mx-4 mt-3 mb-2`}>
      <div className="flex items-center gap-2 min-w-0">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Open navigation"
          className="lg:hidden p-1.5 -ml-1.5 rounded-md hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[22px]">menu</span>
        </button>

        {/* État de connexion — trois états, dont « on ne sait pas encore » */}
        <span
          className={`inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full border text-[12px] font-medium ${PILL_TONES[tone]}`}
          title={connectionTitle({ isHealthy, clusterName, bootstrapServers })}
        >
          <span
            className={`w-1.5 h-1.5 rounded-full ${DOT_TONES[tone]} ${tone === 'unknown' ? 'animate-pulse' : ''}`}
          />
          <span className="hidden sm:inline max-w-[180px] truncate">
            {connectionLabel({ isHealthy, clusterName })}
          </span>
        </span>

        <nav aria-label="Breadcrumb" className="hidden md:flex items-center gap-2 min-w-0">
          <span aria-hidden="true" className="text-outline-variant text-[13px]">/</span>
          <span className="text-[13px] font-medium text-on-surface truncate">{pageName || 'Dashboard'}</span>
        </nav>
      </div>

      <div className="flex items-center gap-1">
        {/* Déclencheur de la palette de commandes (recherche unifiée) */}
        <button
          type="button"
          onClick={onSearchClick}
          aria-label="Search (Command palette)"
          title="Search — ⌘K"
          className="kse-search-trigger flex items-center gap-2 h-9 pl-3 pr-2.5 text-on-surface-variant hover:text-on-surface"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[17px]">search</span>
          <span className="hidden sm:inline text-[13px] w-24 md:w-40 text-left text-outline">Search…</span>
          <kbd className="hidden sm:inline text-[11px] text-outline border border-outline-variant rounded px-1 py-0.5">⌘K</kbd>
        </button>

        <button
          type="button"
          onClick={onThemeToggle}
          title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
          aria-label={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
          aria-pressed={theme === 'light'}
          className="kse-header-action p-2 text-on-surface-variant hover:text-on-surface"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[19px]">{theme === 'light' ? 'dark_mode' : 'light_mode'}</span>
        </button>
        <button
          onClick={() => navigate('/help')}
          className="kse-header-action p-2 text-on-surface-variant hover:text-on-surface"
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
