// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import lombok.Builder;

@Builder
public record QueryRequest(
    String sql,
    String topic,
    Integer maxRows,
    Long timeout,
    String readMode
) {}
