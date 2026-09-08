// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, expect, it } from 'vitest';
import type { McpCallView, McpStatsView, McpToolRow, Measured, ObservedWindow, McpOverrideView } from '../../api/types';
import {
  DEFAULT_WINDOW, callsToCsv, coverageLabel, deniedShare, filtersFromParams, filtersToParams,
  formatBytes, formatMeasured, historyNotice, isWindow, outcomeLabel, overrideAge,
  overrideBanner, sortTools, windowCaveat,
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
