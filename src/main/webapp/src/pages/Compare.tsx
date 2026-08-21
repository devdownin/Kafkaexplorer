// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useState, useEffect, useMemo, useRef } from 'react';
import axios from 'axios';
import { useToast } from '../components/Toast';
import { Button, Switch } from '../components/ui';
import { usePersistentState } from '../draftStore';
import {
  tryParse, diffFields, pairSamples, visiblePairs, countDiffs, countCompared,
  messageText, coordinates, type TopicMessage, type ComparePair,
} from './compare';
import type { TopicDetailResponse } from '../api/types';

const MessageCard: React.FC<{
  sample?: TopicMessage;
  paired?: TopicMessage;
  side: 'A' | 'B';
}> = ({ sample, paired, side }) => {
  // Une position sans vis-à-vis n'est pas un trou à masquer : les deux panneaux doivent rester
  // alignés, donc le rang existe des deux côtés et l'absence se dit.
  if (!sample) {
    return (
      <div className="rounded-lg border border-dashed border-outline-variant/60 p-3 text-[12px] text-outline italic">
        No message at this position
      </div>
    );
  }

  const text = messageText(sample);
  const parsed = tryParse(text);
  const pairedParsed = tryParse(messageText(paired));
  const diff = parsed && pairedParsed ? diffFields(
    side === 'A' ? parsed : pairedParsed,
    side === 'A' ? pairedParsed : parsed
  ) : null;

  const coords = (
    <div className="flex items-center justify-between gap-2 mb-2 text-[10px] text-outline font-mono">
      <span>{coordinates(sample)}</span>
      {sample.key && <span className="truncate max-w-[50%]" title={sample.key}>key {sample.key}</span>}
    </div>
  );

  // Payload non-JSON (XML, texte, valeur nulle) : on affiche le texte, jamais l'objet — rendre le
  // record lui-même est ce qui faisait tomber la page sur l'erreur React #31.
  if (!parsed) {
    return (
      <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3">
        {coords}
        <pre className="font-mono text-[12px] text-on-surface whitespace-pre-wrap break-words">
          {text || <span className="text-outline italic">empty payload</span>}
        </pre>
      </div>
    );
  }

  const fieldColors: Record<string, string> = {
    added: 'bg-success/10 text-success',
    removed: 'bg-error/10 text-error',
    changed: side === 'A' ? 'bg-error/10 text-error' : 'bg-success/10 text-success',
    same: '',
  };

  const entries = Object.entries(parsed);

  return (
    <div className="rounded-lg border border-outline-variant bg-surface-container-low p-3 hover:border-outline transition-colors">
      {coords}
      <div className="font-mono text-[12px] space-y-1">
        {entries.map(([k, v]) => {
          const status = diff?.[k] ?? 'same';
          return (
            <div key={k} className={`flex justify-between px-1 rounded gap-3 ${fieldColors[status]}`}>
              <span className="text-on-surface-variant shrink-0">{k}:</span>
              <span className="truncate text-right">{JSON.stringify(v)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

const Toggle: React.FC<{ label: string; on: boolean; onClick: () => void }> = ({ label, on, onClick }) => (
  <label className="flex items-center gap-2 cursor-pointer select-none">
    <span className="text-[11px] text-on-surface-variant font-medium">{label}</span>
    <Switch checked={on} aria-label={label} onChange={onClick} />
  </label>
);

/**
 * Défilement synchronisé des deux panneaux.
 *
 * Le bouton « Sync scroll » existait, était persisté dans `localStorage`… et lu par rien. Deux
 * colonnes qu'on compare ligne à ligne doivent descendre ensemble, sinon la comparaison se fait
 * de mémoire.
 *
 * Écrire `scrollTop` sur l'autre panneau y déclenche un `scroll`, qui voudrait à son tour piloter
 * le premier. La chaîne s'arrête d'elle-même parce qu'écrire une position **déjà atteinte** ne
 * déclenche aucun événement : le seul garde nécessaire est donc de ne pas réécrire ce qui est déjà
 * là. Une tolérance d'un pixel évite le va-et-vient sur les positions fractionnaires.
 *
 * C'est plus simple qu'un verrou « qui pilote », et surtout correct : un tel verrou, relâché au
 * tick suivant, fait ignorer un défilement de l'autre panneau survenu entre-temps.
 */
function useSyncedScroll(enabled: boolean) {
  const paneA = useRef<HTMLDivElement>(null);
  const paneB = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!enabled) return;
    const nodes = [paneA.current, paneB.current].filter((n): n is HTMLDivElement => n !== null);
    if (nodes.length < 2) return;

    const detach = nodes.map((node, index) => {
      const other = nodes[1 - index];
      const onScroll = () => {
        if (Math.abs(other.scrollTop - node.scrollTop) <= 1) return;
        other.scrollTop = node.scrollTop;
      };
      node.addEventListener('scroll', onScroll, { passive: true });
      return () => node.removeEventListener('scroll', onScroll);
    });

    return () => detach.forEach(off => off());
  }, [enabled]);

  return { paneA, paneB };
}

const TopicPane: React.FC<{
  side: 'A' | 'B'; topic: string; setTopic: (t: string) => void; count: number;
  /** Les deux panneaux reçoivent **les mêmes** paires : c'est ce qui les garde alignés. */
  pairs: ComparePair[]; topics: string[]; hasResult: boolean;
  scrollRef?: React.Ref<HTMLDivElement>;
}> = ({ side, topic, setTopic, count, pairs, topics, hasResult, scrollRef }) => (
  <div className="flex-1 flex flex-col rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
    <div className="p-3 border-b border-outline-variant/60 flex items-center justify-between bg-surface-container-high/60">
      <div className="flex flex-col gap-0.5 min-w-0">
        <span className="text-[11px] font-medium text-primary uppercase tracking-[0.05em]">Topic {side} ({side === 'A' ? 'Source' : 'Target'})</span>
        <select
          value={topic}
          onChange={e => setTopic(e.target.value)}
          aria-label={`Topic ${side}`}
          className="bg-transparent border-none text-on-surface font-medium p-0 focus:outline-none text-[13px] cursor-pointer max-w-full truncate"
        >
          {topics.map(t => <option key={t} value={t} className="bg-surface-container text-on-surface">{t}</option>)}
        </select>
      </div>
      <span className="text-[12px] text-on-surface-variant tabular-nums shrink-0">{count} msgs</span>
    </div>
    <div ref={scrollRef} data-testid={`pane-${side}`} className="flex-1 overflow-y-auto custom-scrollbar p-2 space-y-2">
      {!hasResult && (
        <div className="p-8 text-center text-outline text-[12px]">Select topics and run compare</div>
      )}
      {hasResult && pairs.length === 0 && (
        <div className="p-8 text-center text-outline text-[12px]">No differing message</div>
      )}
      {pairs.map((pair) => (
        <MessageCard
          key={pair.index}
          sample={side === 'A' ? pair.a : pair.b}
          paired={side === 'A' ? pair.b : pair.a}
          side={side}
        />
      ))}
    </div>
  </div>
);

const Compare: React.FC = () => {
  const { toast } = useToast();
  const [topics, setTopics] = useState<string[]>([]);
  const [topicA, setTopicA] = usePersistentState('compare:topicA', '');
  const [topicB, setTopicB] = usePersistentState('compare:topicB', '');
  const [syncCursors, setSyncCursors] = usePersistentState('compare:sync', true);
  const [showDiffOnly, setShowDiffOnly] = usePersistentState('compare:diffOnly', false);
  const [loading, setLoading] = useState(false);
  const [samplesA, setSamplesA] = useState<TopicMessage[]>([]);
  const [samplesB, setSamplesB] = useState<TopicMessage[]>([]);
  const [hasResult, setHasResult] = useState(false);

  useEffect(() => {
    axios.get<string[]>('/api/compare/topics')
      .then(res => {
        setTopics(res.data);
        /*
         * La pré-sélection ne s'applique qu'à défaut de choix conservé — sinon revenir sur la
         * page remplacerait les deux topics qu'on venait de comparer par les deux premiers de la
         * liste. Un topic disparu du cluster retombe sur ce défaut : le laisser sélectionné
         * afficherait un `<select>` vide sans dire pourquoi.
         */
        const keep = (drafted: string, fallback: string | undefined) =>
          drafted && res.data.includes(drafted) ? drafted : (fallback ?? '');
        setTopicA(prev => keep(prev, res.data[0]));
        setTopicB(prev => keep(prev, res.data[1]));
      })
      .catch(() => toast('Failed to load topics', 'error'));
  // eslint-disable-next-line react-hooks/exhaustive-deps -- fetch once on mount; toast is stable
  }, []);

  const runCompare = async () => {
    if (!topicA || !topicB) { toast('Select two topics to compare', 'info'); return; }
    setLoading(true);
    setHasResult(false);
    try {
      const [resA, resB] = await Promise.all([
        axios.get<TopicDetailResponse>(`/api/topic/${topicA}`),
        axios.get<TopicDetailResponse>(`/api/topic/${topicB}`),
      ]);
      // `?? []` des deux côtés, y compris pour le message de succès : il lisait
      // `resA.data.samples.length` sur la réponse brute, donc une réponse sans `samples` levait
      // dans le `try` et se rapportait comme un échec de chargement.
      const nextA = resA.data.samples ?? [];
      const nextB = resB.data.samples ?? [];
      setSamplesA(nextA);
      setSamplesB(nextB);
      setHasResult(true);
      toast(`Loaded ${nextA.length} + ${nextB.length} messages`, 'success');
    } catch {
      toast('Failed to fetch topic samples', 'error');
    } finally {
      setLoading(false);
    }
  };

  // Une seule liste de paires, partagée par les deux panneaux et par le pied de page : le filtre
  // « Diff only » s'applique aux paires, jamais à chaque colonne séparément.
  const pairs = useMemo(() => pairSamples(samplesA, samplesB), [samplesA, samplesB]);
  const shown = useMemo(() => visiblePairs(pairs, showDiffOnly), [pairs, showDiffOnly]);
  const diffCount = useMemo(() => countDiffs(pairs), [pairs]);
  const comparedCount = useMemo(() => countCompared(pairs), [pairs]);
  const { paneA, paneB } = useSyncedScroll(syncCursors);

  return (
    <div className="flex-1 flex flex-col p-4 gap-4 overflow-hidden h-full">
      {/*
        Le bandeau portait un « Shared Filter Context (optional) » : une zone de saisie SQL sans
        `value`, sans `onChange` et sans `ref`. On y tapait un filtre, il ne partait nulle part, et
        rien ne le disait. Le câbler voudrait dire exécuter du SQL par topic — c'est le métier de
        l'éditeur, pas de cette page, qui lit des échantillons de messages. Une commande qui ne fait
        rien coûte plus cher que son absence.
      */}
      <section className="flex flex-col">
        <div className="flex flex-col rounded-xl overflow-hidden bg-surface-container ring-1 ring-white/[0.045]">
          <div className="flex justify-between items-center gap-3 p-3 bg-surface-container-high/40">
            <div className="flex items-center gap-5">
              <Toggle label="Sync scroll" on={syncCursors} onClick={() => setSyncCursors(!syncCursors)} />
              <Toggle label="Diff only" on={showDiffOnly} onClick={() => setShowDiffOnly(!showDiffOnly)} />
            </div>
            <Button variant="primary" icon={loading ? undefined : 'compare_arrows'} loading={loading} onClick={runCompare} disabled={loading}>
              {loading ? 'Loading…' : 'Run compare'}
            </Button>
          </div>
        </div>
      </section>

      {/* Side-by-side */}
      <section className="flex-1 flex gap-4 overflow-hidden">
        <TopicPane side="A" topic={topicA} setTopic={setTopicA} count={samplesA.length} pairs={shown} topics={topics} hasResult={hasResult} scrollRef={paneA} />
        <TopicPane side="B" topic={topicB} setTopic={setTopicB} count={samplesB.length} pairs={shown} topics={topics} hasResult={hasResult} scrollRef={paneB} />
      </section>

      {/* Diff Summary Bar */}
      <footer className="h-11 border border-outline-variant/60 bg-surface-container rounded-xl flex items-center px-4 justify-between gap-3 text-[12px]">
        <div className="flex gap-4">
          {hasResult ? (
            <>
              <span className="text-on-surface-variant">Compared <b className="text-on-surface tabular-nums">{comparedCount}</b></span>
              <span className="text-on-surface-variant">Differences <b className={`tabular-nums ${diffCount > 0 ? 'text-warning' : 'text-success'}`}>{diffCount}</b></span>
            </>
          ) : (
            <span className="text-outline">No comparison run yet</span>
          )}
        </div>
        <div className="flex gap-4 items-center">
          <div className="flex items-center gap-1.5">
            <div className="w-1.5 h-1.5 rounded-full bg-success" />
            <span className="text-on-surface-variant text-[11px]">Added / Higher</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-1.5 h-1.5 rounded-full bg-error" />
            <span className="text-on-surface-variant text-[11px]">Removed / Lower</span>
          </div>
          {hasResult && (
            <button
              onClick={() => {
                const report = { topicA, topicB, diffCount, samplesA, samplesB, exportedAt: new Date().toISOString() };
                const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url; a.download = `diff-${topicA}-vs-${topicB}.json`; a.click();
                URL.revokeObjectURL(url);
              }}
              className="flex items-center gap-1 border-l border-outline-variant pl-4 hover:text-on-surface text-on-surface-variant transition-colors"
            >
              <span className="material-symbols-outlined text-[16px]">download</span>
              <span>Export</span>
            </button>
          )}
        </div>
      </footer>
    </div>
  );
};

export default Compare;
