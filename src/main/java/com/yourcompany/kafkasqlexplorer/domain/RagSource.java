// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

/**
 * A retrieval-augmented-generation citation returned alongside an audit answer.
 *
 * <p>When the SpectraLLM provider runs with RAG enabled, its {@code /api/query} response
 * carries the corpus passages it grounded the answer on. Surfacing them turns the audit into
 * verifiable evidence rather than an opaque verdict.
 *
 * @param text       the cited passage (may be truncated for display)
 * @param sourceFile the document / origin the passage came from
 * @param score      best available relevance score (rerank, else BM25, else similarity); nullable
 */
public record RagSource(
        String text,
        String sourceFile,
        Double score
) {}
