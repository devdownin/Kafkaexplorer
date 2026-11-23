// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/** Candidate tested against one named baseline under the subject's effective policy. */
public record RegistryCompatibility(boolean compatible, int baselineVersion, int candidateVersion,
                                    String level, List<String> messages) {}
