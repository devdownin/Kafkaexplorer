// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

export type Separator = '.' | '-' | '_';

export interface TopicBranch {
  label: string;
  path: string;
  topic: string | null;
  count: number;
  children: TopicBranch[];
}

export type ExclusionReason = 'No separator' | 'Mixed separators' | 'Empty level';

export function exclusionReason(name: string): ExclusionReason | null {
  const separators = (['.', '-', '_'] as const).filter(separator => name.includes(separator));
  if (separators.length === 0) return 'No separator';
  if (separators.length > 1) return 'Mixed separators';
  return name.split(separators[0]).every(part => part.length > 0) ? null : 'Empty level';
}

export interface VisibleBranch {
  branch: TopicBranch;
  depth: number;
  parent: string | null;
  position: number;
  siblings: number;
}

export function visibleBranches(roots: TopicBranch[], expanded: ReadonlySet<string>, expandAll = false): VisibleBranch[] {
  const rows: VisibleBranch[] = [];
  const visit = (siblings: TopicBranch[], depth: number, parent: string | null) => {
    siblings.forEach((branch, index) => {
      rows.push({ branch, depth, parent, position: index + 1, siblings: siblings.length });
      if (branch.children.length && (expandAll || expanded.has(branch.path))) {
        visit(branch.children, depth + 1, branch.path);
      }
    });
  };
  visit(roots, 1, null);
  return rows;
}

interface MutableBranch {
  label: string;
  path: string;
  topic: string | null;
  count: number;
  children: Map<string, MutableBranch>;
}

export function topicSeparator(name: string): Separator | null {
  if (exclusionReason(name)) return null;
  return (['.', '-', '_'] as const).find(separator => name.includes(separator)) ?? null;
}

export function buildTopicHierarchy(topics: string[], separator: Separator): TopicBranch[] {
  const roots = new Map<string, MutableBranch>();
  for (const topic of topics) {
    if (topicSeparator(topic) !== separator) continue;
    const parts = topic.split(separator);
    let siblings = roots;
    let path = '';
    for (const part of parts) {
      path = path ? `${path}${separator}${part}` : part;
      let branch = siblings.get(part);
      if (!branch) {
        branch = { label: part, path, topic: null, count: 0, children: new Map() };
        siblings.set(part, branch);
      }
      branch.count += 1;
      if (path === topic) branch.topic = topic;
      siblings = branch.children;
    }
  }
  const freeze = (nodes: Map<string, MutableBranch>): TopicBranch[] =>
    [...nodes.values()].sort((a, b) => a.label.localeCompare(b.label)).map(node => ({
      label: node.label, path: node.path, topic: node.topic, count: node.count,
      children: freeze(node.children),
    }));
  return freeze(roots);
}
