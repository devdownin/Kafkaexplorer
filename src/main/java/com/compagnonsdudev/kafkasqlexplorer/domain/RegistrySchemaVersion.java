// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/** A persisted schema version, not a client-supplied schema. */
public record RegistrySchemaVersion(String subject, int version, int id, String schemaType,
                                    String schema, List<RegistrySchemaReference> references) {}
