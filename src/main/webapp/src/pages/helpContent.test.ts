// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { describe, it, expect } from 'vitest';
import {
  LESSONS, LESSON_GROUPS, ENGINE_MATRIX, ERROR_GUIDE, EDITOR_TIPS, WINDOW_KINDS, FORMATS,
  topicToTable, editorLink, filterLessons, groupLessons, numberSections,
} from './helpContent';

describe('topicToTable', () => {
  it('replaces dots and dashes with underscores, like the backend does', () => {
    expect(topicToTable('demo.orders.1.received')).toBe('demo_orders_1_received');
    expect(topicToTable('my-topic.v2')).toBe('my_topic_v2');
  });

  it('leaves an already-valid identifier alone', () => {
    expect(topicToTable('orders_typed')).toBe('orders_typed');
  });

  it('trims what a user pastes', () => {
    expect(topicToTable('  demo.customers  ')).toBe('demo_customers');
  });

  it('returns an empty string for an empty input rather than throwing', () => {
    expect(topicToTable('')).toBe('');
  });
});

describe('editorLink', () => {
  it('encodes the SQL exactly once — the editor decodes exactly once', () => {
    const sql = "SELECT * FROM t WHERE state = 'A' LIMIT 10";
    const link = editorLink(sql);
    const param = new URLSearchParams(link.slice(link.indexOf('?'))).get('sql');
    expect(param).toBe(sql);
  });

  it('survives a percent sign, which a double decode would throw on', () => {
    const sql = "SELECT * FROM t WHERE name LIKE '%foo%'";
    const link = editorLink(sql);
    expect(new URLSearchParams(link.slice(link.indexOf('?'))).get('sql')).toBe(sql);
  });
});

describe('filterLessons', () => {
  it('returns everything for an empty or blank query', () => {
    expect(filterLessons(LESSONS, '')).toHaveLength(LESSONS.length);
    expect(filterLessons(LESSONS, '   ')).toHaveLength(LESSONS.length);
  });

  it('matches on a keyword, case-insensitively', () => {
    const hits = filterLessons(LESSONS, 'DEDUPLICATE');
    expect(hits.map((l) => l.id)).toContain('dedup');
  });

  it('matches on the SQL text itself', () => {
    const hits = filterLessons(LESSONS, 'json_value');
    expect(hits.map((l) => l.id)).toEqual(['nested']);
  });

  it('requires every term to match', () => {
    expect(filterLessons(LESSONS, 'window session').map((l) => l.id)).toContain('hop');
    expect(filterLessons(LESSONS, 'window unicorn')).toEqual([]);
  });
});

describe('groupLessons', () => {
  it('keeps the pedagogical order of the groups', () => {
    const groups = groupLessons(LESSONS).map((s) => s.group);
    expect(groups).toEqual(LESSON_GROUPS.filter((g) => groups.includes(g)));
  });

  it('drops groups a filter emptied', () => {
    const sections = groupLessons(filterLessons(LESSONS, 'xpath'));
    expect(sections).toHaveLength(1);
    expect(sections[0].items.map((l) => l.id)).toEqual(['xml']);
  });

  it('places every lesson in exactly one section', () => {
    const placed = groupLessons(LESSONS).flatMap((s) => s.items);
    expect(placed).toHaveLength(LESSONS.length);
  });
});

describe('numberSections', () => {
  it('numbers the whole path continuously, across group boundaries', () => {
    const steps = numberSections(LESSONS).flatMap((s) => s.items.map((i) => i.step));
    expect(steps).toEqual(LESSONS.map((_, i) => i + 1));
  });

  it('renumbers from 1 when a filter narrows the path', () => {
    const sections = numberSections(filterLessons(LESSONS, 'window'));
    expect(sections[0].items[0].step).toBe(1);
  });

  it('keeps each lesson with its own number', () => {
    const pairs = numberSections(LESSONS).flatMap((s) => s.items);
    expect(pairs.find((p) => p.lesson.id === 'read')?.step).toBe(1);
    expect(new Set(pairs.map((p) => p.step)).size).toBe(LESSONS.length);
  });
});

/**
 * Une page d'aide se périme en silence : ces tests-là ne vérifient pas du code,
 * ils vérifient que les exemples restent exécutables par l'application.
 */
describe('lesson content', () => {
  it('has unique ids and a declared group', () => {
    const ids = LESSONS.map((l) => l.id);
    expect(new Set(ids).size).toBe(ids.length);
    LESSONS.forEach((l) => expect(LESSON_GROUPS).toContain(l.group));
  });

  it('only shows statements the backend whitelist accepts', () => {
    LESSONS.filter((l) => l.runnable).forEach((lesson) => {
      expect(lesson.sql.trimStart()).toMatch(/^(SELECT|WITH|CREATE TABLE|EXPLAIN)\b/);
    });
  });

  it('marks INSERT INTO as not runnable from the editor', () => {
    LESSONS.forEach((lesson) => {
      if (/^\s*INSERT INTO\b/i.test(lesson.sql)) expect(lesson.runnable).toBe(false);
    });
  });

  it('names tables in their sanitized form — a dotted name would not resolve', () => {
    LESSONS.forEach((lesson) => {
      const references = [...lesson.sql.matchAll(/\bFROM\s+(?:TABLE\s*\(\s*\w+\s*\(\s*TABLE\s+)?([\w.-]+)/gi)];
      references.forEach(([, name]) => {
        expect(`${lesson.id}: ${name}`).toBe(`${lesson.id}: ${topicToTable(name)}`);
      });
    });
  });

  it('explains each lesson and teaches at least one thing per step', () => {
    LESSONS.forEach((lesson) => {
      expect(lesson.goal.length).toBeGreaterThan(0);
      expect(lesson.reading.length).toBeGreaterThanOrEqual(2);
    });
  });
});

describe('reference tables', () => {
  it('describes both engines for every capability', () => {
    ENGINE_MATRIX.forEach((row) => {
      expect(row.feature).not.toHaveLength(0);
      expect(row.note).not.toHaveLength(0);
    });
  });

  it('never claims the fallback reader runs a join, a sub-query or a CTE', () => {
    const joins = ENGINE_MATRIX.find((r) => r.feature.includes('JOIN'));
    expect(joins?.direct).toBe(false);
  });

  it('gives every error entry a meaning and a fix', () => {
    ERROR_GUIDE.forEach((entry) => {
      expect(entry.meaning).not.toHaveLength(0);
      expect(entry.fix).not.toHaveLength(0);
    });
  });

  it('carries editor tips, window kinds and formats', () => {
    expect(EDITOR_TIPS.length).toBeGreaterThan(0);
    expect(WINDOW_KINDS.map((w) => w.name)).toEqual(['TUMBLE', 'HOP', 'CUMULATE', 'SESSION']);
    expect(FORMATS.map((f) => f.name)).toContain('AVRO');
  });
});
