// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/** An explicitly bounded subject listing, including the number before truncation. */
public record RegistrySubjects(List<String> subjects, int total, boolean truncated) {}
