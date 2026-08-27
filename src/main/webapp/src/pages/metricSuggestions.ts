// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La logique pure du panneau « KPI proposés » de la page Métriques.
 *
 * La page ne savait rien du cluster : ses quatre exemples posaient un `COUNT(*)` sur la première
 * table trouvée, identiques sur un cluster de trois topics et sur un pipeline de commandes. Ce que
 * l'application observe pourtant — l'audit (volumes, constats, flux reconstruits) et Stream Flow
 * (le chemin réel d'une clé, saut par saut) — n'alimentait aucune proposition.
 *
 * Deux règles gouvernent ce qui suit, et ce sont celles du reste de l'écran :
 *
 *  1. **Une proposition n'apparaît qu'adossée à une observation.** Le serveur ne renvoie une carte
 *     qu'avec ses `evidence` ; le panneau les affiche telles quelles. Sans audit ni trace, il n'y
 *     a pas « aucun KPI à proposer » mais « rien n'a encore été mesuré », et ce n'est pas la même
 *     phrase — `describeEvidence` produit celle qui correspond.
 *  2. **Rien ne se crée tout seul.** Une carte ouvre l'éditeur pré-rempli ; l'enregistrement reste
 *     un geste. Le SQL repose sur une colonne de clé déduite et sur un moteur qui sait ou non
 *     exécuter l'agrégat : les deux voyagent en `caveats`, et la prévisualisation tranche.
 *
 * Les rejets sont locaux (`localStorage`) : ils disent « pas sur ce poste », pas « jamais pour
 * personne », et les identifiants de proposition sont stables d'un audit à l'autre pour que le
 * rejet tienne quand le prochain audit redérive la même carte.
 */

import type {
  AuditHistory, AuditRunSummary, MetricConfig, MetricSuggestion, MetricSuggestions, MetricSuggestionSource,
} from '../api/types';
import type { StoredMetricPriorities } from './processModelEvidence';

export const DISMISSED_KEY = 'kse:metric-suggestions-dismissed';
/** Au-delà, la liste de rejets décrit des propositions que plus aucun audit ne produit. */
export const MAX_DISMISSED = 100;

// ── Rejets ───────────────────────────────────────────────────────────────────

export function readDismissed(): string[] {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    // Quota, mode privé, JSON corrompu : au pire les propositions rejetées réapparaissent.
    return [];
  }
}

export function writeDismissed(ids: string[]): void {
  try {
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids.slice(0, MAX_DISMISSED)));
  } catch {
    /* voir readDismissed */
  }
}

export function dismiss(id: string, current: string[] = readDismissed()): string[] {
  if (current.includes(id)) return current;
  const next = [id, ...current].slice(0, MAX_DISMISSED);
  writeDismissed(next);
  return next;
}

export function restore(id: string, current: string[] = readDismissed()): string[] {
  const next = current.filter(entry => entry !== id);
  writeDismissed(next);
  return next;
}

// ── Tri et filtrage ──────────────────────────────────────────────────────────

/**
 * Ce que le panneau affiche : d'abord ce qui n'est pas encore mesuré, ensuite ce qu'une métrique
 * existante couvre déjà — cette dernière carte reste visible parce que « c'est déjà mesuré » est
 * une réponse à la question posée, pas une raison de faire disparaître le constat qui l'a produite.
 */
export function visibleSuggestions(
  suggestions: MetricSuggestion[],
  dismissed: string[],
): MetricSuggestion[] {
  const rejected = new Set(dismissed);
  return suggestions
    .filter(suggestion => !rejected.has(suggestion.id))
    .slice()
    .sort((a, b) => Number(a.alreadyConfigured) - Number(b.alreadyConfigured));
}

export function dismissedSuggestions(
  suggestions: MetricSuggestion[],
  dismissed: string[],
): MetricSuggestion[] {
  const rejected = new Set(dismissed);
  return suggestions.filter(suggestion => rejected.has(suggestion.id));
}

const SOURCE_LABELS: Record<MetricSuggestionSource, string> = {
  AUDIT: 'Cluster audit',
  STREAM_FLOW: 'Stream Flow trace',
  LINEAGE: 'Running Flink job',
  PROCESS_MINING: 'Process Mining mapping',
};

export function sourceLabel(source: MetricSuggestionSource): string {
  // Un libellé inconnu vaut mieux qu'un rendu vide : le serveur peut connaître une source que
  // cette version du front ignore.
  return SOURCE_LABELS[source] ?? source;
}

// ── État des observations ────────────────────────────────────────────────────

export interface EvidenceState {
  /** Une phrase disant sur quoi reposent les propositions, ou pourquoi il n'y en a pas. */
  summary: string;
  /** Ce qui manque et se lance depuis une autre page — vide quand tout a déjà tourné. */
  unlocks: Array<{ label: string; to: string }>;
  /** `true` quand rien n'a jamais été mesuré : le panneau attend, il ne conclut pas. */
  waiting: boolean;
}

/**
 * Le panneau doit distinguer trois situations qu'une liste vide confond : rien n'a été mesuré,
 * quelque chose l'a été mais n'appelle aucun KPI, et tout ce qui était proposé est déjà en place.
 */
export function describeEvidence(response: MetricSuggestions | null): EvidenceState {
  if (!response) {
    return { summary: 'Proposals have not been loaded yet.', unlocks: [], waiting: true };
  }

  const unlocks: Array<{ label: string; to: string }> = [];
  if (!response.auditAvailable) unlocks.push({ label: 'Run a cluster audit', to: '/audit' });
  if (response.flowChainsSubmitted === 0) unlocks.push({ label: 'Trace a message key', to: '/stream-flow' });
  if (!response.processMeasured) unlocks.push({ label: 'Measure a process', to: '/process-mining' });

  const parts: string[] = [];
  if (response.auditAvailable) {
    const when = response.auditTimestamp ? ` of ${formatDate(response.auditTimestamp)}` : '';
    const where = response.auditSource === 'HISTORY' ? ', read back from the history topic' : '';
    parts.push(`the audit${when}${where} (${response.auditTopics} topic${response.auditTopics === 1 ? '' : 's'})`);
  }
  if (response.flowChainsSubmitted > 0) {
    parts.push(`${response.flowChainsSubmitted} Stream Flow trace${response.flowChainsSubmitted === 1 ? '' : 's'} recorded in this browser`);
  }
  /*
   * L'audit et les traces ne sont plus les seules observations : un job Flink en cours et un
   * mapping Process Mining validé produisent aussi des cartes. Les compter d'après les
   * propositions elles-mêmes, sinon le panneau annoncerait « rien n'a été mesuré » au-dessus de
   * cartes qu'il est en train d'afficher.
   */
  const running = response.suggestions.filter(s => s.source === 'LINEAGE').length;
  if (running > 0) parts.push(`${running} running Flink job edge${running === 1 ? '' : 's'}`);
  /*
   * Deux observations distinctes portent la même source, et les confondre dirait faux : un mapping
   * validé est quelqu'un qui a nommé la clé, un processus mesuré est un graphe de successions
   * compté sur la fenêtre. `processMeasured` vient du serveur — une mesure qui n'a suggéré aucune
   * carte reste une mesure, et la déduire des propositions l'effacerait.
   */
  const mapped = response.suggestions.filter(s => s.id.startsWith('pm:status:')).length;
  if (mapped > 0) parts.push('a validated Process Mining field mapping');
  if (response.processMeasured) parts.push('a measured process');

  if (parts.length === 0) {
    return {
      summary: 'Nothing has been measured on this cluster yet, so there is nothing to derive a KPI '
        + 'from. Run an audit, trace a key across topics, or measure a process, and the proposals '
        + 'appear here.',
      unlocks,
      waiting: true,
    };
  }

  const derived = response.suggestions.length === 0
    ? 'No KPI could be derived from it'
    : `${response.suggestions.length} KPI${response.suggestions.length === 1 ? '' : 's'} derived from it`;

  return { summary: `${derived}: ${parts.join(', ')}.`, unlocks, waiting: false };
}

/**
 * L'âge de l'observation, quand il commence à compter — un audit d'il y a trois semaines.
 *
 * La phrase disait « topics may have changed since ». Ce n'est plus à elle de le dire : le serveur
 * vérifie maintenant, à chaque dérivation, que les topics existent encore et s'ils contiennent
 * quelque chose — une proposition dont un topic a disparu est écartée, une dont un topic est vide
 * est marquée. Répéter l'avertissement ici, c'est envoyer relancer un audit pour une question déjà
 * tranchée, et affaiblir celle qui reste ouverte.
 *
 * Ce que l'âge seul dit encore, et que rien d'autre ne peut dire : **les seuils** sont des
 * multiples d'un débit, d'une latence, d'un volume mesurés ce jour-là. Un topic qui existe et qui
 * est plein peut avoir triplé de trafic depuis, et aucune vérification de présence ne le verra.
 */
export function stalenessNote(
  response: MetricSuggestions | null,
  now: number = Date.now(),
  thresholdMs: number = 7 * 24 * 60 * 60 * 1000,
): string | null {
  if (!response?.auditTimestamp) return null;
  const age = now - response.auditTimestamp;
  if (age < thresholdMs) return null;
  const days = Math.floor(age / (24 * 60 * 60 * 1000));
  return `The audit these proposals rest on is ${days} day${days === 1 ? '' : 's'} old. Whether its `
    + 'topics still exist was checked just now; what was not is the traffic they carry, and every '
    + 'threshold below is a multiple of a rate measured that day. Re-run it before trusting them.';
}

// ── Un audit plus récent ─────────────────────────────────────────────────────

/**
 * Le run que le serveur retiendrait aujourd'hui, s'il redérivait maintenant.
 *
 * Mêmes exclusions que `MetricSuggestionService.readAudit` : un run `legacy` porte l'échelle de
 * sévérité retirée et ne se projette pas sur celle d'aujourd'hui, un run `FAILED` ne mesure rien.
 * Les reproduire ici est une duplication assumée — l'alternative serait d'annoncer « un audit plus
 * récent existe » au-dessus d'un bouton qui, une fois cliqué, redérive exactement les mêmes cartes.
 */
export function newestUsableRun(history: AuditHistory | null): AuditRunSummary | null {
  // `listHistory()` répond du plus récent au plus ancien. Le tableau est vérifié plutôt que
  // supposé : le type est écrit à la main, et une réponse sans `runs` — une version antérieure,
  // une erreur renvoyée en 200 — ferait tomber toute la page des métriques sur un `.find` de
  // `undefined`. C'est exactement ce qui avait tué la page Compare.
  const runs = history && Array.isArray(history.runs) ? history.runs : [];
  return runs.find(run => !run.legacy && run.status !== 'FAILED') ?? null;
}

/**
 * Ce qu'il faut dire quand l'audit sous-jacent a bougé depuis la dérivation affichée.
 *
 * Le panneau dérive au chargement de la page et plus jamais : lancer un audit dans un autre onglet
 * laissait des seuils calculés sur le run précédent, sans un mot. `stalenessNote` date déjà
 * l'observation ; celle-ci dit qu'une plus fraîche existe, ce qu'un âge ne peut pas exprimer.
 *
 * Trois réponses distinctes, parce qu'elles n'appellent pas le même geste : un audit *en cours*
 * n'est pas encore une évidence (le serveur refuse un rapport `RUNNING`, dont la liste de topics
 * change entre deux sondages), un premier audit débloque des cartes qui n'existaient pas, et un
 * run plus récent remplace celui sur lequel les seuils reposent.
 */
export function newerAuditNote(
  response: MetricSuggestions | null,
  history: AuditHistory | null,
): string | null {
  if (!response || !history) return null;

  const runs = Array.isArray(history.runs) ? history.runs : [];
  const running = runs.find(run => run.status === 'RUNNING');
  if (running && running.auditId !== response.auditId) {
    return 'An audit is running. These proposals rest on an earlier run — re-derive once it finishes.';
  }

  const newest = newestUsableRun(history);
  if (!newest || newest.auditId === response.auditId) return null;

  if (!response.auditAvailable) {
    return `A cluster audit has run since (${formatDate(newest.timestamp)}). Re-derive to get the KPIs it unlocks.`;
  }
  return `A more recent audit ran on ${formatDate(newest.timestamp)}; these proposals rest on the run of `
    + `${response.auditTimestamp ? formatDate(response.auditTimestamp) : 'an earlier date'}. Re-derive to use it.`;
}

// ── Vers l'éditeur ───────────────────────────────────────────────────────────

/**
 * La configuration pré-remplie telle que l'éditeur l'attend.
 *
 * L'`id` du serveur est retiré : une proposition n'est pas une métrique existante, et la garder
 * ferait écraser la métrique portant cet identifiant au premier enregistrement. Les champs
 * d'exécution (valeur, historique, erreur) sont remis à zéro pour la même raison — ils décrivent
 * une métrique qui a tourné, ce que celle-ci n'a pas encore fait.
 */
export function suggestionToDraft(suggestion: MetricSuggestion): Partial<MetricConfig> {
  const metric = suggestion.metric;
  return {
    ...metric,
    id: undefined,
    lastValue: null,
    lastUpdateTime: null,
    errorMessage: null,
    history: [],
    lastSummary: null,
    templateParams: metric.templateParams ?? {},
    labelFields: metric.labelFields ?? [],
  };
}

// ── Les données sont-elles là ? ──────────────────────────────────────────────

/**
 * Ce que la carte dit de la disponibilité des données, ou `null` quand il n'y a rien à dire.
 *
 * Trois états arrivent ici et un seul se rend : `EMPTY`. `POPULATED` n'a pas besoin d'être
 * annoncé — c'est le cas ordinaire, et un bandeau posé sur chaque carte cesse d'être lu ; et
 * surtout **`UNKNOWN` ne rend rien**, parce que la vérification n'a pas pu se faire et qu'afficher
 * quoi que ce soit reviendrait à répondre à une question jamais posée. Ce que la vérification n'a
 * pas pu faire est dit une fois, dans les `notes` de la réponse, pas vingt fois sur les cartes.
 *
 * Le serveur écarte les propositions dont un topic a disparu, donc `ABSENT` ne peut pas arriver ;
 * la branche existe pour que le jour où ce choix changerait, le panneau ait déjà une phrase.
 */
export function describeDataState(suggestion: MetricSuggestion): string | null {
  switch (suggestion.dataState) {
    case 'EMPTY':
      return 'A topic this reads holds no record right now — the KPI will report nothing until it fills.';
    case 'ABSENT':
      return 'A topic this reads is no longer on the cluster; the metric would fail at every refresh.';
    case 'POPULATED':
    case 'UNKNOWN':
    default:
      return null;
  }
}

/** Les topics qu'une proposition mesure, pour les puces de la carte. */
export function suggestionTopics(suggestion: MetricSuggestion): string[] {
  const params = suggestion.metric.templateParams ?? {};
  const named = ['sourceTopic', 'targetTopic', 'leftTopic', 'rightTopic', 'topic']
    .map(key => params[key])
    .filter((value): value is string => typeof value === 'string' && value.length > 0);
  if (named.length > 0) return Array.from(new Set(named));
  return suggestion.metric.labelTopic ? [suggestion.metric.labelTopic] : [];
}

function formatDate(epochMillis: number): string {
  const date = new Date(epochMillis);
  return Number.isNaN(date.getTime()) ? 'an unknown date' : date.toLocaleString();
}

// ── Les KPI que le modèle retiendrait ───────────────────────────────────────

/** Une entrée du bandeau : ce que le modèle a désigné, et la carte correspondante. */
export interface HighlightedSuggestion {
  suggestion: MetricSuggestion;
  /** La phrase du modèle. Un avis, pas une mesure — le rendu doit l'étiqueter comme tel. */
  why: string;
}

export interface PriorityHighlight {
  entries: HighlightedSuggestion[];
  /** Combien des choix du modèle ne correspondent plus à aucune carte proposée. */
  missing: number;
  measuredAt: number;
}

/**
 * Les cartes que le modèle a retenues, parmi celles réellement proposées.
 *
 * Le bandeau **désigne**, il ne réordonne pas : la liste garde son ordre, qui suit une règle
 * écrite et vérifiable (`BY_RELEVANCE` côté serveur), et l'avis du modèle s'affiche au-dessus
 * plutôt que de se substituer à elle en silence. Réordonner sur un jugement qu'on ne peut pas
 * rejouer, c'est perdre la seule chose que ce panneau garantit.
 *
 * Un choix dont la carte n'est plus proposée est **compté, pas rendu** : un audit plus récent, un
 * topic supprimé, et la carte disparaît légitimement. Le nombre le dit ; nommer une carte absente
 * ne servirait à rien.
 *
 * Rien n'est renvoyé quand la mesure qui a produit le choix n'est pas celle qui a produit les
 * cartes : deux pipelines différents, deux routes, et un avis sur l'un ne dit rien de l'autre.
 */
export function highlightPriorities(
  response: MetricSuggestions | null,
  chosen: StoredMetricPriorities | null,
  currentRoute: string | null,
): PriorityHighlight | null {
  if (!response || !chosen || chosen.priorities.length === 0) return null;
  if (!currentRoute || chosen.route !== currentRoute) return null;

  const byId = new Map(response.suggestions.map(s => [s.id, s]));
  const entries: HighlightedSuggestion[] = [];
  let missing = 0;
  for (const priority of chosen.priorities) {
    const suggestion = byId.get(priority.id);
    if (!suggestion) { missing++; continue; }
    entries.push({ suggestion, why: priority.why });
  }
  if (entries.length === 0 && missing === 0) return null;
  return { entries, missing, measuredAt: chosen.measuredAt };
}

/** Ce que le bandeau annonce, y compris quand il ne reste rien à désigner. */
export function describeHighlight(highlight: PriorityHighlight | null): string | null {
  if (!highlight) return null;
  const { entries, missing } = highlight;
  if (entries.length === 0) {
    return `The analysis picked ${missing} KPI(s) to follow, and none of them is still proposed — `
      + 'a later audit or a deleted topic will do that. Run a fresh Process Mining analysis to '
      + 'get an opinion on the cards below.';
  }
  const head = `Out of the KPIs this process supports, the analysis would follow ${
    entries.length === 1 ? 'this one' : `these ${entries.length}`}.`;
  const tail = missing > 0
    ? ` ${missing} other choice(s) name cards no longer proposed here.`
    : '';
  return head + tail
    + ' That is the model’s reading, not a measurement: the evidence and the thresholds on each '
    + 'card are unchanged, and so is the order of the list below.';
}
