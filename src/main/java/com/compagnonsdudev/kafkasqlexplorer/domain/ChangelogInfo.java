// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Ce qu'un résultat de <em>changelog</em> contient réellement, quand il en est un.
 *
 * <p>Une requête « mise à jour » — une agrégation, une jointure externe, une sous-requête
 * scalaire — ne rend pas des lignes, elle rend une suite de <em>corrections</em> : Flink émet
 * {@code +I(1)}, puis {@code -U(1)} et {@code +U(2)} pour dire « ce que je vous ai donné n'est
 * plus vrai, voici la nouvelle valeur ». Mesuré sur trois lignes en entrée : un
 * {@code SELECT COUNT(*)} en rend cinq, une {@code FULL OUTER JOIN} 3 × 2 en rend sept.
 *
 * <p>{@code Row.getKind()} était lu — journalisé en DEBUG pour la première ligne — puis jeté, si
 * bien que la grille présentait ces corrections comme des résultats : un opérateur lisant un
 * {@code COUNT(*)} voyait cinq lignes et devait deviner que seule la dernière comptait.
 * {@code MetricService} avait déjà tiré la leçon (« la valeur est la dernière ligne numérique ») ;
 * l'éditeur, jamais.
 *
 * <p>Le parti pris est le marqueur plutôt que le repliement côté serveur : replier, c'est décider
 * à la place de l'appelant quelle ligne compte, alors que le marqueur ne ment sur rien — il rend
 * exactement ce que le moteur a émis, en disant ce que chaque ligne est.
 *
 * @param rowsReturned   nombre total de lignes rendues, corrections comprises
 * @param corrections    lignes dont le {@code RowKind} n'est pas {@code +I} — retraits
 *                       ({@code -U}, {@code -D}) et remplacements ({@code +U})
 * @param retractions    lignes qui <em>retirent</em> une ligne précédente ({@code -U}, {@code -D})
 * @param capReached     le plafond de lignes a été atteint, donc la suite des corrections est
 *                       coupée et la dernière ligne n'est pas nécessairement l'état final
 */
public record ChangelogInfo(
    int rowsReturned,
    int corrections,
    int retractions,
    boolean capReached
) {
    /** Le nom réservé sous lequel chaque ligne porte son {@code RowKind}. */
    public static final String ROW_KIND_KEY = "__row_kind";
}
