// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * « KPI proposés pour ce cluster » — le panneau qui rend ce que
 * `POST /api/metrics/suggestions` a dérivé de l'audit et des traces Stream Flow.
 *
 * Le rendu suit la règle du service : rien ne s'affiche sans son observation. Chaque carte porte
 * ce qui a été mesuré, d'où sortent ses seuils et ce qu'elle suppose — la colonne de clé déduite,
 * l'agrégat que seul le planificateur Flink sait exécuter. C'est ce qui sépare une proposition
 * d'un bandeau décoratif, et la page des métriques a déjà payé une fois le prix des seconds.
 *
 * Le bouton n'enregistre pas : il ouvre l'éditeur pré-rempli, là où la prévisualisation tranche.
 */

import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { MetricSuggestion, MetricSuggestions } from '../../api/types';
import {
  describeEvidence, dismiss, dismissedSuggestions, readDismissed, restore, sourceLabel,
  stalenessNote, suggestionTopics, visibleSuggestions,
} from '../../pages/metricSuggestions';
import { Badge, Button, Card, EmptyState, ErrorPanel, Spinner, Tooltip } from '../ui';
import type { QueryErrorInfo } from '../../pages/queryError';

interface Props {
  response: MetricSuggestions | null;
  loading: boolean;
  error: QueryErrorInfo | null;
  /** Un audit plus récent que celui dont ces cartes sont issues, s'il y en a un. */
  newerAudit: string | null;
  onRefresh: () => void;
  onAdopt: (suggestion: MetricSuggestion) => void;
}

const SOURCE_TONE = {
  AUDIT: 'secondary', STREAM_FLOW: 'primary', LINEAGE: 'success', PROCESS_MINING: 'warning',
} as const;

const SuggestionCard: React.FC<{
  suggestion: MetricSuggestion;
  onAdopt: () => void;
  onDismiss: () => void;
}> = ({ suggestion, onAdopt, onDismiss }) => {
  const [assumptionsOpen, setAssumptionsOpen] = useState(false);
  const topics = suggestionTopics(suggestion);

  return (
    <Card padding="sm" className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-semibold text-on-surface text-[13px] leading-snug">{suggestion.title}</p>
          <p className="text-[12px] text-on-surface-variant mt-1 leading-relaxed">{suggestion.rationale}</p>
        </div>
        <Badge tone={SOURCE_TONE[suggestion.source] ?? 'neutral'} className="shrink-0">
          {sourceLabel(suggestion.source)}
        </Badge>
      </div>

      {topics.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {topics.map(topic => (
            <span key={topic} className="font-mono text-[10px] px-1.5 py-0.5 rounded bg-surface-container-high text-on-surface-variant">
              {topic}
            </span>
          ))}
          <Badge tone="neutral">{suggestion.metric.type}</Badge>
        </div>
      )}

      {/* Ce sur quoi la proposition repose : jamais vide, c'est la condition de son existence. */}
      <ul className="space-y-1">
        {suggestion.evidence.map((line, index) => (
          <li key={index} className="text-[11px] text-on-surface-variant leading-relaxed flex gap-1.5">
            <span aria-hidden="true" className="material-symbols-outlined text-[14px] shrink-0 mt-0.5">insights</span>
            <span>{line}</span>
          </li>
        ))}
      </ul>

      {suggestion.thresholdBasis && (
        <p className="text-[11px] text-on-surface-variant leading-relaxed flex gap-1.5">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px] shrink-0 mt-0.5">rule</span>
          <span>{suggestion.thresholdBasis}</span>
        </p>
      )}

      {suggestion.caveats.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setAssumptionsOpen(open => !open)}
            aria-expanded={assumptionsOpen}
            className="text-[11px] text-on-surface-variant hover:text-on-surface flex items-center gap-1 transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
              {assumptionsOpen ? 'expand_less' : 'expand_more'}
            </span>
            What this assumes ({suggestion.caveats.length})
          </button>
          {assumptionsOpen && (
            <ul className="mt-1.5 space-y-1 pl-5 list-disc marker:text-outline">
              {suggestion.caveats.map((caveat, index) => (
                <li key={index} className="text-[11px] text-on-surface-variant leading-relaxed">{caveat}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {suggestion.alreadyConfigured && (
        <p className="text-[11px] text-success flex items-center gap-1.5">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">check_circle</span>
          {/* Le nom porte le `title` plutôt que la ligne : c'est *lui* que l'on va chercher
              ensuite, et la sonde le signalait coupé (`p 334>320`) sans rien qui le porte. */}
          Already measured by{' '}
          <span className="font-mono truncate" title={suggestion.existingMetricName ?? undefined}>
            {suggestion.existingMetricName}
          </span>
        </p>
      )}

      {/* `flex-wrap` : à 310 px de carte les deux boutons demandent 320 px et la rangée débordait
          d'un conteneur en `overflow: hidden` — la fin du second bouton n'était atteignable par
          rien (W7 de MOBILE-LAYOUT-SCOPE.md). */}
      <div className="flex flex-wrap items-center gap-2 mt-auto pt-1">
        <Button
          variant={suggestion.alreadyConfigured ? 'ghost' : 'primary'}
          size="sm"
          icon="tune"
          onClick={onAdopt}
        >
          {suggestion.alreadyConfigured ? 'Open a variant' : 'Review & add'}
        </Button>
        <Tooltip content="Hides it on this browser only. The next audit re-derives it, and the panel keeps it hidden.">
          <Button variant="ghost" size="sm" icon="close" onClick={onDismiss}>Dismiss</Button>
        </Tooltip>
      </div>
    </Card>
  );
};

export const SuggestionsPanel: React.FC<Props> = ({ response, loading, error, newerAudit, onRefresh, onAdopt }) => {
  const [dismissed, setDismissed] = useState<string[]>(() => readDismissed());
  const [showDismissed, setShowDismissed] = useState(false);

  // Mémoïsé parce que `?? []` fabrique un tableau neuf à chaque rendu, ce qui invaliderait les
  // deux useMemo qui en dépendent — la liste serait retriée à chaque frappe ailleurs sur la page.
  const suggestions = useMemo(() => response?.suggestions ?? [], [response]);
  const visible = useMemo(() => visibleSuggestions(suggestions, dismissed), [suggestions, dismissed]);
  const hidden = useMemo(() => dismissedSuggestions(suggestions, dismissed), [suggestions, dismissed]);
  const evidence = useMemo(() => describeEvidence(response), [response]);
  const staleness = useMemo(() => stalenessNote(response), [response]);

  return (
    <section aria-labelledby="suggested-kpis">
      <div className="flex items-baseline justify-between gap-3 mb-3 flex-wrap">
        <div>
          <p id="suggested-kpis" className="text-xs font-bold text-on-surface-variant uppercase tracking-widest">
            Suggested for this cluster
          </p>
          <p className="text-[12px] text-on-surface-variant mt-1 max-w-3xl leading-relaxed">{evidence.summary}</p>
        </div>
        <Button variant="ghost" size="sm" icon="refresh" onClick={onRefresh} disabled={loading}>
          Re-derive
        </Button>
      </div>

      {error && <div className="mb-3"><ErrorPanel error={error} /></div>}

      {/* Une observation plus fraîche existe : le dire, et rendre le geste immédiat — le panneau
          ne dérive qu'au chargement, donc sans ça un audit lancé ailleurs reste invisible ici. */}
      {newerAudit && (
        <div className="mb-3 flex items-start gap-2 text-[11px] text-warning">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5">update</span>
          <span>{newerAudit}</span>
          <Button variant="ghost" size="sm" icon="refresh" onClick={onRefresh} disabled={loading}>
            Re-derive now
          </Button>
        </div>
      )}

      {staleness && (
        <p className="text-[11px] text-warning mb-3 flex items-start gap-1.5">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px] mt-0.5">schedule</span>
          <span>{staleness}</span>
        </p>
      )}

      {loading && !response ? (
        <div className="flex items-center gap-2 text-[12px] text-on-surface-variant py-6">
          <Spinner size={16} /> Deriving KPIs from what has been measured…
        </div>
      ) : evidence.waiting ? (
        <EmptyState
          icon="lightbulb"
          title="Nothing measured yet"
          description={evidence.summary}
          action={
            <div className="flex items-center gap-2 flex-wrap justify-center">
              {evidence.unlocks.map(unlock => (
                <Link key={unlock.to} to={unlock.to}>
                  <Button variant="secondary" size="sm">{unlock.label}</Button>
                </Link>
              ))}
            </div>
          }
        />
      ) : visible.length === 0 ? (
        <p className="text-[12px] text-on-surface-variant py-4">
          {hidden.length > 0
            ? 'Every proposal derived from what was measured has been dismissed on this browser.'
            : 'What was measured suggests no KPI beyond the ones already configured.'}
        </p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {visible.map(suggestion => (
            <SuggestionCard
              key={suggestion.id}
              suggestion={suggestion}
              onAdopt={() => onAdopt(suggestion)}
              onDismiss={() => setDismissed(dismiss(suggestion.id, dismissed))}
            />
          ))}
        </div>
      )}

      {/* Ce que l'observation a produit sans devenir un KPI — un constat sans carte reste un
          constat, et le taire laisserait croire que l'audit n'a rien trouvé là-dessus. */}
      {response && response.notes.length > 0 && (
        <ul className="mt-4 space-y-1.5">
          {response.notes.map((note, index) => (
            <li key={index} className="text-[11px] text-on-surface-variant leading-relaxed flex gap-1.5">
              <span aria-hidden="true" className="material-symbols-outlined text-[14px] shrink-0 mt-0.5">info</span>
              <span>{note}</span>
            </li>
          ))}
        </ul>
      )}

      {hidden.length > 0 && (
        <div className="mt-4">
          <button
            type="button"
            onClick={() => setShowDismissed(open => !open)}
            aria-expanded={showDismissed}
            className="text-[11px] text-on-surface-variant hover:text-on-surface flex items-center gap-1 transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
              {showDismissed ? 'expand_less' : 'expand_more'}
            </span>
            {hidden.length} dismissed proposal{hidden.length === 1 ? '' : 's'}
          </button>
          {showDismissed && (
            <ul className="mt-2 space-y-1">
              {hidden.map(suggestion => (
                <li key={suggestion.id} className="flex items-center gap-2 text-[11px] text-on-surface-variant">
                  <span className="truncate" title={suggestion.title}>{suggestion.title}</span>
                  <button
                    type="button"
                    className="text-primary hover:underline shrink-0"
                    onClick={() => setDismissed(restore(suggestion.id, dismissed))}
                  >
                    Restore
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
};

export default SuggestionsPanel;
