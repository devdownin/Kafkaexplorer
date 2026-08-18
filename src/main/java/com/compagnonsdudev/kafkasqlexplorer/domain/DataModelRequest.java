// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * Body of {@code POST /api/data-model}: the topics to build the model from. A boxed list —
 * Jackson binds records through the canonical constructor, so an absent property arrives as
 * {@code null} and the controller must be able to answer 400 with a reason rather than fail
 * the binding.
 */
public record DataModelRequest(List<String> topics) {
}
