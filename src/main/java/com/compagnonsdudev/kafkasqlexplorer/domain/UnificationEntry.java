// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record UnificationEntry(
    String canonicalName,
    Map<String, String> mappings,
    double confidence,
    List<String> conflicts
) {}
