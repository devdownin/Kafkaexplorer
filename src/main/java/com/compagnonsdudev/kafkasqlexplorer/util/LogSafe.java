// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.util;

/**
 * Neutralise ce qui n'a rien à faire dans une ligne de journal : un {@code %0A} dans une valeur
 * influencée par l'appelant forge la ligne qu'il veut dans le fichier censé être le compte rendu
 * de ce qui s'est passé. {@code logs/kafkaexplorer.log} est un volume nommé dans chaque pile et le
 * premier fichier que quiconque colle dans un rapport de bug.
 *
 * <p>Trois fonctions et non une, parce que ce qui est journalisé ici n'a pas un seul alphabet
 * légal, et qu'appliquer le plus strict à tout détruirait précisément les lignes qu'on écrit pour
 * diagnostiquer. Le choix se fait sur ce que la valeur <em>est</em>, pas sur ce qui ferait taire un
 * contrôle :
 *
 * <ul>
 *   <li>{@link #name(String)} — un nom de topic Kafka, de table Flink, un identifiant de session,
 *       d'audit, de mapping. Alphabet légal {@code [a-zA-Z0-9._-]}, donc tout le reste est déjà
 *       anormal et rien de légitime n'est remplacé.</li>
 *   <li>{@link #slug(String)} — un identifiant de modèle ou un libellé de fournisseur, qui
 *       viennent de la configuration et portent légitimement {@code /} et {@code :}
 *       ({@code openai/gpt-4o-mini}). Les journaliser avec {@link #name} donnerait
 *       {@code openai_gpt-4o-mini}, c'est-à-dire pas ce que l'opérateur a saisi — une valeur
 *       méconnaissable dans la ligne qui existe pour la lui montrer.</li>
 *   <li>{@link #text(String)} — du texte libre : une requête SQL, un message d'exception. Aucune
 *       liste blanche n'est applicable, l'ensemble des caractères légaux étant l'ensemble des
 *       caractères.</li>
 * </ul>
 *
 * <p><b>Ce que CodeQL en reconnaît, et ce qu'il n'en reconnaît pas.</b> {@code name} et
 * {@code slug} sont écrits en {@code String.replaceAll} avec une classe niée, la seule forme que
 * {@code java/log-injection} admet comme barrière — un {@link java.util.regex.Pattern} hoisté sort
 * du modèle, {@code Matcher.replaceAll} n'étant pas déclaré sur {@code String}. La recompilation du
 * motif à chaque appel est le prix de cette reconnaissance, et il est payé sur des chemins qui
 * tournent une fois par enregistrement de table ou par appel au modèle, jamais par enregistrement
 * Kafka.
 *
 * <p>{@code text} n'est <b>pas</b> reconnue, et c'est un arbitrage assumé plutôt qu'un oubli : elle
 * retire au message exactement ce qu'il faut pour forger une ligne — les caractères de contrôle,
 * saut de ligne compris — et lui laisse tout le reste. Passer une requête SQL par {@link #name}
 * satisferait le contrôle et rendrait la ligne illisible, ce qui reviendrait à retirer un
 * comportement voulu pour faire taire un outil. Les constats qui subsistent sur ces appels-là sont
 * donc connus et décrits, pas ignorés.
 */
public final class LogSafe {

    private LogSafe() {}

    /** Un nom de topic, de table ou un identifiant : {@code [a-zA-Z0-9._-]}, le reste en {@code _}. */
    public static String name(String value) {
        return value == null ? null : value.replaceAll("[^\\w.\\-]", "_");
    }

    /**
     * Un identifiant de modèle ou un libellé de fournisseur : comme {@link #name}, plus {@code /}
     * et {@code :}, qu'aucun des deux ne peut utiliser pour forger une ligne.
     */
    public static String slug(String value) {
        return value == null ? null : value.replaceAll("[^\\w.\\-/:]", "_");
    }

    /**
     * Une liste de noms, chacun passé par {@link #name}.
     *
     * <p>Elle existe parce qu'une liste journalisée telle quelle l'est par son {@code toString()},
     * qui recopie ses éléments : un saut de ligne dans l'un d'eux forge une ligne aussi sûrement
     * que s'il avait été journalisé seul. C'est le cas exact que CodeQL a trouvé sur les deux
     * lignes « Starting live session » — dont l'identifiant de session est un UUID frappé deux
     * lignes plus haut, tandis que la liste de topics arrive d'un {@code @RequestParam}. Assainir
     * l'argument qui ne pouvait rien porter et laisser l'autre est le genre de correction qui
     * ressemble à une défense et n'en est pas une.
     *
     * <p>Rend une liste et non une chaîne : le formateur de journalisation garde ainsi son propre
     * rendu, et l'appel reste un argument parmi d'autres.
     */
    public static java.util.List<String> names(java.util.Collection<String> values) {
        return values == null ? null : values.stream().map(LogSafe::name).toList();
    }

    /**
     * Du texte libre dont la lisibilité est le point — une requête SQL, un message d'exception.
     * Les caractères de contrôle deviennent une espace, tout le reste passe.
     *
     * <p>Une espace et non {@code _} : sur un message d'exception multi-ligne, {@code _} colle les
     * mots de part et d'autre du saut de ligne, tandis que l'espace donne la phrase qu'un lecteur
     * attend. Les espaces consécutives ne sont pas repliées — une requête SQL indentée doit rester
     * reconnaissable, et le repliement coûterait un second parcours pour un gain cosmétique.
     */
    public static String text(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", " ");
    }
}
