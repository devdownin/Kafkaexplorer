// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import type {
  McpCallView, McpOverrideView, McpReplay, McpStatsView, McpToolRow, Measured, ObservedWindow,
} from '../../api/types';
import {
  DEFAULT_WINDOW, callsToCsv, coverageLabel, deniedShare, filtersFromParams, filtersToParams,
  formatBytes, formatMeasured, historyNotice, isWindow, outcomeLabel, overrideAge,
  explainDenial, filterTools, firstSentence, hasMoreThanFirstSentence, overrideBanner,
  replaySummary, sortTools, windowCaveat,
} from './mcpConsoleLogic';

const measured = (value: number): Measured<number> => ({ value, measured: true, reason: null });
const unmeasured = (reason: string): Measured<number> => ({ value: null, measured: false, reason });

const window_ = (over: Partial<ObservedWindow> = {}): ObservedWindow => ({
  requestedWindowMs: 86_400_000,
  oldestCallAt: null,
  callsHeld: 10,
  ringCapacity: 2000,
  droppedFromRing: 0,
  auditPersisted: false,
  ...over,
});

const call = (over: Partial<McpCallView> = {}): McpCallView => ({
  correlationId: 'c1',
  startedAt: '2026-09-07T14:02:31Z',
  durationMs: 18_400,
  origin: 'AGENT',
  identity: 'svc-sre@corp',
  clientInfo: 'claude-code/1.4.2',
  tool: 'kex_trace_key',
  redactedParams: {},
  outcome: 'OK',
  jsonRpcErrorCode: null,
  deniedByGuard: null,
  recordsScanned: measured(8400),
  stopReason: 'EXHAUSTED',
  partialCoverage: false,
  truncated: false,
  outputBytes: measured(900),
  ...over,
});

describe('les avertissements de fenêtre', () => {
  it('ne dit rien quand la fenêtre est réellement couverte', () => {
    // Un avertissement affiché en permanence devient du décor, et on cesse de lire celui qui compte.
    expect(windowCaveat(window_())).toBeNull();
  });

  it("dit que l'anneau a évincé, avec la capacité et le compte", () => {
    const caveat = windowCaveat(window_({ droppedFromRing: 412, ringCapacity: 2000 }));
    expect(caveat).toContain('2000');
    expect(caveat).toContain('412');
  });

  it('nomme le moment où commence ce qui reste, quand il est connu', () => {
    // « ces chiffres portent sur ce qu'il reste » ne dit rien tant qu'on ne sait pas depuis quand.
    const caveat = windowCaveat(
      window_({ droppedFromRing: 5, oldestCallAt: '2026-09-07T14:02:31.000Z' }),
    );
    expect(caveat).toMatch(/commence à \d{2}:\d{2}/);
  });

  it('signale séparément que rien ne survit à un redémarrage', () => {
    // Deux faits distincts : « il reste de la place » et « rien n'est persisté » sont vrais
    // indépendamment l'un de l'autre.
    expect(historyNotice(window_({ auditPersisted: false }))).toContain("topic d'audit");
    expect(historyNotice(window_({ auditPersisted: true }))).toBeNull();
  });
});

describe('les valeurs mesurées', () => {
  it("rend « non mesuré » plutôt qu'un zéro", () => {
    expect(formatMeasured(unmeasured('cet outil ne compte pas de records'), 'ms')).toBe('non mesuré');
    expect(formatMeasured(measured(0), 'ms')).toBe('0 ms');
  });

  it("distingue « aucun refus » de « aucun appel »", () => {
    // 0 % pour zéro appel fait passer un serveur inutilisé pour un serveur sain.
    const stats = (calls: number, denied: number) => ({ calls, denied }) as McpStatsView;
    expect(deniedShare(stats(0, 0))).toBeNull();
    expect(deniedShare(stats(10, 2))).toBe(20);
  });
});

describe('la colonne couverture', () => {
  it("marque d'un avertissement une passe incomplète et dit ce que ça veut dire", () => {
    const label = coverageLabel(call({ partialCoverage: true, stopReason: 'TIME_BUDGET' }));
    expect(label.icon).toBe('⚠');
    expect(label.title).toContain('TIME_BUDGET');
    expect(label.title).toContain("n'existe pas");
  });

  it('marque une passe complète', () => {
    expect(coverageLabel(call()).icon).toBe('✓');
  });

  it("ne prétend rien pour un outil sans enveloppe", () => {
    expect(coverageLabel(call({ stopReason: null })).icon).toBe('—');
  });
});

describe('le verdict', () => {
  it('porte le code JSON-RPC, qui est ce qui désigne le réglage', () => {
    expect(outcomeLabel(call({ outcome: 'DENIED', jsonRpcErrorCode: -32041 }))).toBe('refusé -32041');
    expect(outcomeLabel(call({ truncated: true }))).toBe('OK (tronqué)');
    expect(outcomeLabel(call())).toBe('OK');
  });
});

describe("l'état de l'écran dans l'URL", () => {
  it("n'écrit que ce qui est posé, pour que le lien reste partageable", () => {
    const params = filtersToParams({ tool: 'kex_sql_query', outcome: '', identity: '', origin: '' },
      DEFAULT_WINDOW, 'catalog');
    expect(params.toString()).toBe('tool=kex_sql_query');
  });

  it('se restaure depuis les paramètres', () => {
    const filters = filtersFromParams(new URLSearchParams('tool=kex_trace_key&outcome=DENIED'));
    expect(filters).toEqual({ tool: 'kex_trace_key', outcome: 'DENIED', identity: '', origin: '' });
  });

  it('refuse une fenêtre inconnue plutôt que de la propager', () => {
    expect(isWindow('24h')).toBe(true);
    expect(isWindow('banana')).toBe(false);
    expect(isWindow(null)).toBe(false);
  });
});

describe("l'export CSV", () => {
  it("laisse vide une mesure absente plutôt que d'écrire 0", () => {
    // Un 0 dans un tableur est une affirmation que plus personne ne distinguera d'une mesure.
    const csv = callsToCsv([call({ recordsScanned: unmeasured('cet outil ne compte pas') })]);
    const row = csv.split('\n')[1].split(',');
    expect(row[9]).toBe('');
  });

  it('échappe ce qui casserait le fichier', () => {
    const csv = callsToCsv([call({ identity: 'a,b' })]);
    expect(csv).toContain('"a,b"');
  });
});

describe('le tri du catalogue', () => {
  it('groupe par catégorie puis par nom, écriture en dernier', () => {
    const tool = (name: string, category: McpToolRow['category']) => ({ name, category }) as McpToolRow;
    const sorted = sortTools([
      tool('kex_produce_message', 'WRITE'),
      tool('kex_sql_query', 'EXPLORATION'),
      tool('kex_describe_topic', 'EXPLORATION'),
    ]);
    expect(sorted.map((t) => t.name)).toEqual([
      'kex_describe_topic', 'kex_sql_query', 'kex_produce_message',
    ]);
  });
});

describe('les octets', () => {
  it('rend les plafonds dans leur unité', () => {
    expect(formatBytes(512)).toBe('512 o');
    expect(formatBytes(1_048_576)).toBe('1.0 Mio');
  });
});

describe('overrideBanner / describeOverride / overrideAge', () => {
  const override = (over: Partial<McpOverrideView> = {}): McpOverrideView => ({
    kind: 'TOOL', target: 'kex_sql_query', actor: 'alice', reason: 'une boucle folle',
    since: '2026-09-08T05:00:00Z', ageMs: 120_000, ...over,
  });

  it('ne rend rien quand aucune dérogation ne tient', () => {
    expect(overrideBanner([])).toBeNull();
  });

  it('nomme le levier, sa cible, son auteur, son âge et sa raison', () => {
    const [line] = overrideBanner([override()])!;
    expect(line).toContain('kex_sql_query');
    expect(line).toContain('alice');
    expect(line).toContain('depuis 2 min');
    expect(line).toContain('boucle folle');
  });

  it('dit le verrou global sans cible', () => {
    const [line] = overrideBanner([override({ kind: 'READONLY', target: null })])!;
    expect(line).toContain('lecture seule');
    expect(line).not.toContain('null');
  });

  it('dit la quarantaine par identité', () => {
    const [line] = overrideBanner([override({ kind: 'QUARANTINE', target: 'agent-7' })])!;
    expect(line).toContain('agent-7');
    expect(line).toContain('quarantaine');
  });

  it("remplace un auteur ou une raison absents plutôt que d'afficher un vide", () => {
    // Une colonne vide se lit comme un défaut de la page, pas comme une question à poser.
    const [line] = overrideBanner([override({ actor: null, reason: null })])!;
    expect(line).toContain('non nommé');
    expect(line).toContain('sans raison');
  });

  it('échelonne l’âge : une dérogation vieille d’un jour se lit d’un coup d’œil', () => {
    expect(overrideAge(30_000)).toBe("à l'instant");
    expect(overrideAge(45 * 60_000)).toBe('depuis 45 min');
    expect(overrideAge(5 * 3_600_000)).toBe('depuis 5 h');
    expect(overrideAge(50 * 3_600_000)).toBe('depuis 2 j');
  });
});

describe('replaySummary', () => {
  const replay = (over: Partial<McpReplay> = {}): McpReplay => ({
    calls: [{ tool: 'kex_list_topics' }], recordsScanned: 12,
    scanReachedWindowStart: true, topicExists: true, warnings: [], ...over,
  });

  it("distingue une piste vide d'une fenêtre vide", () => {
    // Deux conclusions opposées tirées de la même liste vide.
    const summary = replaySummary(replay({ calls: [], topicExists: false, recordsScanned: 0 }));
    expect(summary).toContain('piste vide');
    expect(summary).not.toContain('appel(s)');
  });

  it("dit que l'absence en est une quand le balayage a atteint le début de la fenêtre", () => {
    expect(replaySummary(replay())).toContain('une absence en est bien une');
  });

  it("dit que l'absence ne prouve rien quand la rétention a mordu dans la fenêtre", () => {
    // Sans cette phrase, l'écran laisse choisir la lecture rassurante.
    const summary = replaySummary(replay({ scanReachedWindowStart: false }));
    expect(summary).toContain("n'a PAS atteint");
    expect(summary).toContain('ne prouve rien');
  });

  it('compte ce qui a été trouvé et ce qui a été lu, qui ne sont pas le même nombre', () => {
    const summary = replaySummary(replay({ calls: [{ a: 1 }, { b: 2 }], recordsScanned: 40 }));
    expect(summary).toContain('2 appel(s)');
    expect(summary).toContain('40 enregistrement(s)');
  });
});

describe('explainDenial', () => {
  it('traduit un code KIP-1318 et dit où se règle ce qui l\'a produit', () => {
    // « -32041 · garde SCOPE » ne répond à la question de la carte que pour qui connaît la table.
    const why = explainDenial(-32041, 'SCOPE')!;
    expect(why.sentence).toContain('portée');
    expect(why.setting).toContain('allowed-topic-prefixes');
  });

  it('distingue les deux causes qui partagent -32044, qui se règlent à deux endroits', () => {
    expect(explainDenial(-32044, 'DENY_LIST')!.setting).toContain('commutateur');
    expect(explainDenial(-32044, 'POLICY')!.setting).toContain('allow-runtime-toggle');
  });

  it('dit qu’une dépendance absente n’est pas une garde à desserrer', () => {
    const why = explainDenial(-32043, null)!;
    expect(why.sentence).toContain("n'est pas une garde");
    expect(why.setting).toBeNull();
  });

  it('rend null sur un code inconnu plutôt qu’une phrase inventée', () => {
    // Un code que cet écran ignore vient d'un serveur plus récent que lui.
    expect(explainDenial(-32099, null)).toBeNull();
    expect(explainDenial(null, null)).toBeNull();
  });
});

describe('filterTools / firstSentence', () => {
  const tool = (name: string, description: string, category = 'EXPLORATION'): McpToolRow => ({
    name, category, description,
    visibility: { state: 'EXPOSED', reason: null },
    defaultBudgetMs: null, hardMaxRecords: null, hardMaxRows: null, hardMaxBytes: 1,
    calls: 0, denied: 0, p95Ms: { value: null, measured: false, reason: 'x' },
  } as McpToolRow);

  it('filtre sur le nom, la catégorie et la description', () => {
    const tools = [tool('kex_trace_key', 'Suit une clé.'), tool('kex_run_audit', 'Lance un audit.')];
    expect(filterTools(tools, 'trace')).toHaveLength(1);
    expect(filterTools(tools, 'AUDIT')).toHaveLength(1);
    expect(filterTools(tools, 'exploration')).toHaveLength(2);
  });

  it('ne filtre rien sur une requête vide', () => {
    const tools = [tool('a', 'x'), tool('b', 'y')];
    expect(filterTools(tools, '   ')).toHaveLength(2);
  });

  it('coupe sur la phrase, pas sur un nombre de caractères', () => {
    // Une troncature au milieu d'un mot dit « il y a plus » sans rien apprendre.
    expect(firstSentence('Suit une clé. Puis autre chose.')).toBe('Suit une clé.');
  });

  it('aplatit les retours à la ligne pour la ligne repliée', () => {
    expect(firstSentence('Suit une clé\n  à travers le cluster. Reste.'))
      .toBe('Suit une clé à travers le cluster.');
  });

  it('ne propose de déplier que lorsqu’il y a réellement plus à lire', () => {
    expect(hasMoreThanFirstSentence('Une seule phrase.')).toBe(false);
    expect(hasMoreThanFirstSentence('Une phrase. Et une autre.')).toBe(true);
  });
});
