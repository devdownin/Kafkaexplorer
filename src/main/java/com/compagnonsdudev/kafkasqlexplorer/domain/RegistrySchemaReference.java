// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/** A Confluent Schema Registry reference, for Avro, Protobuf and JSON schemas. */
public record RegistrySchemaReference(String name, String subject, int version) {}
