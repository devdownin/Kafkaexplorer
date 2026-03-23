// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public record KafkaMessage(
    String topic,
    int partition,
    long offset,
    long timestamp,
    String key,
    String value
) {}
