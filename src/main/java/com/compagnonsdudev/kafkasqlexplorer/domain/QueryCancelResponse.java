// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;

/**
 * {@code POST /api/query/cancel/{queryId}} — ce qui a réellement été annulé.
 *
 * <p>{@code cancelled: false} est un résultat normal et non un échec : un scan
 * {@code KAFKA_DIRECT} n'a aucun job Flink à annuler, et l'appel reste bien formé. C'est
 * précisément la distinction pour laquelle {@code CancelOutcome} existe — un booléen avait deux
 * états pour trois issues — et la porter jusqu'au navigateur est ce qui lui permet de dire
 * « requête abandonnée » plutôt que « annulée ».
 */
public record QueryCancelResponse(
    boolean cancelled,
    String outcome
) {
    public static QueryCancelResponse of(FlinkSqlService.CancelOutcome outcome) {
        return new QueryCancelResponse(
            outcome == FlinkSqlService.CancelOutcome.CANCELLED, outcome.name());
    }
}
