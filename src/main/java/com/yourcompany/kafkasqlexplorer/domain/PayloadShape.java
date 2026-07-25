// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The structure of a payload, independent of its values: the set of leaf paths with array
 * indices collapsed to {@code []}. Messages that share a structure share a shape, so a window
 * of a thousand 1 MB documents contributes a handful of shapes to the prompt instead of a
 * thousand copies of the same deeply nested skeleton.
 *
 * @param id        stable hash of the path set — the same structure always yields the same id
 * @param format    JSON / XML / TEXT
 * @param depth     deepest nesting level observed
 * @param paths     leaf paths, document order, capped by {@code process-mining.max-shape-paths}
 * @param truncated true when the walk hit a cap (depth, path count, parse events) so the path list is partial
 */
public record PayloadShape(
    String id,
    String format,
    int depth,
    List<String> paths,
    boolean truncated
) {
    /** Renders the shape for an LLM prompt, showing at most {@code maxPaths} leaf paths. */
    public String toPromptBlock(int maxPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(id)
          .append(" (format=").append(format)
          .append(", depth=").append(depth)
          .append(", leaves=").append(paths.size())
          .append(truncated ? ", partial" : "")
          .append("): ");
        int shown = Math.min(maxPaths, paths.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paths.get(i));
        }
        if (shown < paths.size()) {
            sb.append(", … +").append(paths.size() - shown).append(" autres chemins");
        }
        return sb.append("\n").toString();
    }
}
