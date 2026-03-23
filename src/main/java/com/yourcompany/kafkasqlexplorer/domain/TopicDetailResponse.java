// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record TopicDetailResponse(
    TopicDescriptor topic,
    MessageFormat format,
    Map<String, String> schema,
    String ddl,
    List<String> samples
) {}
