// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect, beforeEach } from 'vitest';
import type { DataModelEntity, DataModelRelation, DataModelResponse } from '../api/types';
import {
  DEFAULT_MAX_TOPICS, MAX_COLUMNS_SHOWN, NODE_W, HEADER_H, ROW_H, FOOTER_H, DOMAIN_PALETTE,
  filterTopics, toggleTopic, selectAll, topicsFromQuery, buildQuery,
  displayedColumns, entityHeight, computeLayout, describeModel, splitByConnectivity,
  columnRowY, anchorSpread, computeEdgeGeometry, crowFootPath, oneBarPath,
  graphBounds, fitTransform, centerOnEntity, topicDomains, domainColors,
  formatCount, describeRelation, matchingColumns, describeColumnMatches,
  buildExportSvg, exportNotes, escapeXml, toMermaidEr, buildJoinSql, joinAliases,
  capTopics, relationKey, diffModels, diffIsEmpty, describeDiff,
  filterRelations, describeRelationFilter, orphanKeyColumns, describeOrphanKey,
  shortenColumnName, readSelectionDraft, saveSelectionDraft, clampMaxTopics,
  maxTopicsFromQuery, requestTimeoutMs, describeBuildBudget, MIN_REQUEST_TIMEOUT_MS,
  isSignificantResize, RESIZE_EPSILON_PX,
  NODE_SIZINGS, MIN_NODE_W, MAX_NODE_W, MAX_FIT_SCALE, MIN_FIT_SCALE, fitScale,
  COMFORTABLE_NODE_SIZING, DEFAULT_NODE_SIZING, COMPACT_NODE_SIZING, chooseNodeSizing,
  orphanColumns, maxNodesPerColumn, drawnEntities, describeSetAside,
  readPanelOpen, writePanelOpen, PANEL_KEY,
  minimapLayout, visibleGraphRect, graphFullyVisible, centerOnGraphPoint,
  readSavedModels, saveModel, deleteSavedModel, clearSavedModels, MAX_SAVED_MODELS,
  SAVED_MODELS_KEY, joinAliasesFor, buildMultiJoinSql,
  describeBuildProgress, describeStaleGraphDuringBuild,
} from './dataModel';
import type { RelationConfidence } from '../api/types';

// Ce module écrit dans localStorage (brouillon de sélection, sélections nommées) : sans remise
// à zéro, un test lit ce que le précédent a laissé, et l'ordre d'exécution devient une
// dépendance cachée.
beforeEach(() => localStorage.clear());

function entity(id: string, columnCount: number, overrides: Partial<DataModelEntity> = {}): DataModelEntity {
  return {
    id,
    topic: id.replace(/_/g, '.'),
    format: 'JSON',
    columns: Array.from({ length: columnCount }, (_, i) => ({
      name: `col_${i}`, type: 'STRING', primaryKey: false, references: null, keyBase: null
    })),
    primaryKey: null,
    messageCount: null,
    ...overrides,
  };
}

function relation(from: string, to: string, fromColumn = 'x_id'): DataModelRelation {
  return { from, to, fromColumn, toColumn: null, confidence: 'HIGH', reason: 'test' };
}

describe('topic selection', () => {
  it('filters case-insensitively', () => {
    expect(filterTopics(['demo.Orders', 'demo.payments'], 'ORD')).toEqual(['demo.Orders']);
    expect(filterTopics(['a', 'b'], '  ')).toEqual(['a', 'b']);
  });

  it('toggles in and out', () => {
    expect(toggleTopic([], 't')).toEqual(['t']);
    expect(toggleTopic(['t'], 't')).toEqual([]);
  });

  /*
   * Le champ de saisie annonce `demo.orders.*` dans son placeholder ; le filtre juste en dessous
   * ne comprenait pas cette syntaxe et rendait une liste vide, ce qui se lit comme « aucun topic »
   * plutôt que « pas la bonne syntaxe ». C'est le défaut signalé.
   */
  it('the filter understands a pattern, like the field above it advertises', () => {
    const catalog = ['demo.orders.1.received', 'demo.orders.2.validated', 'demo.payments.authorized'];
    expect(filterTopics(catalog, 'demo.orders.*'))
      .toEqual(['demo.orders.1.received', 'demo.orders.2.validated']);
  });

  it('a pattern filter is anchored, so it shows exactly what the field would add', () => {
    const catalog = ['demo.orders.1.received', 'other.demo.orders.x'];
    expect(filterTopics(catalog, 'demo.orders.*')).toEqual(['demo.orders.1.received']);
  });

  it('plain text stays a substring match — that is what a filter is', () => {
    expect(filterTopics(['demo.Orders', 'demo.payments'], 'ORD')).toEqual(['demo.Orders']);
  });

  it('a pattern is case-insensitive too, like the substring path has always been', () => {
    expect(filterTopics(['demo.Orders.1'], 'demo.orders.*')).toEqual(['demo.Orders.1']);
  });

  it('a pattern matching nothing shows nothing, without throwing on the regex characters', () => {
    expect(filterTopics(['demo.orders.1'], 'nope.*')).toEqual([]);
    expect(filterTopics(['a+b(c)'], 'a+b(c)')).toEqual(['a+b(c)']);
  });

  it('select-all respects the server cap and skips internal topics', () => {
    const visible = ['internal.audit.history', ...Array.from({ length: 40 }, (_, i) => `t${i}`)];
    const selected = selectAll([], visible, DEFAULT_MAX_TOPICS);
    expect(selected).toHaveLength(DEFAULT_MAX_TOPICS);
    expect(selected).not.toContain('internal.audit.history');
  });

  it('select-all keeps what was already selected', () => {
    expect(selectAll(['z'], ['a'], DEFAULT_MAX_TOPICS)).toEqual(['z', 'a']);
  });
});

describe('URL round-trip', () => {
  it('round-trips a selection', () => {
    const query = buildQuery(['demo.orders', 'demo.payments']);
    expect(topicsFromQuery(query)).toEqual(['demo.orders', 'demo.payments']);
  });

  it('deduplicates and drops blanks on read', () => {
    expect(topicsFromQuery('?topics=a,,a,%20,b')).toEqual(['a', 'b']);
  });

  it('an empty selection produces no query string', () => {
    expect(buildQuery([])).toBe('');
    expect(topicsFromQuery('')).toEqual([]);
  });
});

describe('displayedColumns / entityHeight', () => {
  it('lists everything under the cap', () => {
    const { columns, hidden } = displayedColumns(entity('t', 5));
    expect(columns).toHaveLength(5);
    expect(hidden).toBe(0);
  });

  it('caps and counts the rest — a node is not a page', () => {
    const { columns, hidden } = displayedColumns(entity('t', MAX_COLUMNS_SHOWN + 7));
    expect(columns).toHaveLength(MAX_COLUMNS_SHOWN);
    expect(hidden).toBe(7);
  });

  it('key columns are shown first — they carry the edges', () => {
    const e = entity('t', MAX_COLUMNS_SHOWN + 2);
    e.columns[MAX_COLUMNS_SHOWN + 1] = {
      name: 'order_id', type: 'STRING', primaryKey: true, references: null, keyBase: null
    };
    const { columns } = displayedColumns(e);
    expect(columns[0].name).toBe('order_id');
  });

  it('a key-like column with no relation is ranked with the keys, not lost to the overflow', () => {
    // C'est celle que le diagramme marque d'un `?` : sur une charge utile imbriquée elle
    // partait dans le `+N more` exactement quand la carte est chargée, donc quand elle sert.
    const e = entity('t', MAX_COLUMNS_SHOWN + 2);
    e.columns[MAX_COLUMNS_SHOWN + 1] = {
      name: 'customer_id', type: 'STRING', primaryKey: false, references: null, keyBase: 'customer',
    };
    const { columns } = displayedColumns(e);
    expect(columns[0].name).toBe('customer_id');
  });

  it('height follows the displayed rows, plus one for the overflow line', () => {
    expect(entityHeight(entity('t', 3))).toBe(HEADER_H + 3 * ROW_H + FOOTER_H);
    expect(entityHeight(entity('t', MAX_COLUMNS_SHOWN + 7)))
      .toBe(HEADER_H + (MAX_COLUMNS_SHOWN + 1) * ROW_H + FOOTER_H);
  });
});

describe('computeLayout', () => {
  it('an empty model has no positions', () => {
    expect(computeLayout([], [])).toEqual({});
  });

  it('a referenced entity lands in a later column than its referencer', () => {
    const a = entity('facts', 2);
    const b = entity('dims', 2);
    const positions = computeLayout([a, b], [relation('facts', 'dims')]);
    expect(positions.dims.x).toBeGreaterThan(positions.facts.x);
  });

  it('entities without relations are gridded below the graph, not stacked in one column', () => {
    const connected = [entity('a', 2), entity('b', 2)];
    const isolatedEntities = Array.from({ length: 6 }, (_, i) => entity(`iso${i}`, 2));
    const positions = computeLayout([...connected, ...isolatedEntities], [relation('a', 'b')]);
    const graphBottom = Math.max(positions.a.y, positions.b.y);
    expect(positions.iso0.y).toBeGreaterThan(graphBottom);
    // Grid wraps: not all isolated nodes share one x.
    const xs = new Set(isolatedEntities.map(e => positions[e.id].x));
    expect(xs.size).toBeGreaterThan(1);
  });

  it('stacked nodes never overlap whatever their heights', () => {
    const tall = entity('tall', 20);
    const small = entity('small', 2);
    const sink = entity('sink', 2);
    const positions = computeLayout(
      [tall, small, sink],
      [relation('tall', 'sink'), relation('small', 'sink')]);
    // tall and small share the first column; small starts below tall's real height.
    const first = Math.min(positions.tall.y, positions.small.y);
    const second = Math.max(positions.tall.y, positions.small.y);
    const firstEntity = positions.tall.y === first ? tall : small;
    expect(second).toBeGreaterThanOrEqual(first + entityHeight(firstEntity));
  });

  it('a relation citing an unknown entity is ignored rather than crashing the layout', () => {
    const positions = computeLayout([entity('a', 1)], [relation('a', 'ghost')]);
    expect(positions.a).toBeDefined();
    expect(positions.ghost).toBeUndefined();
  });

  it('a cycle still places every node', () => {
    const positions = computeLayout(
      [entity('a', 1), entity('b', 1)],
      [relation('a', 'b'), relation('b', 'a')]);
    expect(positions.a).toBeDefined();
    expect(positions.b).toBeDefined();
  });

  /*
   * Un hub que trente topics référencent est la forme la plus banale d'un modèle de données, et
   * elle produisait une colonne de trente boîtes : 424 × 5 099 px, une emprise qu'aucun écran ne
   * peut cadrer autrement qu'en tombant à l'échelle 0,14 avec les deux tiers de la largeur vides.
   */
  it('a crowded layer wraps into adjacent sub-columns instead of one very tall column', () => {
    const hub = entity('hub', 3);
    const spokes = Array.from({ length: 24 }, (_, i) => entity(`spoke${i}`, 3));
    const relations = spokes.map(s => relation(s.id, 'hub'));
    const positions = computeLayout([hub, ...spokes], relations);

    const spokeXs = new Set(spokes.map(s => positions[s.id].x));
    expect(spokeXs.size).toBeGreaterThan(1);
    // Et la couche reste une couche : le hub est toujours à droite de tous ses référents.
    expect(positions.hub.x).toBeGreaterThan(Math.max(...spokeXs));

    // Aucune sous-colonne ne dépasse la borne annoncée.
    const perColumn = maxNodesPerColumn(25);
    const counts = new Map<number, number>();
    spokes.forEach(s => counts.set(positions[s.id].x, (counts.get(positions[s.id].x) ?? 0) + 1));
    expect(Math.max(...counts.values())).toBeLessThanOrEqual(perColumn);
  });

  it('the wrapped layout is wide enough to be fitted, where the single column never was', () => {
    const hub = entity('hub', 6);
    const spokes = Array.from({ length: 24 }, (_, i) => entity(`spoke${i}`, 6));
    const relations = spokes.map(s => relation(s.id, 'hub'));
    const entities = [hub, ...spokes];
    const bounds = graphBounds(entities, computeLayout(entities, relations))!;
    // Ce qui compte n'est pas un chiffre précis mais la forme : plus large que haute, donc
    // cadrable sur un écran qui l'est aussi.
    expect(bounds.maxX - bounds.minX).toBeGreaterThan(bounds.maxY - bounds.minY);
  });

  it('a layer small enough to stack is left stacked', () => {
    // Deux nœuds dans la première couche ne se replient pas : le repli est un remède, pas une règle.
    const positions = computeLayout(
      [entity('a', 2), entity('b', 2), entity('sink', 2)],
      [relation('a', 'sink'), relation('b', 'sink')]);
    expect(positions.a.x).toBe(positions.b.x);
  });

  it('the isolated grid widens with its population rather than staying four wide', () => {
    expect(orphanColumns(0)).toBe(1);
    expect(orphanColumns(4)).toBe(4);
    expect(orphanColumns(40)).toBe(8);
    // Bornée : un modèle qui n'a que des isolées ne part pas à l'horizontale.
    expect(orphanColumns(400)).toBe(8);
  });
});

/*
 * Le calibre des boîtes. Il était constant, donc le même à trois entités qu'à cent — et c'est lui
 * qui décidait en silence si le diagramme pouvait tenir à l'écran de façon lisible.
 */
describe('chooseNodeSizing', () => {
  const viewport = { width: 1280, height: 800 };

  function star(spokes: number, columns: number) {
    const entities = [entity('hub', columns),
      ...Array.from({ length: spokes }, (_, i) => entity(`spoke${i}`, columns))];
    const relations = entities.slice(1).map(e => relation(e.id, 'hub'));
    return { entities, relations };
  }

  it('every tier stays between the stated bounds', () => {
    for (const sizing of NODE_SIZINGS) {
      expect(sizing.width).toBeGreaterThanOrEqual(MIN_NODE_W);
      expect(sizing.width).toBeLessThanOrEqual(MAX_NODE_W);
    }
  });

  it('a small model gets the widest boxes — there is room, so it is used', () => {
    const { entities, relations } = star(1, 4);
    expect(chooseNodeSizing(entities, relations, viewport)).toBe(COMFORTABLE_NODE_SIZING);
  });

  it('a large model gets compact boxes, which is what keeps its text readable', () => {
    const { entities, relations } = star(29, 15);
    const sizing = chooseNodeSizing(entities, relations, viewport);
    expect(sizing).toBe(COMPACT_NODE_SIZING);

    // Ce n'est pas un dogme : le calibre serré rend le texte *plus grand* à l'écran, parce que
    // l'emprise plus faible relève l'échelle de cadrage. C'est tout l'arbitrage.
    const renderedWith = (s: typeof sizing) => s.rowSize * fitScale(
      graphBounds(entities, computeLayout(entities, relations, s), s)!,
      viewport.width, viewport.height, 40, 40, MAX_FIT_SCALE, 0);
    expect(renderedWith(COMPACT_NODE_SIZING))
      .toBeGreaterThan(renderedWith(COMFORTABLE_NODE_SIZING));
  });

  /*
   * Régression : la comparaison passait par `fitTransform`, dont le plancher de 0,1 écrase la
   * différence entre deux calibres qui le touchent tous les deux. Le départage se faisait alors
   * sur le seul corps du texte, et c'est la *plus grosse* boîte qui gagnait — exactement sur le
   * modèle qui la supporte le moins.
   */
  it('a model too large for any tier still picks the one that shows the most', () => {
    const { entities, relations } = star(80, 25);
    expect(chooseNodeSizing(entities, relations, viewport)).toBe(COMPACT_NODE_SIZING);
  });

  it('nothing measurable yields the default rather than an absurd choice', () => {
    const { entities, relations } = star(3, 4);
    expect(chooseNodeSizing(entities, relations, { width: 0, height: 0 }))
      .toBe(DEFAULT_NODE_SIZING);
    expect(chooseNodeSizing([], [], viewport)).toBe(DEFAULT_NODE_SIZING);
  });

  it('the chosen tier drives the layout it was chosen for', () => {
    const { entities, relations } = star(29, 15);
    const sizing = chooseNodeSizing(entities, relations, viewport);
    const positions = computeLayout(entities, relations, sizing);
    const bounds = graphBounds(entities, positions, sizing)!;
    // Les boîtes larges donneraient une emprise strictement plus grande.
    const wide = graphBounds(entities,
      computeLayout(entities, relations, COMFORTABLE_NODE_SIZING), COMFORTABLE_NODE_SIZING)!;
    expect(bounds.maxX - bounds.minX).toBeLessThan(wide.maxX - wide.minX);
  });
});

describe('columnRowY', () => {
  it('gives the centre of the displayed row', () => {
    const e = entity('t', 3);
    expect(columnRowY(e, 'col_1')).toBe(HEADER_H + 1 * ROW_H + ROW_H / 2);
  });

  it('is null for a column folded into the overflow, and for no column at all', () => {
    const e = entity('t', MAX_COLUMNS_SHOWN + 3);
    expect(columnRowY(e, `col_${MAX_COLUMNS_SHOWN + 2}`)).toBeNull();
    expect(columnRowY(e, null)).toBeNull();
    expect(columnRowY(e, 'nope')).toBeNull();
  });
});

describe('computeEdgeGeometry', () => {
  const positions = { facts: { x: 0, y: 0 }, dims: { x: 400, y: 50 } };

  function pair(): DataModelEntity[] {
    const facts = entity('facts', 4);
    facts.columns[2] = { name: 'dim_id', type: 'STRING', primaryKey: false, references: 'dims', keyBase: null };
    const dims = entity('dims', 3, { primaryKey: 'dim_id' });
    dims.columns[1] = { name: 'dim_id', type: 'STRING', primaryKey: true, references: null, keyBase: null };
    return [facts, dims];
  }

  it('anchors each end on its column row, not the node centre', () => {
    const [geometry] = computeEdgeGeometry(
      [{ ...relation('facts', 'dims', 'dim_id'), toColumn: 'dim_id' }], pair(), positions);
    // Key columns are displayed first, so dim_id is row 0 in both nodes.
    expect(geometry!.y1).toBe(0 + HEADER_H + ROW_H / 2);
    expect(geometry!.y2).toBe(50 + HEADER_H + ROW_H / 2);
    expect(geometry!.x1).toBe(NODE_W);
    expect(geometry!.x2).toBe(400);
    expect(geometry!.d1).toBe(1);
    expect(geometry!.d2).toBe(-1);
  });

  it('a null toColumn falls back to the target key row, then the centre', () => {
    const [withPk] = computeEdgeGeometry(
      [relation('facts', 'dims', 'dim_id')], pair(), positions);
    expect(withPk!.y2).toBe(50 + HEADER_H + ROW_H / 2);

    const noKey = [entity('facts', 1), entity('dims', 2)];
    const [centred] = computeEdgeGeometry(
      [relation('facts', 'dims', 'col_0')], noKey, positions);
    expect(centred!.y2).toBe(50 + entityHeight(noKey[1]) / 2);
  });

  it('flips sides when the target is to the left', () => {
    const [geometry] = computeEdgeGeometry(
      [relation('dims', 'facts', 'col_0')], pair(), positions);
    expect(geometry!.x1).toBe(400);
    expect(geometry!.d1).toBe(-1);
    expect(geometry!.x2).toBe(NODE_W);
    expect(geometry!.d2).toBe(1);
  });

  it('spreads edges sharing the same anchor instead of stacking them', () => {
    const entities = [entity('a', 2), entity('b', 2), entity('sink', 2, { primaryKey: 'col_0' })];
    const threePos = { a: { x: 0, y: 0 }, b: { x: 0, y: 300 }, sink: { x: 400, y: 100 } };
    const [first, second] = computeEdgeGeometry(
      [relation('a', 'sink', 'col_0'), relation('b', 'sink', 'col_0')], entities, threePos);
    expect(first!.y2).not.toBe(second!.y2);
    // Centred around the shared row: the mean is the unspread anchor.
    const row = 100 + HEADER_H + ROW_H / 2;
    expect((first!.y2 + second!.y2) / 2).toBe(row);
  });

  it('a relation citing an unknown entity yields null, not a crash', () => {
    const [geometry] = computeEdgeGeometry(
      [relation('facts', 'ghost', 'x_id')], pair(), positions);
    expect(geometry).toBeNull();
  });
});

describe('anchorSpread', () => {
  it('is zero for a lone edge and symmetric for several', () => {
    expect(anchorSpread(0, 1)).toBe(0);
    expect(anchorSpread(0, 2)).toBe(-5);
    expect(anchorSpread(1, 2)).toBe(5);
    expect(anchorSpread(1, 3)).toBe(0);
  });
});

describe('cardinality glyphs', () => {
  it('the crow foot fans from 10px out back to the node edge', () => {
    expect(crowFootPath(100, 50, 1)).toBe('M110,50 L100,45 M110,50 L100,50 M110,50 L100,55');
    expect(crowFootPath(100, 50, -1)).toContain('M90,50');
  });

  it('the one-bar sits 7px out, perpendicular', () => {
    expect(oneBarPath(100, 50, -1)).toBe('M93,45 L93,55');
  });
});

describe('graphBounds / fitTransform', () => {
  it('bounds cover positions plus real node sizes', () => {
    const a = entity('a', 2);
    const b = entity('b', 8);
    const bounds = graphBounds([a, b], { a: { x: 0, y: 0 }, b: { x: 300, y: 100 } });
    expect(bounds).toEqual({
      minX: 0, minY: 0, maxX: 300 + NODE_W, maxY: 100 + entityHeight(b),
    });
  });

  it('bounds are null with nothing placed', () => {
    expect(graphBounds([entity('a', 1)], {})).toBeNull();
  });

  it('fit centres the graph and never scales above 1', () => {
    const t = fitTransform({ minX: 0, minY: 0, maxX: 100, maxY: 100 }, 1000, 800);
    expect(t.scale).toBe(1);
    expect(t.x).toBe((1000 - 100) / 2);
    expect(t.y).toBe((800 - 100) / 2);
  });

  it('fit shrinks a graph larger than the viewport', () => {
    const t = fitTransform({ minX: 0, minY: 0, maxX: 4000, maxY: 100 }, 1000, 800, 40);
    expect(t.scale).toBeCloseTo((1000 - 80) / 4000);
  });

  it('an unset topPadding reproduces the old symmetric centring exactly', () => {
    // Regression pin: existing callers that never pass topPadding must see no change at all.
    const bounds = { minX: 10, minY: 20, maxX: 4010, maxY: 620 };
    const withDefault = fitTransform(bounds, 1200, 900, 40);
    const explicitlySymmetric = fitTransform(bounds, 1200, 900, 40, 40);
    expect(withDefault).toEqual(explicitlySymmetric);
  });

  it('a larger topPadding pushes a height-constrained graph down and clear of the reserved band', () => {
    // The banner overlay covers the top of the viewport without shrinking the SVG: a graph
    // whose height is the binding constraint used to centre almost flush with the top, right
    // under it. Because scale is also height-bound here, growing topPadding shrinks scale too —
    // so this checks what must hold regardless: the content clears the reserved band, and
    // shrinks rather than overflowing it.
    const bounds = { minX: 0, minY: 0, maxX: 300, maxY: 2000 };
    const symmetric = fitTransform(bounds, 1200, 900, 40);
    const reserved = fitTransform(bounds, 1200, 900, 40, 160);
    expect(reserved.y).toBeGreaterThan(symmetric.y);
    expect(reserved.scale).toBeLessThan(symmetric.scale);
    // The fitted content's top edge must clear the reserved band, not just move down a little.
    expect(reserved.y + bounds.minY * reserved.scale).toBeGreaterThanOrEqual(160);
  });

  it('topPadding has no effect when width, not height, is the binding constraint', () => {
    // A wide, short graph is scaled down by its width alone; reserving vertical room changes
    // nothing about that — the padding sides (left/right) it actually depends on are untouched.
    const bounds = { minX: 0, minY: 0, maxX: 4000, maxY: 50 };
    const symmetric = fitTransform(bounds, 1200, 900, 40);
    const reserved = fitTransform(bounds, 1200, 900, 40, 160);
    expect(reserved.scale).toBe(symmetric.scale);
    expect(reserved.x).toBe(symmetric.x);
  });

  it('a topPadding that leaves no room still returns a finite, positive scale', () => {
    const bounds = { minX: 0, minY: 0, maxX: 100, maxY: 100 };
    const t = fitTransform(bounds, 1200, 900, 40, 2000);
    expect(Number.isFinite(t.scale)).toBe(true);
    expect(t.scale).toBeGreaterThan(0);
  });

  /*
   * Le plafond de 1 laissait un modèle de trois tables occuper le quart du canevas : « le graphe
   * n'utilise pas la place disponible » est exactement ce reproche-là.
   */
  it('a maxScale above 1 lets a small graph fill the canvas, and still centres it', () => {
    const bounds = { minX: 0, minY: 0, maxX: 200, maxY: 200 };
    const t = fitTransform(bounds, 1200, 900, 40, 40, MAX_FIT_SCALE);
    expect(t.scale).toBe(MAX_FIT_SCALE);
    expect(t.x).toBeCloseTo((1200 - 200 * MAX_FIT_SCALE) / 2);
  });

  it('the default maxScale is still 1, so a caller that does not ask sees no change', () => {
    const bounds = { minX: 0, minY: 0, maxX: 200, maxY: 200 };
    expect(fitTransform(bounds, 1200, 900).scale).toBe(1);
  });

  it('fitScale is what fitTransform uses, and its floor is optional', () => {
    const huge = { minX: 0, minY: 0, maxX: 100_000, maxY: 100 };
    expect(fitTransform(huge, 1200, 900).scale).toBe(MIN_FIT_SCALE);
    expect(fitScale(huge, 1200, 900, 40, 40, 1, 0)).toBeLessThan(MIN_FIT_SCALE);
  });
});

describe('drawn vs set aside', () => {
  it('an entity no relation touches is listed, not drawn', () => {
    const entities = [entity('a', 2), entity('b', 2), entity('lonely', 2)];
    const { drawn, setAside } = drawnEntities(entities, [relation('a', 'b')], false);
    expect(drawn.map(e => e.id)).toEqual(['a', 'b']);
    expect(setAside.map(e => e.id)).toEqual(['lonely']);
  });

  it('the opt-in draws them, and then nothing is set aside', () => {
    const entities = [entity('a', 2), entity('b', 2), entity('lonely', 2)];
    const { drawn, setAside } = drawnEntities(entities, [relation('a', 'b')], true);
    expect(drawn).toHaveLength(3);
    expect(setAside).toEqual([]);
  });

  /*
   * L'exception, et la seule : tout mettre de côté quand rien n'est relié laisserait un canevas
   * vide après une génération réussie — une page qui ne montre rien alors qu'elle a un modèle.
   */
  it('with no relation at all the grid is the diagram, rather than an empty canvas', () => {
    const entities = [entity('a', 2), entity('b', 2)];
    const { drawn, setAside } = drawnEntities(entities, [], false);
    expect(drawn).toHaveLength(2);
    expect(setAside).toEqual([]);
  });

  it('the list says why those entities have no edge, not just how many', () => {
    expect(describeSetAside(4, false)).toContain('no deduced relation');
    expect(describeSetAside(1, false)).toContain('1 entity');
    expect(describeSetAside(3, true)).toContain('drawn in the grid');
  });
});

describe('panel collapse', () => {
  it('round-trips, and an unset or corrupt value falls back to the caller default', () => {
    expect(readPanelOpen(true)).toBe(true);
    expect(readPanelOpen(false)).toBe(false);
    writePanelOpen(false);
    expect(readPanelOpen(true)).toBe(false);
    writePanelOpen(true);
    expect(readPanelOpen(false)).toBe(true);
    localStorage.setItem(PANEL_KEY, 'yes');
    expect(readPanelOpen(false)).toBe(false);
  });
});

describe('centerOnEntity', () => {
  const e = entity('orders', 3);

  it('centers the entity box in the viewport', () => {
    const t = centerOnEntity(e, { x: 100, y: 200 }, 1200, 900, 1, 0.8);
    const cx = 100 + NODE_W / 2;
    const cy = 200 + entityHeight(e) / 2;
    expect(t.x).toBeCloseTo(600 - cx * t.scale);
    expect(t.y).toBeCloseTo(450 - cy * t.scale);
  });

  it('raises the scale up to minScale when zoomed further out than that', () => {
    const t = centerOnEntity(e, { x: 0, y: 0 }, 1200, 900, 0.3, 0.8);
    expect(t.scale).toBe(0.8);
  });

  it('keeps the current scale when already at least as close', () => {
    const t = centerOnEntity(e, { x: 0, y: 0 }, 1200, 900, 0.9, 0.8);
    expect(t.scale).toBe(0.9);
  });

  it('never exceeds scale 1 even below minScale\'s floor', () => {
    const t = centerOnEntity(e, { x: 0, y: 0 }, 1200, 900, 0.1, 1.5);
    expect(t.scale).toBe(1);
  });

  /*
   * Le plafond de 1 bornait le résultat, donc sauter sur une entité depuis un cadrage plus proche
   * que 1 — ce qu'un petit modèle donne maintenant — *dézoomait*. Sauter sur une table est un
   * geste pour s'en approcher.
   */
  it('keeps a closer-than-1 framing instead of zooming back out to it', () => {
    expect(centerOnEntity(e, { x: 0, y: 0 }, 1200, 900, MAX_FIT_SCALE, 0.8).scale)
      .toBe(MAX_FIT_SCALE);
  });
});

describe('clampMaxTopics', () => {
  const limits = { maxTopics: 100, defaultMaxTopics: 30, perTopicTimeoutMs: 20_000, inferenceThreads: 4 };

  it('lets a run ask for more than the default, up to the server ceiling', () => {
    expect(clampMaxTopics(60, limits)).toBe(60);
    expect(clampMaxTopics(100, limits)).toBe(100);
  });

  it('never lets the field promise more than the server allows', () => {
    expect(clampMaxTopics(500, limits)).toBe(100);
  });

  // Le plafond inconnu est le défaut, pas l'infini : une génération refusée après plusieurs
  // minutes d'inférence est un pire résultat qu'un champ momentanément trop prudent.
  it('assumes the default ceiling while the limits are unknown', () => {
    expect(clampMaxTopics(80, null)).toBe(30);
    expect(clampMaxTopics(10, null)).toBe(10);
  });

  it('refuses zero, negatives and a field left illisible', () => {
    expect(clampMaxTopics(0, limits)).toBe(1);
    expect(clampMaxTopics(-5, limits)).toBe(1);
    expect(clampMaxTopics(Number.NaN, limits)).toBe(30);
  });

  it('a ceiling below the default wins over it — the server is the authority', () => {
    expect(clampMaxTopics(30, { maxTopics: 5, defaultMaxTopics: 30, perTopicTimeoutMs: 20_000, inferenceThreads: 4 })).toBe(5);
  });
});

describe('the budget travels in the URL', () => {
  it('a link replays the budget its run was built under', () => {
    const url = buildQuery(['a', 'b'], 60);
    expect(maxTopicsFromQuery(url)).toBe(60);
    expect(topicsFromQuery(url)).toEqual(['a', 'b']);
  });

  // Un lien n'a pas à porter un paramètre qui ne dit rien, et les URLs déjà partagées ne bougent pas.
  it('the default is left out of the URL entirely', () => {
    expect(buildQuery(['a'], DEFAULT_MAX_TOPICS)).toBe('?topics=a');
    expect(buildQuery(['a'])).toBe('?topics=a');
  });

  it('an absent or unusable value reads as no budget, never an invented one', () => {
    expect(maxTopicsFromQuery('?topics=a')).toBeNull();
    expect(maxTopicsFromQuery('?topics=a&max=nope')).toBeNull();
    expect(maxTopicsFromQuery('?topics=a&max=0')).toBeNull();
    expect(maxTopicsFromQuery('?topics=a&max=-4')).toBeNull();
  });
});

describe('requestTimeoutMs', () => {
  const limits = { maxTopics: 100, defaultMaxTopics: 30, perTopicTimeoutMs: 20_000, inferenceThreads: 4 };

  // Le défaut fixe de deux minutes reste le plancher : une petite génération se comporte
  // exactement comme avant.
  it('keeps the floor for a run the server can answer well within it', () => {
    expect(requestTimeoutMs(4, limits)).toBe(MIN_REQUEST_TIMEOUT_MS);
    expect(requestTimeoutMs(1, limits)).toBe(MIN_REQUEST_TIMEOUT_MS);
  });

  // 100 topics sur 4 fils, 20 s chacun : 25 vagues, soit 500 s — largement au-delà des deux
  // minutes que la page attendait, ce qui faisait abandonner axios pendant que le serveur
  // travaillait encore.
  it('waits for the worst case the server describes on a large run', () => {
    expect(requestTimeoutMs(100, limits)).toBe(25 * 20_000 + 15_000);
    expect(requestTimeoutMs(100, limits)).toBeGreaterThan(MIN_REQUEST_TIMEOUT_MS);
  });

  it('falls back to the floor while the limits are unknown or unusable', () => {
    expect(requestTimeoutMs(100, null)).toBe(MIN_REQUEST_TIMEOUT_MS);
    expect(requestTimeoutMs(100, { ...limits, inferenceThreads: 0 })).toBe(MIN_REQUEST_TIMEOUT_MS);
    expect(requestTimeoutMs(100, { ...limits, perTopicTimeoutMs: 0 })).toBe(MIN_REQUEST_TIMEOUT_MS);
  });

  it('states the bound as a bound, and only when it exceeds the floor', () => {
    expect(describeBuildBudget(4, limits)).toBeNull();
    expect(describeBuildBudget(100, null)).toBeNull();
    expect(describeBuildBudget(100, limits)).toBe('Up to 9 min if every topic is slow to answer.');
  });
});

describe('isSignificantResize', () => {
  const size = { width: 900, height: 600 };

  // L'inspecteur prend 320 px au canevas sur desktop : c'est le cas qui laissait le graphe
  // décalé, une partie passant sous le panneau qui venait de s'ouvrir.
  it('the inspector opening is a resize worth reframing for', () => {
    expect(isSignificantResize(size, { width: 580, height: 600 })).toBe(true);
  });

  it('a window resize in either axis counts', () => {
    expect(isSignificantResize(size, { width: 900, height: 400 })).toBe(true);
    expect(isSignificantResize(size, { width: 1400, height: 600 })).toBe(true);
  });

  // Une barre de défilement qui apparaît, un arrondi de sous-pixel : recadrer là-dessus ferait
  // sautiller le graphe sans rien améliorer.
  it('a sub-pixel or scrollbar-sized jitter does not', () => {
    expect(isSignificantResize(size, { width: 900.4, height: 600 })).toBe(false);
    expect(isSignificantResize(size, { width: 900 + RESIZE_EPSILON_PX - 1, height: 600 })).toBe(false);
    expect(isSignificantResize(size, { width: 900 + RESIZE_EPSILON_PX, height: 600 })).toBe(true);
  });

  // Le cadrage initial vient d'ailleurs (la génération) ; une première mesure ne le refait pas.
  it('a first measurement reframes nothing', () => {
    expect(isSignificantResize(null, size)).toBe(false);
  });

  // Un onglet caché ou un panneau replié mesurent zéro, et cadrer sur zéro donne une
  // transformation absurde qui resterait à l'écran au retour.
  it('a zero-sized viewport is never reframed for', () => {
    expect(isSignificantResize(size, { width: 0, height: 0 })).toBe(false);
    expect(isSignificantResize(size, { width: 900, height: 0 })).toBe(false);
  });
});

describe('capTopics', () => {
  it('keeps everything under the cap, with no overflow', () => {
    const topics = ['a', 'b', 'c'];
    expect(capTopics(topics, DEFAULT_MAX_TOPICS)).toEqual({ kept: topics, overflow: [] });
  });

  it('splits at the cap and names what is left out — never a silent truncation', () => {
    const topics = Array.from({ length: 35 }, (_, i) => `t${i}`);
    const { kept, overflow } = capTopics(topics, DEFAULT_MAX_TOPICS);
    expect(kept).toHaveLength(DEFAULT_MAX_TOPICS);
    expect(overflow).toHaveLength(5);
    expect(kept).toEqual(topics.slice(0, DEFAULT_MAX_TOPICS));
    expect(overflow).toEqual(topics.slice(DEFAULT_MAX_TOPICS));
  });

  it('an empty list stays empty', () => {
    expect(capTopics([], DEFAULT_MAX_TOPICS)).toEqual({ kept: [], overflow: [] });
  });
});

describe('topicDomains / domainColors', () => {
  it('drops the leading segments every topic shares', () => {
    const domains = topicDomains(['demo.orders.nested', 'demo.payments.captured', 'demo.orders']);
    expect(domains.get('demo.orders.nested')).toBe('orders');
    expect(domains.get('demo.payments.captured')).toBe('payments');
    expect(domains.get('demo.orders')).toBe('orders');
  });

  it('keeps the first segment when nothing is shared, and survives single-segment names', () => {
    const domains = topicDomains(['orders', 'billing.invoices']);
    expect(domains.get('orders')).toBe('orders');
    expect(domains.get('billing.invoices')).toBe('billing');
  });

  it('does not strip a shared segment when a topic has nothing left behind it', () => {
    // "demo" alone would end up with no segment if the head were dropped.
    const domains = topicDomains(['demo', 'demo.orders']);
    expect(domains.get('demo')).toBe('demo');
    expect(domains.get('demo.orders')).toBe('demo');
  });

  it('assigns stable tints to sorted domains', () => {
    const colors = domainColors(new Map([['t1', 'zeta'], ['t2', 'alpha']]));
    expect(colors.get('alpha')).toBe(DOMAIN_PALETTE[0]);
    expect(colors.get('zeta')).toBe(DOMAIN_PALETTE[1]);
  });
});

describe('describeModel', () => {
  const base: DataModelResponse = {
    entities: [], relations: [], warnings: [],
    topicsRequested: 3, topicsAnalyzed: 3, truncated: false,
  };

  it('states a complete model plainly', () => {
    expect(describeModel({ ...base, relations: [relation('a', 'b')] }))
      .toBe('3 entities · 1 relation deduced');
  });

  it('a partial model says what it is partial over', () => {
    expect(describeModel({ ...base, topicsAnalyzed: 2 }))
      .toBe('2 entities (of 3 topics selected) · 0 relations deduced');
  });

  it('singular forms read correctly', () => {
    expect(describeModel({ ...base, topicsRequested: 1, topicsAnalyzed: 1 }))
      .toBe('1 entity · 0 relations deduced');
  });
});

describe('splitByConnectivity', () => {
  it('separates entities a relation touches from those none does', () => {
    const a = entity('a', 1);
    const b = entity('b', 1);
    const lonely = entity('lonely', 1);
    const { connected, isolated } = splitByConnectivity(
      [a, b, lonely], [relation('a', 'b')]);
    expect(connected.map(e => e.id)).toEqual(['a', 'b']);
    expect(isolated.map(e => e.id)).toEqual(['lonely']);
  });

  it('a self-relation and a relation to an unknown entity connect nothing', () => {
    const { connected, isolated } = splitByConnectivity(
      [entity('a', 1)], [relation('a', 'a'), relation('a', 'ghost')]);
    expect(connected).toHaveLength(0);
    expect(isolated.map(e => e.id)).toEqual(['a']);
  });
});

describe('formatCount', () => {
  it('compacts thousands and millions, keeps small numbers whole', () => {
    expect(formatCount(847)).toBe('847');
    expect(formatCount(12_340)).toBe('12.3K');
    expect(formatCount(1_250_000)).toBe('1.3M');
    expect(formatCount(0)).toBe('0');
  });

  it('says so rather than printing a number it does not have', () => {
    expect(formatCount(Number.NaN)).toBe('—');
    expect(formatCount(Number.POSITIVE_INFINITY)).toBe('—');
  });
});

describe('describeRelation', () => {
  it('states the link, the grade and the evidence in one sentence', () => {
    const described = describeRelation({
      from: 'demo_payments', to: 'demo_orders',
      fromColumn: 'order_id', toColumn: 'order_id',
      confidence: 'HIGH', reason: "'order_id' names topic 'demo.orders'.",
    });
    expect(described).toBe(
      "demo_payments.order_id → demo_orders.order_id · high confidence · "
      + "'order_id' names topic 'demo.orders'.");
  });

  it('omits the target column when the target has no matching key', () => {
    expect(describeRelation(relation('a', 'b', 'x_id'))).toContain('a.x_id → b ·');
  });
});

describe('matchingColumns / describeColumnMatches', () => {
  const orders = entity('orders', 2, {
    columns: [
      { name: 'order_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
      { name: 'amount', type: 'DOUBLE', primaryKey: false, references: null, keyBase: null },
    ],
  });
  const payments = entity('payments', 2, {
    columns: [
      { name: 'ORDER_ID', type: 'STRING', primaryKey: false, references: 'orders', keyBase: null },
      { name: 'customer.id', type: 'STRING', primaryKey: false, references: null, keyBase: null },
    ],
  });

  it('matches as a case-insensitive substring of the whole path', () => {
    const matches = matchingColumns([orders, payments], 'order_id');
    expect([...matches.get('orders')!]).toEqual(['order_id']);
    expect([...matches.get('payments')!]).toEqual(['ORDER_ID']);
  });

  it('a nested path answers to either half of it', () => {
    expect(matchingColumns([payments], 'customer').get('payments')).toBeDefined();
    expect(matchingColumns([payments], 'id').get('payments')!.size).toBe(2);
  });

  it('an empty term designates nothing — highlighting everything says nothing', () => {
    expect(matchingColumns([orders, payments], '   ').size).toBe(0);
    expect(describeColumnMatches(new Map(), '  ')).toBe('');
  });

  it('zero reads as a zero, not as an absent answer', () => {
    const matches = matchingColumns([orders], 'nope');
    expect(matches.size).toBe(0);
    expect(describeColumnMatches(matches, 'nope')).toBe('No column matches “nope”');
  });

  it('counts columns and entities, singular forms included', () => {
    expect(describeColumnMatches(matchingColumns([orders, payments], 'order_id'), 'order_id'))
      .toBe('2 columns in 2 entities');
    expect(describeColumnMatches(matchingColumns([orders], 'amount'), 'amount'))
      .toBe('1 column in 1 entity');
  });
});

describe('escapeXml', () => {
  it('escapes what would break the markup — topic and column names go through it', () => {
    expect(escapeXml('a & b <c> "d"')).toBe('a &amp; b &lt;c&gt; &quot;d&quot;');
  });
});

describe('exportNotes', () => {
  const base: DataModelResponse = {
    entities: [], relations: [], warnings: [],
    topicsRequested: 5, topicsAnalyzed: 5, truncated: false,
  };

  it('says how many entities the drawing leaves out', () => {
    expect(exportNotes(base, 3)[0]).toBe('3 entities have no deduced relation and are not drawn.');
    expect(exportNotes(base, 1)[0]).toBe('1 entity has no deduced relation and are not drawn.');
  });

  it('carries the server warnings verbatim', () => {
    const notes = exportNotes({ ...base, warnings: ["Topic 'x' yielded no schema."] }, 0);
    expect(notes).toEqual(["Topic 'x' yielded no schema."]);
  });

  it('counts the warnings past the cap rather than printing a log', () => {
    const warnings = Array.from({ length: 7 }, (_, i) => `w${i}`);
    const notes = exportNotes({ ...base, warnings }, 0);
    expect(notes).toHaveLength(5);
    expect(notes[4]).toBe('… and 3 more warnings.');
  });

  it('a clean model with everything drawn needs no note', () => {
    expect(exportNotes(base, 0)).toEqual([]);
  });
});

describe('buildExportSvg', () => {
  const bounds = { minX: 40, minY: 40, maxX: 640, maxY: 340 };
  const caption = { title: 'Kafka data model', coverage: '4 entities · 3 relations deduced', notes: [] as string[] };

  it('produces a standalone SVG sized to the graph plus its chrome', () => {
    const svg = buildExportSvg('<g id="inner"/>', bounds, caption, []);
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true);
    expect(svg).toContain('<g id="inner"/>');
    const width = Number(/width="(\d+)"/.exec(svg)![1]);
    const height = Number(/height="(\d+)"/.exec(svg)![1]);
    // 600 wide graph + 2 × 32 padding; taller than the graph because of the caption block.
    expect(width).toBe(664);
    expect(height).toBeGreaterThan(340 - 40 + 64);
    expect(svg).toContain(`viewBox="0 0 ${width} ${height}"`);
  });

  it('translates the graph so its own origin is not lost', () => {
    const svg = buildExportSvg('<g/>', bounds, caption, []);
    // dx = padding − minX = 32 − 40 = −8.
    expect(svg).toMatch(/<g transform="translate\(-8, \d+\)">/);
  });

  it('carries the coverage line — a detached diagram must say what it covers', () => {
    const svg = buildExportSvg('', bounds, caption, []);
    expect(svg).toContain('Kafka data model');
    expect(svg).toContain('4 entities · 3 relations deduced');
  });

  it('carries the notes and escapes them', () => {
    const svg = buildExportSvg('', bounds,
      { ...caption, notes: ['2 entities & "others" are not drawn'] }, []);
    expect(svg).toContain('2 entities &amp; &quot;others&quot; are not drawn');
  });

  it('draws both legends, dash included, so a dotted line stays interpretable', () => {
    const svg = buildExportSvg('', bounds, caption,
      [{ color: '#7ee2a8', label: 'high — key columns agree' },
       { color: '#79839a', dash: '2 4', label: 'low — shared key column' }],
      [{ color: '#a3adff', label: 'orders' }]);
    expect(svg).toContain('high — key columns agree');
    expect(svg).toContain('stroke-dasharray="2 4"');
    expect(svg).toContain('>orders</text>');
  });

  it('paints a background — a transparent export is unreadable on a light page', () => {
    const svg = buildExportSvg('', bounds, caption, []);
    expect(svg).toMatch(/<rect width="\d+" height="\d+" fill="#[0-9a-f]{6}"\/>/);
  });

  it('widens for a caption longer than a one-table graph', () => {
    const narrow = { minX: 0, minY: 0, maxX: 100, maxY: 100 };
    const svg = buildExportSvg('', narrow, caption, []);
    expect(Number(/width="(\d+)"/.exec(svg)![1])).toBeGreaterThan(100 + 64);
  });
});

describe('toMermaidEr', () => {
  const caption = { title: 'Kafka data model', coverage: '2 entities · 1 relation deduced', notes: [] as string[] };

  function model(): DataModelResponse {
    const orders = entity('demo_orders', 0, {
      primaryKey: 'order_id',
      columns: [
        { name: 'order_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
        { name: 'amount', type: 'DOUBLE', primaryKey: false, references: null, keyBase: null },
      ],
    });
    const payments = entity('demo_payments', 0, {
      columns: [
        { name: 'order_id', type: 'STRING', primaryKey: false, references: 'demo_orders', keyBase: null },
      ],
    });
    return {
      entities: [orders, payments],
      relations: [{
        from: 'demo_payments', to: 'demo_orders',
        fromColumn: 'order_id', toColumn: 'order_id',
        confidence: 'HIGH', reason: 'test',
      }],
      warnings: [], topicsRequested: 2, topicsAnalyzed: 2, truncated: false,
    };
  }

  it('emits a valid erDiagram with the caption as comments', () => {
    const mermaid = toMermaidEr(model(), caption);
    const lines = mermaid.split('\n');
    expect(lines[0]).toBe('%% Kafka data model');
    expect(lines[1]).toBe('%% 2 entities · 1 relation deduced');
    expect(lines[2]).toBe('erDiagram');
  });

  it('draws the relation from the referenced side, with the confidence in the label', () => {
    const mermaid = toMermaidEr(model(), caption);
    // Mermaid has no per-edge line style, so a diagram that dropped the grade would present a
    // guess and a key match identically.
    expect(mermaid).toContain('demo_orders ||--o{ demo_payments : "order_id (high)"');
  });

  it('marks keys and foreign keys', () => {
    const mermaid = toMermaidEr(model(), caption);
    expect(mermaid).toContain('STRING order_id PK');
    expect(mermaid).toContain('STRING order_id FK');
    expect(mermaid).toContain('DOUBLE amount');
  });

  it('sanitises identifiers but keeps the original name as a comment', () => {
    const nested = entity('t', 0, {
      columns: [{ name: 'customer.address.city', type: 'STRING', primaryKey: false, references: null, keyBase: null }],
    });
    const mermaid = toMermaidEr(
      { entities: [nested], relations: [], warnings: [], topicsRequested: 1, topicsAnalyzed: 1, truncated: false },
      caption);
    expect(mermaid).toContain('STRING customer_address_city "customer.address.city"');
  });

  it('carries the notes, so a text export states its bounds like the image does', () => {
    const mermaid = toMermaidEr(model(), { ...caption, notes: ['3 entities have no deduced relation and are not drawn.'] });
    expect(mermaid).toContain('%% 3 entities have no deduced relation and are not drawn.');
  });

  it('skips a relation whose endpoints are not both drawn', () => {
    const m = model();
    m.relations.push({
      from: 'demo_payments', to: 'ghost', fromColumn: 'x_id', toColumn: null,
      confidence: 'LOW', reason: 'test',
    });
    expect(toMermaidEr(m, caption)).not.toContain('ghost');
  });

  it('an empty model is still a valid diagram, not a broken one', () => {
    const empty = toMermaidEr(
      { entities: [], relations: [], warnings: [], topicsRequested: 0, topicsAnalyzed: 0, truncated: false },
      caption);
    expect(empty.trim().endsWith('erDiagram')).toBe(true);
  });
});

describe('joinAliases', () => {
  it('uses the distinctive segment of each topic', () => {
    expect(joinAliases('demo.payments.authorized', 'demo.orders.1.received'))
      .toEqual(['payments', 'orders']);
  });

  it('falls back to the last segment for sibling topics, never to a numeric alias', () => {
    // Both share `demo.orders`, so what the domain rule isolates is `1` and `2` — not SQL
    // identifiers, and the proposed query would have died at the parser.
    expect(joinAliases('demo.orders.1.received', 'demo.orders.2.validated'))
      .toEqual(['received', 'validated']);
  });

  it('suffixes only when nothing else separates the two', () => {
    const [a, b] = joinAliases('demo.orders.1', 'demo.orders.2');
    expect(a).not.toBe(b);
    expect(a).toMatch(/^[A-Za-z_]/);
    expect(b).toMatch(/^[A-Za-z_]/);
  });
});

describe('buildJoinSql', () => {
  const orders = entity('demo_orders', 0, {
    topic: 'demo.orders.1.received',
    primaryKey: 'order_id',
    columns: [
      { name: 'order_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
      { name: 'status', type: 'STRING', primaryKey: false, references: null, keyBase: null },
      { name: 'amount_cents', type: 'BIGINT', primaryKey: false, references: null, keyBase: null },
    ],
  });
  const payments = entity('demo_payments', 0, {
    topic: 'demo.payments.authorized',
    primaryKey: 'payment_id',
    columns: [
      { name: 'payment_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
      { name: 'order_id', type: 'STRING', primaryKey: false, references: 'demo_orders', keyBase: null },
      { name: 'method', type: 'STRING', primaryKey: false, references: null, keyBase: null },
    ],
  });
  const rel = (over: Partial<DataModelRelation> = {}): DataModelRelation => ({
    from: 'demo_payments', to: 'demo_orders',
    fromColumn: 'order_id', toColumn: 'order_id',
    confidence: 'HIGH', reason: 'Key columns agree.',
    ...over,
  });

  it('writes the join the relation describes, with readable aliases', () => {
    const join = buildJoinSql(rel(), [orders, payments])!;
    expect(join.sql).toContain('FROM demo_payments AS payments');
    expect(join.sql).toContain('JOIN demo_orders AS orders');
    expect(join.sql).toContain('  ON payments.order_id = orders.order_id');
    expect(join.caveats).toEqual([]);
  });

  it('names columns rather than SELECT * — both sides carry order_id', () => {
    const join = buildJoinSql(rel(), [orders, payments])!;
    expect(join.sql).not.toContain('SELECT *');
    expect(join.sql).toContain('    payments.order_id');
    expect(join.sql).toContain('    orders.order_id');
  });

  it('carries the evidence and the unbounded-join caveat into the query', () => {
    const join = buildJoinSql(rel(), [orders, payments])!;
    expect(join.sql).toContain('-- Key columns agree.');
    expect(join.sql).toContain('high confidence');
    expect(join.sql).toContain('Flink keeps both sides in state');
  });

  it('bounds the result, so the editor gets a query that terminates', () => {
    expect(buildJoinSql(rel(), [orders, payments])!.sql.endsWith('LIMIT 50')).toBe(true);
  });

  it('falls back to the target key when the relation names no column, and says so', () => {
    const join = buildJoinSql(rel({ toColumn: null }), [orders, payments])!;
    expect(join.sql).toContain('ON payments.order_id = orders.order_id');
    expect(join.caveats[0]).toContain("detected key 'order_id'");
    // The assumption travels in the SQL itself, not only in a tooltip nobody exports.
    expect(join.sql).toContain('-- The relation names no column');
  });

  it('refuses rather than inventing a predicate when nothing resolves', () => {
    const keyless = entity('demo_orders', 0, {
      topic: 'demo.orders.1.received',
      primaryKey: null,
      columns: [{ name: 'status', type: 'STRING', primaryKey: false, references: null, keyBase: null }],
    });
    expect(buildJoinSql(rel({ toColumn: null }), [keyless, payments])).toBeNull();
  });

  it('leaves nested fields out of the projection and reports how many', () => {
    const nested = entity('demo_payments', 0, {
      topic: 'demo.payments.authorized',
      primaryKey: 'payment_id',
      columns: [
        { name: 'payment_id', type: 'STRING', primaryKey: true, references: null, keyBase: null },
        { name: 'order_id', type: 'STRING', primaryKey: false, references: 'demo_orders', keyBase: null },
        { name: 'card.last4', type: 'STRING', primaryKey: false, references: null, keyBase: null },
      ],
    });
    const join = buildJoinSql(rel(), [orders, nested])!;
    expect(join.sql).not.toContain('card.last4');
    expect(join.caveats.some(c => c.includes('1 nested field'))).toBe(true);
  });

  it('caps the projection so the query stays a starting point', () => {
    const wide = entity('demo_payments', 0, {
      topic: 'demo.payments.authorized',
      primaryKey: 'payment_id',
      columns: Array.from({ length: 20 }, (_, i) => ({
        name: i === 0 ? 'order_id' : `f_${i}`, type: 'STRING',
        primaryKey: false, references: null, keyBase: null
      })),
    });
    const join = buildJoinSql(rel(), [orders, wide])!;
    const projected = join.sql.split('\n').filter(l => l.startsWith('    payments.'));
    expect(projected).toHaveLength(5);
    // The join column is kept whatever the cap: without it the query reads as unrelated.
    expect(projected[0]).toMatch(/^ {4}payments\.order_id,?$/);
  });

  it('returns null when an endpoint is not in the model', () => {
    expect(buildJoinSql(rel({ to: 'ghost' }), [payments])).toBeNull();
  });
});

describe('filterRelations / describeRelationFilter', () => {
  const all = new Set<RelationConfidence>(['HIGH', 'MEDIUM', 'LOW']);
  const relations: DataModelRelation[] = [
    { ...relation('a', 'b'), confidence: 'HIGH' },
    { ...relation('b', 'c'), confidence: 'MEDIUM' },
    { ...relation('c', 'd'), confidence: 'LOW' },
  ];

  it('every grade enabled keeps every relation', () => {
    expect(filterRelations(relations, all)).toHaveLength(3);
  });

  it('drops exactly the grades that are off', () => {
    const kept = filterRelations(relations, new Set<RelationConfidence>(['HIGH']));
    expect(kept).toHaveLength(1);
    expect(kept[0].confidence).toBe('HIGH');
  });

  it('no grade enabled draws nothing, without throwing', () => {
    expect(filterRelations(relations, new Set<RelationConfidence>())).toEqual([]);
  });

  it('says nothing when the filter hides nothing — a permanent "0 hidden" teaches you to skip the line', () => {
    expect(describeRelationFilter(3, 3)).toBeNull();
    expect(describeRelationFilter(0, 0)).toBeNull();
  });

  it('states what it hid, out of how many', () => {
    expect(describeRelationFilter(9, 4)).toBe('5 of 9 relations hidden by the confidence filter');
  });
});

/*
 * `idBaseOf` is a mirror of `DataModelService.idBaseOf`, so the expectations below are the ones
 * `DataModelServiceTest` already pins on the server — copied deliberately rather than invented,
 * since a mirror tested against its own reading of itself proves nothing. The server is the
 * authority; if these two ever disagree, this file is the one that is wrong.
 */
describe('shortenColumnName', () => {
  it('leaves a name that fits untouched', () => {
    expect(shortenColumnName('order_id', 22)).toBe('order_id');
  });

  it('cuts a flat name from the right — it has no redundant part', () => {
    expect(shortenColumnName('a_very_long_column_name_here', 12)).toBe('a_very_long…');
  });

  it('keeps the leaf of a dotted path and elides the prefix', () => {
    // Cutting from the right gives 'shipping.address.ci…', which amputates exactly the part
    // that tells one column of a sub-object from another.
    const short = shortenColumnName('shipping.address.city', 14);
    expect(short.startsWith('…')).toBe(true);
    expect(short.endsWith('city')).toBe(true);
    expect(short.length).toBeLessThanOrEqual(14);
  });

  it('keeps as many trailing segments as fit', () => {
    expect(shortenColumnName('a.b.c.d', 6)).toBe('…b.c.d');
    expect(shortenColumnName('a.b.c.d', 5)).toBe('…c.d');
  });

  it('a leaf longer than the budget is cut rather than reduced to an ellipsis alone', () => {
    const short = shortenColumnName('shipping.averyveryverylongleafname', 10);
    expect(short.length).toBeLessThanOrEqual(10);
    expect(short).toContain('avery');
  });
});

describe('selection draft', () => {
  it('round-trips an unrun selection with the budget it was chosen under', () => {
    saveSelectionDraft(['demo.orders', 'demo.payments'], 60);
    expect(readSelectionDraft(DEFAULT_MAX_TOPICS))
      .toEqual({ topics: ['demo.orders', 'demo.payments'], maxTopics: 60 });
  });

  it('an emptied selection clears the draft rather than storing nothing usefully', () => {
    saveSelectionDraft(['demo.orders'], 30);
    saveSelectionDraft([], 30);
    expect(readSelectionDraft(DEFAULT_MAX_TOPICS)).toBeNull();
  });

  it('nothing stored reads as no draft', () => {
    expect(readSelectionDraft(DEFAULT_MAX_TOPICS)).toBeNull();
  });

  // Le budget relu borne la relecture : sans ça, une sélection de 40 topics choisie sous un
  // budget de 60 revenait tronquée à 30 par le seul défaut de l'appelant.
  it('the draft is capped by its own budget, not by the caller\'s default', () => {
    saveSelectionDraft(Array.from({ length: 40 }, (_, i) => `t${i}`), 60);
    expect(readSelectionDraft(DEFAULT_MAX_TOPICS)?.topics).toHaveLength(40);
  });

  it('a draft with no budget of its own falls back to the cap it is read with', () => {
    saveSelectionDraft(Array.from({ length: 40 }, (_, i) => `t${i}`), 100);
    localStorage.setItem('kse:draft:data-model', JSON.stringify({
      v: 1, at: Date.now(), value: Array.from({ length: 40 }, (_, i) => `t${i}`),
    }));
    const draft = readSelectionDraft(DEFAULT_MAX_TOPICS);
    expect(draft?.topics).toHaveLength(DEFAULT_MAX_TOPICS);
    expect(draft?.maxTopics).toBeNull();
  });

  it('a draft of the wrong shape is ignored rather than trusted', () => {
    saveSelectionDraft(['ok'], 30);
    // Ce qu'une version antérieure aurait pu écrire : des entrées qui ne sont pas des topics.
    localStorage.setItem('kse:draft:data-model',
      JSON.stringify({ v: 1, at: Date.now(), value: { topics: [1, null, 'kept'], maxTopics: 'x' } }));
    expect(readSelectionDraft(DEFAULT_MAX_TOPICS)).toEqual({ topics: ['kept'], maxTopics: null });
  });
});

describe('minimap', () => {
  const bounds = { minX: 0, minY: 0, maxX: 2000, maxY: 1000 };

  it('fits the whole graph inside the box', () => {
    const layout = minimapLayout(bounds, 168, 120, 4);
    expect(2000 * layout.scale).toBeLessThanOrEqual(168 - 8 + 0.001);
    expect(1000 * layout.scale).toBeLessThanOrEqual(120 - 8 + 0.001);
  });

  it('reports what is visible in graph coordinates', () => {
    const visible = visibleGraphRect({ x: -100, y: -50, scale: 2 }, 400, 200);
    expect(visible).toEqual({ minX: 50, minY: 25, maxX: 250, maxY: 125 });
  });

  it('a graph entirely on screen needs no minimap — it would only frame its own box', () => {
    expect(graphFullyVisible(bounds, { x: 0, y: 0, scale: 0.05 }, 400, 200)).toBe(true);
  });

  it('a graph that overflows does need one', () => {
    expect(graphFullyVisible(bounds, { x: 0, y: 0, scale: 1 }, 400, 200)).toBe(false);
  });

  it('clicking a point centres the viewport on it, at the current scale', () => {
    const t = centerOnGraphPoint({ x: 1000, y: 500 }, 400, 200, 0.5);
    expect(t.scale).toBe(0.5);
    const visible = visibleGraphRect(t, 400, 200);
    expect((visible.minX + visible.maxX) / 2).toBeCloseTo(1000);
    expect((visible.minY + visible.maxY) / 2).toBeCloseTo(500);
  });
});

describe('saved selections', () => {
  it('round-trips a named selection', () => {
    saveModel('Order pipeline', ['demo.orders', 'demo.payments'], 30, 111);
    expect(readSavedModels()).toEqual([
      { name: 'Order pipeline', topics: ['demo.orders', 'demo.payments'], maxTopics: 30, at: 111 },
    ]);
  });

  it('saving under an existing name replaces it in place rather than stacking a homonym', () => {
    saveModel('Pipeline', ['a'], 30, 1);
    saveModel('Pipeline', ['a', 'b'], 30, 2);
    const models = readSavedModels();
    expect(models).toHaveLength(1);
    expect(models[0].topics).toEqual(['a', 'b']);
  });

  it('the newest save comes first', () => {
    saveModel('first', ['a'], 30, 1);
    saveModel('second', ['b'], 30, 2);
    expect(readSavedModels().map(m => m.name)).toEqual(['second', 'first']);
  });

  it('deletes by name and leaves the rest', () => {
    saveModel('keep', ['a'], 30, 1);
    saveModel('drop', ['b'], 30, 2);
    expect(deleteSavedModel('drop').map(m => m.name)).toEqual(['keep']);
    expect(readSavedModels().map(m => m.name)).toEqual(['keep']);
  });

  it('refuses a blank name or an empty selection rather than storing an unusable entry', () => {
    saveModel('   ', ['a'], 30, 30);
    saveModel('named', [], 30, 30);
    expect(readSavedModels()).toEqual([]);
  });

  it('keeps the budget the selection was chosen under', () => {
    saveModel('orders', ['a', 'b'], 60, 1);
    expect(readSavedModels()[0].maxTopics).toBe(60);
  });

  // Une entrée écrite avant que le budget ne soit réglable se relit : elle reprend le défaut,
  // elle n'est pas jetée.
  it('an entry written before the budget existed reads back with none', () => {
    localStorage.setItem('kse:data-model-saved', JSON.stringify({
      v: 1, models: [{ name: 'legacy', topics: ['a'], at: 1 }],
    }));
    expect(readSavedModels()[0]).toEqual({ name: 'legacy', topics: ['a'], maxTopics: null, at: 1 });
  });

  it('is bounded', () => {
    for (let i = 0; i < MAX_SAVED_MODELS + 5; i++) saveModel(`m${i}`, ['a'], i);
    expect(readSavedModels()).toHaveLength(MAX_SAVED_MODELS);
  });

  it('an envelope of an unknown shape is erased rather than guessed at', () => {
    localStorage.setItem(SAVED_MODELS_KEY, JSON.stringify({ v: 99, models: [{ name: 'x', topics: ['a'] }] }));
    expect(readSavedModels()).toEqual([]);
    expect(localStorage.getItem(SAVED_MODELS_KEY)).toBeNull();
  });

  it('unreadable JSON is erased rather than thrown on', () => {
    localStorage.setItem(SAVED_MODELS_KEY, '{not json');
    expect(readSavedModels()).toEqual([]);
  });

  it('drops entries that carry no usable topic', () => {
    localStorage.setItem(SAVED_MODELS_KEY, JSON.stringify({
      v: 1,
      models: [{ name: 'ok', topics: ['a', 2, null] }, { name: 'empty', topics: [] }],
    }));
    expect(readSavedModels()).toEqual([{ name: 'ok', topics: ['a'], maxTopics: null, at: 0 }]);
  });

  it('nothing stored reads as an empty list', () => {
    clearSavedModels();
    expect(readSavedModels()).toEqual([]);
  });
});

describe('joinAliasesFor / buildMultiJoinSql', () => {
  const col = (name: string, over: Partial<DataModelEntity['columns'][number]> = {}) => ({
    name, type: 'STRING', primaryKey: false, references: null, keyBase: null, ...over,
  });
  const ent = (id: string, topic: string, columns: DataModelEntity['columns'],
               primaryKey: string | null = null): DataModelEntity =>
    ({ ...entity(id, 0), topic, columns, primaryKey });

  const orders = ent('demo_orders', 'demo.orders.received',
    [col('order_id', { primaryKey: true }), col('status')], 'order_id');
  const payments = ent('demo_payments', 'demo.payments.authorized',
    [col('payment_id', { primaryKey: true }), col('order_id', { references: 'demo_orders' })],
    'payment_id');
  const shipments = ent('demo_shipments', 'demo.shipments.dispatched',
    [col('shipment_id', { primaryKey: true }), col('order_id', { references: 'demo_orders' })],
    'shipment_id');

  const rel = (from: string, to: string, fromColumn: string, toColumn: string | null)
    : DataModelRelation => ({
      from, to, fromColumn, toColumn, confidence: 'HIGH', reason: `${fromColumn} names ${to}.`,
    });

  it('gives every topic a distinct, SQL-legal alias', () => {
    const aliases = joinAliasesFor([orders.topic, payments.topic, shipments.topic]);
    expect(new Set(aliases).size).toBe(3);
    aliases.forEach(a => expect(a).toMatch(/^[A-Za-z_]/));
  });

  it('numbers homonyms rather than emitting the same alias twice', () => {
    // Deux topics dont le segment distinctif est un chiffre : aucun nom exploitable.
    const aliases = joinAliasesFor(['demo.orders.1', 'demo.orders.2']);
    expect(new Set(aliases).size).toBe(2);
    aliases.forEach(a => expect(a).toMatch(/^[A-Za-z_]/));
  });

  it('joins three entities along their deduced relations', () => {
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders', 'demo_shipments'],
      [orders, payments, shipments],
      [rel('demo_payments', 'demo_orders', 'order_id', 'order_id'),
       rel('demo_shipments', 'demo_orders', 'order_id', 'order_id')]);

    expect(result.problem).toBeNull();
    expect(result.sql).toContain('FROM demo_payments AS');
    // Deux JOIN pour trois tables : un arbre couvrant, pas un produit cartésien.
    expect(result.sql!.match(/^JOIN /gm)).toHaveLength(2);
    expect(result.sql).toContain('LIMIT 50');
  });

  it('every JOIN predicate cites a table already introduced', () => {
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders', 'demo_shipments'],
      [orders, payments, shipments],
      [rel('demo_payments', 'demo_orders', 'order_id', 'order_id'),
       rel('demo_shipments', 'demo_orders', 'order_id', 'order_id')]);

    const lines = result.sql!.split('\n');
    const introduced = new Set<string>();
    for (const line of lines) {
      const from = /^FROM \S+ AS (\S+)/.exec(line);
      if (from) { introduced.add(from[1]); continue; }
      const join = /^JOIN \S+ AS (\S+)/.exec(line);
      if (join) { introduced.add(join[1]); continue; }
      const on = /^ {2}ON (\S+?)\.\S+ = (\S+?)\./.exec(line);
      if (on) {
        expect(introduced.has(on[1])).toBe(true);
        expect(introduced.has(on[2])).toBe(true);
      }
    }
  });

  it('refuses a set the deduced relations do not connect, and says so', () => {
    const lonely = ent('demo_iot', 'demo.iot.sensors', [col('reading')]);
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders', 'demo_iot'],
      [orders, payments, lonely],
      [rel('demo_payments', 'demo_orders', 'order_id', 'order_id')]);

    expect(result.sql).toBeNull();
    expect(result.problem).toContain('demo_iot');
  });

  it('refuses fewer than two entities', () => {
    const result = buildMultiJoinSql(['demo_orders'], [orders], []);
    expect(result.sql).toBeNull();
    expect(result.problem).toContain('at least two');
  });

  it('falls back to the target key when a relation names no column, and says it', () => {
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders'], [orders, payments],
      [rel('demo_payments', 'demo_orders', 'order_id', null)]);
    expect(result.sql).toContain('.order_id');
  });

  it('refuses an edge whose target has neither a named column nor a detected key', () => {
    const keyless = ent('demo_orders', 'demo.orders.received', [col('status')]);
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders'], [keyless, payments],
      [rel('demo_payments', 'demo_orders', 'order_id', null)]);
    expect(result.sql).toBeNull();
    expect(result.problem).toContain('demo_orders');
  });

  it('leaves nested fields out of the projection and reports how many', () => {
    const nested = ent('demo_payments', 'demo.payments.authorized', [
      col('payment_id', { primaryKey: true }),
      col('order_id', { references: 'demo_orders' }),
      col('card.last4'),
    ], 'payment_id');
    const result = buildMultiJoinSql(
      ['demo_payments', 'demo_orders'], [orders, nested],
      [rel('demo_payments', 'demo_orders', 'order_id', 'order_id')]);
    expect(result.sql).not.toContain('card.last4');
    expect(result.caveats.some(c => c.includes('1 nested field'))).toBe(true);
  });
});

describe('orphanKeyColumns / describeOrphanKey', () => {
  const col = (name: string, over: Partial<DataModelEntity['columns'][number]> = {}) => ({
    name, type: 'STRING', primaryKey: false, references: null, keyBase: null, ...over,
  });

  function withColumns(id: string, columns: DataModelEntity['columns']): DataModelEntity {
    return { ...entity(id, 0), columns };
  }

  it('flags a column the server said points somewhere, that produced no relation', () => {
    const e = withColumns('demo_payments', [
      col('payment_id', { primaryKey: true }),
      col('customer_id', { keyBase: 'customer' }),
      col('amount'),
    ]);
    const orphans = orphanKeyColumns(e);
    expect(orphans.map(o => o.column)).toEqual(['customer_id']);
    expect(orphans[0].base).toBe('customer');
  });

  it('a column a relation did come out of is not an orphan', () => {
    const e = withColumns('demo_payments', [
      col('order_id', { keyBase: 'order', references: 'demo_orders' }),
    ]);
    expect(orphanKeyColumns(e)).toEqual([]);
  });

  it('the primary key is never an orphan', () => {
    const e = withColumns('demo_orders', [col('order_id', { primaryKey: true, keyBase: 'order' })]);
    expect(orphanKeyColumns(e)).toEqual([]);
  });

  it('a column the server judged to point at nobody is never flagged', () => {
    // The server returns keyBase null for an ordinary column, for a bare id, and for a name
    // echoing its own topic — three cases, one answer, and none of them is a broken key.
    const e = withColumns('demo_orders', [col('amount'), col('id'), col('order_id')]);
    expect(orphanKeyColumns(e)).toEqual([]);
  });

  it('an empty keyBase is treated as pointing at nobody', () => {
    const e = withColumns('demo_orders', [col('id', { keyBase: '' })]);
    expect(orphanKeyColumns(e)).toEqual([]);
  });

  it('states the one thing that is now true by construction, not by check', () => {
    // A target present in the model always yields a relation, so a base with no reference means
    // the topic is absent from the selection — actionable, and not a guess.
    expect(describeOrphanKey({ column: 'customer_id', base: 'customer' }))
      .toBe("Reads as a key naming 'customer' — no selected topic is named after it.");
  });
});

describe('relationKey / diffModels / describeDiff', () => {
  const base: DataModelResponse = {
    entities: [], relations: [], warnings: [],
    topicsRequested: 2, topicsAnalyzed: 2, truncated: false,
  };

  it('two calls with the same shape produce the same key, regardless of confidence or reason', () => {
    const a = relation('orders', 'payments', 'order_id');
    const b = { ...a, confidence: 'MEDIUM' as const, reason: 'a different run, a different wording' };
    expect(relationKey(a)).toBe(relationKey(b));
  });

  it('no difference between two identical models', () => {
    const model: DataModelResponse = {
      ...base,
      entities: [entity('a', 1), entity('b', 1)],
      relations: [relation('a', 'b')],
    };
    const diff = diffModels(model, model);
    expect(diffIsEmpty(diff)).toBe(true);
    expect(describeDiff(diff)).toBe('No difference — same entities and relations.');
  });

  it('an entity present only in the second selection is added, not the reverse', () => {
    const before: DataModelResponse = { ...base, entities: [entity('a', 1)] };
    const after: DataModelResponse = { ...base, entities: [entity('a', 1), entity('b', 1)] };
    const diff = diffModels(before, after);
    expect(diff.addedEntities.map(e => e.id)).toEqual(['b']);
    expect(diff.removedEntities).toHaveLength(0);
    expect(diff.unchangedEntityCount).toBe(1);
  });

  it('an entity dropped from the second selection is removed', () => {
    const before: DataModelResponse = { ...base, entities: [entity('a', 1), entity('b', 1)] };
    const after: DataModelResponse = { ...base, entities: [entity('a', 1)] };
    const diff = diffModels(before, after);
    expect(diff.removedEntities.map(e => e.id)).toEqual(['b']);
    expect(diff.addedEntities).toHaveLength(0);
  });

  it('a relation appearing only after is added; one only before is removed', () => {
    const before: DataModelResponse = { ...base, relations: [relation('a', 'b', 'x_id')] };
    const after: DataModelResponse = { ...base, relations: [relation('a', 'c', 'y_id')] };
    const diff = diffModels(before, after);
    expect(diff.addedRelations).toHaveLength(1);
    expect(diff.addedRelations[0].to).toBe('c');
    expect(diff.removedRelations).toHaveLength(1);
    expect(diff.removedRelations[0].to).toBe('b');
    expect(diff.unchangedRelationCount).toBe(0);
  });

  it('the same relation at a different confidence is a change, not an add+remove pair', () => {
    const before: DataModelResponse = {
      ...base, relations: [{ ...relation('a', 'b'), confidence: 'MEDIUM' }],
    };
    const after: DataModelResponse = {
      ...base, relations: [{ ...relation('a', 'b'), confidence: 'HIGH' }],
    };
    const diff = diffModels(before, after);
    expect(diff.addedRelations).toHaveLength(0);
    expect(diff.removedRelations).toHaveLength(0);
    expect(diff.changedRelations).toHaveLength(1);
    expect(diff.changedRelations[0]).toMatchObject({ from: 'MEDIUM', to: 'HIGH' });
  });

  it('an unchanged relation counts as unchanged, not as a silent add', () => {
    const model: DataModelResponse = { ...base, relations: [relation('a', 'b')] };
    const diff = diffModels(model, { ...model, relations: [...model.relations] });
    expect(diff.addedRelations).toHaveLength(0);
    expect(diff.unchangedRelationCount).toBe(1);
  });

  it('describeDiff summarises every kind of change present, and only those', () => {
    const before: DataModelResponse = {
      ...base,
      entities: [entity('a', 1), entity('b', 1)],
      relations: [{ ...relation('a', 'b', 'x_id'), confidence: 'MEDIUM' }],
    };
    const after: DataModelResponse = {
      ...base,
      entities: [entity('a', 1), entity('c', 1)],
      relations: [{ ...relation('a', 'b', 'x_id'), confidence: 'HIGH' }, relation('a', 'c', 'z_id')],
    };
    const summary = describeDiff(diffModels(before, after));
    expect(summary).toContain('+1 entity');
    expect(summary).toContain('-1 entity');
    expect(summary).toContain('+1 relation');
    expect(summary).toContain('1 confidence changed');
    expect(summary.split(', ')).toHaveLength(4); // no removed-relations clause, since there are none
  });
});

describe('describeBuildProgress / describeStaleGraphDuringBuild', () => {
  it('names the scope before a second has passed, with no invented percentage', () => {
    expect(describeBuildProgress(15, 0)).toBe('Reading 15 topics…');
    expect(describeBuildProgress(15, 900)).toBe('Reading 15 topics…');
  });

  it('counts seconds, then minutes, because the wait is minutes on a large selection', () => {
    expect(describeBuildProgress(15, 42_000)).toBe('Reading 15 topics… 42s');
    expect(describeBuildProgress(15, 125_000)).toBe('Reading 15 topics… 2m 05s');
  });

  it('reads correctly for a single topic', () => {
    expect(describeBuildProgress(1, 3_000)).toBe('Reading 1 topic… 3s');
  });

  it('says the graph on screen is the previous one — only when there is one', () => {
    expect(describeStaleGraphDuringBuild(true)).toContain('previous model');
    expect(describeStaleGraphDuringBuild(false)).toBeNull();
  });
});
