// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record QueryInitResponse(
    List<String> topics,
    List<String> tables,
    boolean health
) {}
