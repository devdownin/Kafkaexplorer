// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * {@code POST /api/assistant/sql}.
 *
 * @param mode     {@code GENERATE} (a question becomes a SELECT) or {@code FIX} (a refused
 *                 statement and the planner's message become a corrected one)
 * @param question the request in natural language; required for {@code GENERATE}
 * @param sql      the statement to fix; required for {@code FIX}
 * @param error    the planner's message about {@code sql}, as the editor displayed it
 * @param tables   tables the user put in scope explicitly; they are described first
 */
public record AssistantSqlRequest(
    String mode,
    String question,
    String sql,
    String error,
    List<String> tables
) {
}
