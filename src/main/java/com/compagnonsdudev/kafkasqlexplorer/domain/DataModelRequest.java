// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Body of {@code POST /api/data-model}: the topics to build the model from, and how many of them
 * this run may analyse. Both boxed — Jackson binds records through the canonical constructor, so
 * an absent property arrives as {@code null}, and a primitive {@code int} would fail the whole
 * request with "Cannot map null into type int" on a body as small as
 * {@code {"topics": ["a"]}}. The controller must be able to answer 400 with a reason rather than
 * fail the binding.
 *
 * @param topics    the topics to model
 * @param maxTopics how many to analyse, or {@code null} for the server's default. The server
 *                  clamps it to {@code explorer.data-model-max-topics} and says so in the
 *                  warnings — the caller chooses within a bound it does not get to raise.
 */
public record DataModelRequest(List<String> topics, Integer maxTopics) {
}
