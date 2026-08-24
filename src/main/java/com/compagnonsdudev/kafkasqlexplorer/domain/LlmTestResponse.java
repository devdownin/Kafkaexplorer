// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * {@code POST /api/config/test-llm} — le test de joignabilité du fournisseur LLM.
 *
 * <p>Le typer a fait apparaître une dérive que rien ne pouvait voir : la réponse porte
 * {@code provider} et {@code model} depuis toujours, et l'interface TypeScript qui prétendait la
 * décrire ne les déclarait pas. C'est le genre d'écart que {@code check-api-types.py} refuse dans
 * les deux sens, et il ne pouvait pas le faire ici faute de record en face.
 *
 * <p>{@code candidate} distingue une saisie du formulaire, pas encore appliquée, de la
 * configuration en vigueur : « joignable » ne dit pas la même chose des deux. {@code modelCheck}
 * n'est renseigné que par les fournisseurs qui publient les capacités d'un modèle — son absence
 * ne dit donc rien du modèle, elle dit qu'on n'a pas demandé.
 */
public record LlmTestResponse(
    boolean ok,
    String message,
    String provider,
    String model,
    boolean candidate,
    LlmModelCheck modelCheck
) {}
