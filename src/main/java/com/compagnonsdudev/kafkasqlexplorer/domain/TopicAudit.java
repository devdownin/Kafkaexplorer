// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * @param healthStatus the worst severity among {@code issues}, HEALTHY when there are none
 */
public record TopicAudit(
    String name,
    long messageCount,
    MessageFormat format,
    int poisonMessageCount,
    long duplicateCount,
    HealthStatus healthStatus,
    List<TopicIssue> issues
) {}
