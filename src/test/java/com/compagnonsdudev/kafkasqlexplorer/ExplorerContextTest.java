// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer;

import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Un {@link SpringBootTest} qui n'écrit pas dans l'arbre de travail.
 *
 * <p>Les magasins sur fichier portent des chemins <em>relatifs</em> par défaut
 * ({@code data/flink-jobs.json}, {@code data/flink-tables.json}, {@code data/settings.json}), ce
 * qui est juste pour un conteneur dont le répertoire de travail est {@code /app} et faux pour une
 * suite de tests : là, « relatif » veut dire la racine du dépôt. Mesuré — la suite complète y
 * laissait un {@code data/flink-jobs.json} contenant la sonde de préchauffage, parce qu'un
 * contexte Spring démarre le vrai {@code FlinkWarmupService} et le vrai {@code FlinkSqlService}.
 * Ignoré par git, donc invisible, et c'est précisément ce qui le rend gênant : un test touche le
 * fichier qu'une application lancée à la main sur la même machine utilise.
 *
 * <p>Une annotation composée plutôt que cinq copies des mêmes trois lignes — cinq classes
 * démarrent un contexte, et une sixième oubliée réintroduirait le problème sans que rien ne le
 * dise. {@code ${java.io.tmpdir}} est résolu par Spring depuis les propriétés système, donc le
 * chemin est celui de la plateforme sans qu'aucun test n'ait à le calculer.
 *
 * <p>Ce qui n'est <em>pas</em> corrigé ici, faute d'exister : {@code logging.file.name}.
 * {@code logback-test.xml} ne déclare qu'un appender console, donc la suite n'écrit aucun fichier
 * de log — vérifié plutôt que supposé, le répertoire {@code logs/} observé pendant ce travail
 * venait d'un {@code spring-boot:run} lancé à la main.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
    "explorer.flink-table-store-path=${java.io.tmpdir}/kse-test-flink-tables.json",
    "explorer.settings-store-path=${java.io.tmpdir}/kse-test-settings.json",
})
public @interface ExplorerContextTest {
}
