// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/** {@code POST /api/process-mining/profiling/validate} — l'identifiant du mapping retenu. */
public record FieldMappingValidation(
    String fieldMappingId
) {}
