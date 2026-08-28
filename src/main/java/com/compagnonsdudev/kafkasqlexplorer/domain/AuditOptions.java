// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Controls which checks are executed during an audit run, and optionally
 * restricts the audit to topics whose name starts with {@code topicPrefix}.
 * Defaults (via {@link #all()}) enable every check over all topics.
 */
public record AuditOptions(
    boolean checkSchema,
    boolean checkPoisonMessages,
    boolean checkDuplicates,
    boolean checkFlows,
    boolean checkExactCount,
    /**
     * Reads each topic's consumer groups and reports the ones nothing will drain. Costs several
     * coordinator round trips per topic, hence its own switch — but it is the check that answers
     * "why is this piling up?", which none of the others could.
     */
    boolean checkConsumerLag,
    String topicPrefix
) {
    /**
     * Binds a body that names only some of the checks — or none of them.
     *
     * <p>Jackson binds a record through its canonical constructor, so an absent property arrives as
     * {@code null} and a primitive component fails the <em>whole</em> request with a parse error
     * naming a Jackson internal. {@code POST /api/audit/start} declares
     * {@code @RequestBody(required = false)} precisely so a caller may send nothing, so accepting
     * no body while refusing <code>{}</code> was incoherent — and a body naming just a prefix is
     * the most natural thing to write by hand. Same defect, same fix as {@code StreamFlowRequest}.
     *
     * <p><b>An absent flag means the check runs</b>, which is what makes <code>{}</code> mean
     * exactly what sending no body means: {@link #all()}. Turning one off is then saying so.
     */
    @JsonCreator
    public AuditOptions(
        @JsonProperty("checkSchema") Boolean checkSchema,
        @JsonProperty("checkPoisonMessages") Boolean checkPoisonMessages,
        @JsonProperty("checkDuplicates") Boolean checkDuplicates,
        @JsonProperty("checkFlows") Boolean checkFlows,
        @JsonProperty("checkExactCount") Boolean checkExactCount,
        @JsonProperty("checkConsumerLag") Boolean checkConsumerLag,
        @JsonProperty("topicPrefix") String topicPrefix
    ) {
        this(checkSchema == null || checkSchema,
             checkPoisonMessages == null || checkPoisonMessages,
             checkDuplicates == null || checkDuplicates,
             checkFlows == null || checkFlows,
             checkExactCount == null || checkExactCount,
             checkConsumerLag == null || checkConsumerLag,
             topicPrefix);
    }

    public static AuditOptions all() {
        return new AuditOptions(true, true, true, true, true, true, null);
    }

    /** Trimmed prefix, or {@code null} when no topic filter should apply. */
    public String normalizedPrefix() {
        return (topicPrefix == null || topicPrefix.isBlank()) ? null : topicPrefix.trim();
    }
}
