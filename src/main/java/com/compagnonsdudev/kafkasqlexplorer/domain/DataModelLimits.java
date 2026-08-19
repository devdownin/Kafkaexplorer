// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * What {@code GET /api/data-model/limits} answers: the bounds the Data Model page has to respect.
 *
 * <p>It exists so the browser stops carrying its own copy of the number. The page used to declare
 * {@code MAX_TOPICS = 30} beside the Java constant of the same value — two hand-kept copies of one
 * rule, which is the drift this codebase keeps removing. Now the ceiling is configurable
 * ({@code explorer.data-model-max-topics}) and the page reads it, so raising it in the deployment
 * raises what the UI offers, with nothing to edit twice.
 *
 * @param maxTopics        the hard ceiling one request may analyse
 * @param defaultMaxTopics what a request analyses when it does not ask for a number
 */
public record DataModelLimits(int maxTopics, int defaultMaxTopics) {
}
