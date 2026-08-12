import type { FC } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolvePageName } from '../navigation';
import { connectionLabel, connectionTitle } from './connectionStatus';

interface HeaderProps {
  onMenuClick?: () => void;
  /** Ouvre la palette de commandes (⌘K). */
  onSearchClick?: () => void;
  isHealthy: boolean;
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
const Header: FC<HeaderProps> = ({ onMenuClick, onSearchClick, isHealthy, clusterName, bootstrapServers }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const pageName = resolvePageName(location.pathname);

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
          title={connectionTitle({ isHealthy, clusterName, bootstrapServers })}
        >
          <span className={`w-1.5 h-1.5 rounded-full ${isHealthy ? 'bg-success' : 'bg-error'}`} />
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
          className="flex items-center gap-2 h-8 pl-2.5 pr-2 rounded-md bg-surface-container-low border border-outline-variant/70 text-on-surface-variant hover:text-on-surface hover:border-outline transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[17px]">search</span>
          <span className="hidden sm:inline text-[13px] w-24 md:w-40 text-left text-outline">Search…</span>
          <kbd className="hidden sm:inline text-[10px] text-outline border border-outline-variant rounded px-1 py-0.5">⌘K</kbd>
        </button>

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
