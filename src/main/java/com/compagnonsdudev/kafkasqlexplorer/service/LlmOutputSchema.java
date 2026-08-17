// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.util.Map;

/**
 * A JSON Schema the answer must conform to, plus the name providers want alongside it.
 *
 * <p>Passing one is a request, not a guarantee: a client that cannot constrain its provider ignores
 * it and the caller still parses the text as before. That is why the prompt keeps asking for JSON
 * even when a schema is sent — the schema is the belt, the prompt is the braces, and the SpectraLLM
 * path has only the braces.
 *
 * @param name   identifier for the shape, sent by OpenAI-compatible endpoints
 * @param schema the JSON Schema as a plain map, rendered to JSON by each client
 */
public record LlmOutputSchema(String name, Map<String, Object> schema) {
}
