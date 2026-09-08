// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * La moitié pure de l'écran MCP : ce qui se calcule sans React et se teste sans DOM.
 *
 * Nommé pour ce qu'il contient et non `Mcp.ts` en face de `Mcp.tsx` : deux fichiers qui ne
 * diffèrent que par la casse se confondent sur un système de fichiers insensible à la casse, et
 * l'import résout alors sur `undefined`. C'est la règle du dépôt, payée une fois en 110 tests
 * rouges sous Windows pendant que la CI Linux restait verte.
 */
import type {
  McpCallView,
  McpOverrideView,
  McpReplay,
  McpStatsView,
  McpToolRow,
  Measured,
  ObservedWindow,
} from '../../api/types';

/** Les fenêtres proposées par le sélecteur, et la seule qui est un défaut. */
export const WINDOWS = ['1h', '24h', '7d'] as const;
export type McpWindow = (typeof WINDOWS)[number];
export const DEFAULT_WINDOW: McpWindow = '24h';

export function isWindow(value: string | null): value is McpWindow {
  return value !== null && (WINDOWS as readonly string[]).includes(value);
}

/**
 * Ce que dit l'en-tête d'une carte au-dessus d'un chiffre calculé sur l'anneau.
 *
 * `null` quand la fenêtre est réellement couverte — pas de mise en garde là où il n'y a rien à
 * mettre en garde, sinon l'avertissement devient du décor et on cesse de le lire. Sinon la phrase
 * dit ce qui manque : l'anneau a évincé, donc la fenêtre affichée est un suffixe de la vérité.
 */
export function windowCaveat(window: ObservedWindow): string | null {
  if (window.droppedFromRing === 0) return null;
  const since = window.oldestCallAt
    ? ` Ce qui reste commence à ${new Date(window.oldestCallAt).toLocaleTimeString('fr-FR')}.`
    : '';
  return (
    `Le flux vif ne garde que ${window.ringCapacity} appels : ${window.droppedFromRing} ont été ` +
    `évincés depuis le démarrage. Ces chiffres portent sur ce qu'il reste, pas sur toute la ` +
    `fenêtre.${since}`
  );
}

/**
 * Ce que dit la carte quand rien n'est persisté au-delà de l'anneau.
 *
 * Distinct de l'avertissement ci-dessus : « il reste de la place » et « rien ne survit à un
 * redémarrage » sont deux faits, et le second est vrai même quand l'anneau n'a rien évincé.
 */
export function historyNotice(window: ObservedWindow): string | null {
  return window.auditPersisted
    ? null
    : "Rien n'est écrit sur le topic d'audit : ce que montre ce flux est tout ce qui existe, et il " +
        'disparaît au redémarrage.';
}

/** Rend une valeur mesurée, ou la raison pour laquelle elle ne l'est pas. Jamais un tiret. */
export function formatMeasured(value: Measured<number>, unit: string): string {
  return value.measured && value.value !== null ? `${formatNumber(value.value)} ${unit}` : 'non mesuré';
}

export function formatNumber(value: number): string {
  return value.toLocaleString('fr-FR');
}

/** Octets en unité lisible. Les plafonds sont en Mio, donc l'écran l'est aussi. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} Kio`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} Mio`;
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

/**
 * Le libellé d'un verdict, code JSON-RPC compris quand il y en a un.
 *
 * Le code voyage avec le mot : « refusé » n'aide personne, « refusé -32041 hors périmètre »
 * désigne le réglage à ouvrir.
 */
export function outcomeLabel(call: McpCallView): string {
  if (call.outcome === 'OK') return call.truncated ? 'OK (tronqué)' : 'OK';
  if (call.jsonRpcErrorCode === null) return call.outcome === 'DENIED' ? 'refusé' : 'erreur';
  return `${call.outcome === 'DENIED' ? 'refusé' : 'erreur'} ${call.jsonRpcErrorCode}`;
}

/**
 * Le pictogramme de couverture d'une ligne.
 *
 * C'est la colonne que l'écran existe pour montrer : une réponse reçue incomplète est là où naît
 * une conclusion fausse, et rien d'autre dans la ligne ne le dit.
 */
export function coverageLabel(call: McpCallView): { icon: string; title: string } {
  if (call.stopReason === null) {
    return { icon: '—', title: "cet outil ne rend pas d'enveloppe de couverture" };
  }
  if (!call.partialCoverage) {
    return { icon: '✓', title: 'passe complète : un résultat vide est une réponse négative' };
  }
  return {
    icon: '⚠',
    title:
      `passe incomplète (${call.stopReason}) : un résultat vide veut dire « pas trouvé dans ce ` +
      `qui a été lu », pas « n'existe pas »`,
  };
}

/** Les outils triés comme la table les montre : par catégorie, puis par nom. */
const CATEGORY_ORDER = ['EXPLORATION', 'CORRELATION', 'DIAGNOSTIC', 'WRITE'];

export function sortTools(tools: McpToolRow[]): McpToolRow[] {
  return [...tools].sort((a, b) => {
    const byCategory = CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category);
    return byCategory !== 0 ? byCategory : a.name.localeCompare(b.name);
  });
}

/**
 * La part de refus, pour la carte Appels.
 *
 * `null` sur zéro appel plutôt que 0 % : « aucun refus » et « aucun appel » sont deux états, et
 * afficher 0 % pour le second fait passer un serveur inutilisé pour un serveur sain.
 */
export function deniedShare(stats: McpStatsView): number | null {
  return stats.calls === 0 ? null : (stats.denied / stats.calls) * 100;
}

/** L'état des filtres, tel qu'il voyage dans l'URL — « regarde ce qu'il a fait » doit être un lien. */
export interface FeedFilters {
  tool: string;
  outcome: string;
  identity: string;
  origin: string;
}

export const EMPTY_FILTERS: FeedFilters = { tool: '', outcome: '', identity: '', origin: '' };

export function filtersFromParams(params: URLSearchParams): FeedFilters {
  return {
    tool: params.get('tool') ?? '',
    outcome: params.get('outcome') ?? '',
    identity: params.get('identity') ?? '',
    origin: params.get('origin') ?? '',
  };
}

/** N'écrit que ce qui est posé : une URL pleine de paramètres vides n'est pas partageable. */
export function filtersToParams(filters: FeedFilters, window: McpWindow, tab: string): URLSearchParams {
  const params = new URLSearchParams();
  if (tab !== 'catalog') params.set('tab', tab);
  if (window !== DEFAULT_WINDOW) params.set('window', window);
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value);
  }
  return params;
}

/** Les lignes filtrées, en CSV. L'export porte ce qui est à l'écran, pas tout l'anneau. */
export function callsToCsv(calls: McpCallView[]): string {
  const header = [
    'startedAt', 'origin', 'identity', 'clientInfo', 'tool', 'outcome',
    'jsonRpcErrorCode', 'deniedByGuard', 'durationMs', 'recordsScanned',
    'stopReason', 'truncated', 'outputBytes', 'correlationId',
  ];
  const escape = (value: unknown): string => {
    const text = value === null || value === undefined ? '' : String(value);
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const rows = calls.map((call) =>
    [
      call.startedAt, call.origin, call.identity, call.clientInfo, call.tool, call.outcome,
      call.jsonRpcErrorCode, call.deniedByGuard, call.durationMs,
      // Une mesure absente reste absente dans l'export : un 0 dans un tableur est une affirmation
      // que personne ne pourra plus distinguer d'une mesure.
      call.recordsScanned.measured ? call.recordsScanned.value : '',
      call.stopReason, call.truncated,
      call.outputBytes.measured ? call.outputBytes.value : '',
      call.correlationId,
    ].map(escape).join(','),
  );
  return [header.join(','), ...rows].join('\n');
}

/** L'âge d'une dérogation, en clair. C'est le chiffre qui rend une dérogation oubliée visible. */
export function overrideAge(ageMs: number): string {
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 1) return "à l'instant";
  if (minutes < 60) return `depuis ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `depuis ${hours} h`;
  return `depuis ${Math.floor(hours / 24)} j`;
}

/** Ce que dit une ligne du bandeau : le levier, sa cible, qui l'a mis et pourquoi. */
export function describeOverride(override: McpOverrideView): string {
  const what =
    override.kind === 'READONLY'
      ? 'Surface verrouillée en lecture seule'
      : override.kind === 'TOOL'
        ? `Outil ${override.target} coupé`
        : `Identité ${override.target} en quarantaine`;
  return `${what} par ${override.actor ?? 'un opérateur non nommé'} ${overrideAge(override.ageMs)} — ${
    override.reason ?? 'sans raison donnée'
  }`;
}

/**
 * Le bandeau des dérogations actives, ou `null`.
 *
 * Il ne disparaît jamais tant qu'une dérogation tient, et c'est tout son intérêt : le pire mode de
 * défaillance de ce commutateur est une dérogation qui survit à l'incident qu'elle répondait et
 * devient la configuration permanente que personne ne se souvient d'avoir choisie. Un bandeau qu'on
 * peut fermer serait fermé le premier jour.
 */
export function overrideBanner(overrides: McpOverrideView[]): string[] | null {
  return overrides.length === 0 ? null : overrides.map(describeOverride);
}

/**
 * Ce que le rejeu a réellement couvert.
 *
 * La phrase qui compte est la seconde : le balayage est borné, donc une fenêtre revenue vide est
 * soit une fenêtre où rien ne s'est passé, soit une fenêtre que le balayage n'a pas atteinte — deux
 * conclusions opposées tirées de la même liste vide. Sans elle, l'écran laisse choisir la
 * rassurante.
 */
export function replaySummary(replay: McpReplay): string {
  if (!replay.topicExists) {
    return "Rien n'a jamais été journalisé : le topic d'audit n'existe pas encore. Ce n'est pas une "
      + 'fenêtre vide, c\'est une piste vide.';
  }
  const found = `${replay.calls.length} appel(s) sur ${replay.recordsScanned} enregistrement(s) lus.`;
  return replay.scanReachedWindowStart
    ? `${found} Le balayage a atteint le début de la fenêtre : une absence en est bien une.`
    : `${found} Le balayage n'a PAS atteint le début de la fenêtre — la rétention en a retiré des `
      + 'enregistrements, donc une absence ici ne prouve rien.';
}

/**
 * Ce qu'un code de refus veut dire, et où se règle ce qui l'a produit.
 *
 * Les codes viennent de KIP-1318 et c'est leur mérite : un agent entraîné sur cette surface les
 * lit. Un opérateur, non — `-32041 · garde SCOPE` demande de connaître la table pour être compris,
 * et la carte « Refusé, et pourquoi » ne répondait donc à sa propre question que pour qui savait
 * déjà. La phrase dit la cause ; `setting` dit où la changer, ce qui est la seule action que la
 * carte appelle.
 *
 * `null` sur un code inconnu plutôt qu'une phrase générique : un code que cet écran ne connaît pas
 * vient d'une version du serveur plus récente que lui, et inventer un sens serait pire que de
 * laisser le nombre parler.
 */
export function explainDenial(code: number | null, guard: string | null): DenialExplanation | null {
  switch (code) {
    case -32001:
      return { sentence: "l'appelant ne s'est pas authentifié", setting: null };
    case -32029:
      return {
        sentence: "l'identité a dépassé son quota d'appels ; le refus dit combien de temps attendre",
        setting: 'explorer.mcp.rate-limit.calls-per-minute',
      };
    case -32040:
      return {
        sentence: 'une valeur lue dans le cluster servait d\'argument à un outil mutant',
        setting: null,
      };
    case -32041:
      return {
        sentence: "l'appel visait un topic ou un groupe hors de la portée configurée",
        setting: 'explorer.mcp.allowed-topic-prefixes / allowed-group-prefixes',
      };
    case -32042:
      return {
        sentence: 'cet outil exige une approbation et aucun jeton valide n\'accompagnait l\'appel',
        setting: 'explorer.mcp.approval-required-tools',
      };
    case -32043:
      return {
        sentence: "une dépendance n'a pas répondu — le broker, Flink ou le registre de schémas. "
          + "Ce n'est pas une garde : rien à desserrer dans le YAML",
        setting: null,
      };
    case -32044:
      // Deux causes sous un même code, et elles se règlent à deux endroits opposés.
      return guard === 'DENY_LIST'
        ? {
            sentence: 'un opérateur a coupé cet outil à chaud ; il reste listé parce que le client '
              + 'garde sa liste en cache',
            setting: 'le commutateur de cet écran',
          }
        : {
            sentence: 'refusé par une décision d\'opérateur',
            setting: 'explorer.mcp.console.allow-runtime-toggle',
          };
    case -32045:
      return {
        sentence: 'la réponse portait des données que le mode DLP interdit de laisser sortir',
        setting: 'explorer.mcp.dlp.mode',
      };
    case -32046:
      return { sentence: "les arguments de l'appel n'étaient pas valides", setting: null };
    case -32047:
      return {
        sentence: 'cette identité est en quarantaine',
        setting: 'le commutateur de cet écran',
      };
    default:
      return null;
  }
}

export interface DenialExplanation {
  /** La cause, en une phrase. */
  sentence: string;
  /** Où se règle ce qui a produit ce refus, ou `null` quand rien ne se règle. */
  setting: string | null;
}

/** Les outils que le filtre de la table laisse passer — nom et description, sans casse. */
export function filterTools(tools: McpToolRow[], query: string): McpToolRow[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return tools;
  return tools.filter(
    (tool) =>
      tool.name.toLowerCase().includes(needle)
      || tool.category.toLowerCase().includes(needle)
      || tool.description.toLowerCase().includes(needle),
  );
}

/**
 * La première phrase d'une description d'outil, pour la ligne repliée.
 *
 * Les descriptions des phases 3 et 4 font vingt lignes : elles sont écrites pour un modèle, qui
 * les lit entièrement, et la table les empilait telles quelles. L'opérateur, lui, balaie d'abord
 * des noms et des états — donc la ligne porte la première phrase et le reste se déplie.
 *
 * Coupé sur la phrase et non sur un nombre de caractères : une troncature au milieu d'un mot dit
 * « il y a plus » sans rien apprendre, là où une première phrase est une réponse complète à
 * « qu'est-ce que c'est ? ».
 */
export function firstSentence(description: string): string {
  const flat = description.replace(/\s+/g, ' ').trim();
  const stop = flat.search(/\.\s/);
  return stop === -1 ? flat : flat.slice(0, stop + 1);
}

/** Vrai quand replier la description cache réellement quelque chose. */
export function hasMoreThanFirstSentence(description: string): boolean {
  return firstSentence(description).length < description.replace(/\s+/g, ' ').trim().length;
}
