// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * {@code POST /api/query/validate} — la syntaxe seule ; le catalogue n'est pas consulté.
 *
 * <p>{@code error} est nul quand la requête est valide.
 */
public record SqlValidationResponse(
    boolean valid,
    String error
) {
    /*
     * `accepted` et non `valid` : un record expose déjà un accesseur `valid()`, et une fabrique
     * du même nom ne compile pas — le compilateur la refuse comme accesseur invalide.
     */
    public static SqlValidationResponse accepted() {
        return new SqlValidationResponse(true, null);
    }

    public static SqlValidationResponse rejected(String error) {
        return new SqlValidationResponse(false, error);
    }
}
