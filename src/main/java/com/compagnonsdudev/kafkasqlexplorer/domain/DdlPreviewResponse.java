// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * {@code GET /api/query/ddl-preview} — le DDL généré, ou la raison pour laquelle il ne l'a pas été.
 *
 * <p>Exactement l'un des deux est renseigné. Servi en {@code Map.of} jusqu'ici, donc invérifiable :
 * {@code check-api-types.py} résout les interfaces du front contre les records de ce paquet, et
 * une forme qui n'en a pas est une affirmation écrite à la main que rien ne relit — le défaut
 * exact qui a tué la page Compare des mois après le changement qui l'a causé.
 */
public record DdlPreviewResponse(
    String ddl,
    String error
) {
    public static DdlPreviewResponse of(String ddl) {
        return new DdlPreviewResponse(ddl, null);
    }

    public static DdlPreviewResponse failed(String error) {
        return new DdlPreviewResponse(null, error);
    }
}
