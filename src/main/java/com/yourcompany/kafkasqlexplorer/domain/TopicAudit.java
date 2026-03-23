// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record TopicAudit(
    String name,
    long messageCount,
    MessageFormat format,
    int poisonMessageCount,
    long duplicateCount,
    HealthStatus healthStatus,
    List<String> issues
) {}
