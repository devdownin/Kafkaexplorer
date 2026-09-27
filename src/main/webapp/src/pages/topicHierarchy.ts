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

interface MutableBranch {
  label: string;
  path: string;
  topic: string | null;
  count: number;
  children: Map<string, MutableBranch>;
}

export function topicSeparator(name: string): Separator | null {
  const separators = (['.', '-', '_'] as const).filter(separator => name.includes(separator));
  if (separators.length !== 1) return null;
  const separator = separators[0];
  return name.split(separator).every(part => part.length > 0) ? separator : null;
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
