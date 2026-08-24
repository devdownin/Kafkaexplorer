// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Un refus, avec sa raison. La forme que le navigateur lit déjà : {@code extractApiErrorMessage}
 * cherche {@code message} puis {@code error} dans le corps de la réponse, donc le fil est inchangé.
 *
 * <p>Servie en {@code Map.of("error", …)} jusqu'ici, ce qui portait un piège que {@code ddl-preview}
 * a dû faire corriger un contrôleur plus loin : {@code Map.of} refuse une valeur nulle et
 * {@code e.getMessage()} est nul sur une {@code NullPointerException} — donc le chemin d'erreur
 * répondait 500 sans corps précisément dans le cas où l'appelant a le plus besoin d'une raison.
 * Un record accepte le nul, ce qui déplace la question au lieu de la résoudre : c'est pourquoi
 * {@link #of} passe par {@code SqlErrorClassifier.explain}, documenté pour ne jamais rendre ni nul
 * ni blanc, et pour aplatir la chaîne des causes où le texte utile se trouve d'ordinaire.
 */
public record ApiError(
    String error
) {
    public static ApiError of(Throwable t) {
        return new ApiError(com.compagnonsdudev.kafkasqlexplorer.service.SqlErrorClassifier.explain(t));
    }

    public static ApiError of(String message) {
        return new ApiError(message == null || message.isBlank() ? "Request failed" : message);
    }
}
