// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

public record FieldProfileResult(
    List<TopicProfile> topics,
    SchemaUnificationProposal unificationProposal,
    List<String> warnings
) {}
