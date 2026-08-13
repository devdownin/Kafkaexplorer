// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * A predefined, reusable audit checklist item expressed as an LLM instruction.
 *
 * <p>The catalog of these is offered in the Process Mining UI so an operator can pick
 * which audits to run against a Kafka message exchange, instead of hand-writing a prompt.
 * The {@link #prompt()} text is injected into the analysis prompt to steer the LLM toward
 * that specific audit goal while keeping the standard anomaly/flowchart output.
 *
 * @param id            stable identifier used by the API and the UI selection
 * @param name          short human-readable title
 * @param category      grouping key (SEQUENCE, TEMPORAL, DATA_QUALITY, BUSINESS, RELIABILITY, SECURITY)
 * @param description   one-line explanation shown under the title in the picker
 * @param prompt        the instruction handed to the LLM for this audit
 * @param requiredRoles semantic field roles the audit needs to be meaningful
 *                      (CORRELATION_ID, TIMESTAMP, STATUS, AMOUNT); empty = always applicable.
 *                      The UI greys an audit out when profiling did not detect a required role.
 */
public record AuditPrompt(
        String id,
        String name,
        String category,
        String description,
        String prompt,
        List<String> requiredRoles
) {
    public AuditPrompt {
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
    }
}
