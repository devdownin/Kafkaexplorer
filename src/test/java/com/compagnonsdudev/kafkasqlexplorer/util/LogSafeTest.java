// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ce que chacune des trois fonctions promet, et surtout ce qu'elle laisse passer — c'est cette
 * moitié-là qui se perd quand on durcit un assainissement pour faire taire un contrôle.
 *
 * <p>Le test vivait dans {@code FlinkSqlServiceTest} du temps où la fonction était une méthode du
 * moteur SQL, alors que {@code OpenRouterModelCatalog} l'appelait déjà à travers lui.
 */
class LogSafeTest {

    @Test
    void nameNeutralisesEverythingOutsideATopicName() {
        assertEquals("a_b", LogSafe.name("a\nb"), "un saut de ligne forge une ligne de log");
        assertEquals("a_b", LogSafe.name("a\rb"));
        assertEquals("a_b", LogSafe.name("a\tb"));
        assertEquals("a_b", LogSafe.name("a\0b"));
        assertEquals("a_b", LogSafe.name("a\177b"));
        assertEquals("__", LogSafe.name("\r\n"), "CRLF : deux caractères, deux remplacements");
        // Ce qu'un nom de topic Kafka ou de table Flink porte réellement traverse intact :
        // l'alphabet légal d'un nom de topic est exactement celui que la liste blanche autorise.
        assertEquals("demo_orders_1_received", LogSafe.name("demo_orders_1_received"));
        assertEquals("demo.orders-1", LogSafe.name("demo.orders-1"));
        // Liste blanche : tout le reste tombe, y compris ce qu'une liste noire de caractères de
        // contrôle laissait passer. Ce n'est pas un dommage collatéral — un nom de topic ne peut
        // pas contenir ces caractères, donc en voir un ici veut dire qu'on ne journalise pas ce
        // qu'on croit, et l'échapper est la bonne réponse.
        assertEquals("____", LogSafe.name("é àü"), "hors de [a-zA-Z0-9._-], donc hors d'un nom légal");
        assertEquals("a_b", LogSafe.name("a b"), "l'espace non plus n'est pas légal");
        assertNull(LogSafe.name(null));
    }

    @Test
    void slugKeepsWhatAModelIdentifierIsMadeOf() {
        // La raison d'être de cette seconde fonction : `name` rendrait `openai_gpt-4o-mini`, donc
        // pas ce que l'opérateur a saisi, dans la ligne qui existe pour le lui montrer.
        assertEquals("openai/gpt-4o-mini", LogSafe.slug("openai/gpt-4o-mini"));
        assertEquals("qwen3:4b", LogSafe.slug("qwen3:4b"));
        assertEquals("Anthropic", LogSafe.slug("Anthropic"));
        assertEquals("OpenAI-compatible", LogSafe.slug("OpenAI-compatible"));
        // Ce qui reste interdit est exactement ce qui forge une ligne.
        assertEquals("a_b", LogSafe.slug("a\nb"));
        assertEquals("a_b", LogSafe.slug("a\rb"));
        assertEquals("a_b", LogSafe.slug("a b"), "un identifiant de modèle n'a pas d'espace");
        assertNull(LogSafe.slug(null));
    }

    @Test
    void textKeepsTheMessageAndLosesOnlyWhatForgesALine() {
        // Une requête SQL passée par `name` deviendrait illisible ; c'est tout l'intérêt de la
        // ligne qui la journalise, donc c'est ce qu'il ne faut pas détruire.
        assertEquals("SELECT * FROM t WHERE id = 'ORD-42' AND x > 3",
            LogSafe.text("SELECT * FROM t WHERE id = 'ORD-42' AND x > 3"));
        assertEquals("Object 'orders' not found; line 1, column 15",
            LogSafe.text("Object 'orders' not found; line 1, column 15"));
        // Une espace et non `_` : sur un message multi-ligne, `_` collerait les mots.
        assertEquals("ligne un ligne deux", LogSafe.text("ligne un\nligne deux"));
        assertEquals("a b", LogSafe.text("a\rb"));
        assertEquals("a b", LogSafe.text("a\0b"));
        assertEquals("  ", LogSafe.text("\r\n"));
        // Les espaces consécutives ne sont pas repliées : une requête indentée reste reconnaissable.
        assertEquals("SELECT   1", LogSafe.text("SELECT\t\t 1"));
        assertNull(LogSafe.text(null));
    }

    @Test
    void noFunctionEverLetsALineBreakThrough() {
        for (String forged : new String[] {
            "x\nWARN forged line", "x\r\nERROR forged", "x\013verticalTab", "x\fformFeed" }) {
            assertTrue(LogSafe.name(forged).indexOf('\n') < 0 && LogSafe.name(forged).indexOf('\r') < 0);
            assertTrue(LogSafe.slug(forged).indexOf('\n') < 0 && LogSafe.slug(forged).indexOf('\r') < 0);
            assertTrue(LogSafe.text(forged).indexOf('\n') < 0 && LogSafe.text(forged).indexOf('\r') < 0);
        }
    }

    @Test
    void namesSanitisesEachElementOfAList() {
        // Une liste journalisée telle quelle l'est par son toString(), qui recopie ses éléments :
        // un saut de ligne dans l'un d'eux forge une ligne comme s'il était journalisé seul.
        assertEquals(java.util.List.of("demo.orders-1", "a_b"),
            LogSafe.names(java.util.List.of("demo.orders-1", "a\nb")));
        assertEquals(java.util.List.of(), LogSafe.names(java.util.List.of()));
        assertNull(LogSafe.names(null));
        // Ce que la liste rend est ce qui part au journal : rien n'en sort avec un saut de ligne.
        assertTrue(LogSafe.names(java.util.List.of("x\r\nERROR forged")).toString().indexOf('\n') < 0);
    }
}
