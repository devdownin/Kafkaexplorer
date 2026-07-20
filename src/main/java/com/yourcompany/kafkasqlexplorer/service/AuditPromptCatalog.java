// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.AuditPrompt;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Curated library of ready-to-use audit prompts for Kafka message exchanges.
 *
 * <p>Each entry is a focused instruction the LLM applies on top of the standard Process
 * Mining analysis. The UI exposes this list so an operator can compose an audit by ticking
 * the checks that matter, rather than writing a prompt from scratch.
 */
@Service
public class AuditPromptCatalog {

    private final Map<String, AuditPrompt> byId = new LinkedHashMap<>();

    public AuditPromptCatalog() {
        register(new AuditPrompt(
                "ordering",
                "Ordering & sequencing",
                "SEQUENCE",
                "Out-of-order events and missing steps in the expected process order.",
                "Vérifie l'ordre des événements par correlation id : détecte les messages hors séquence, "
                        + "les étapes manquantes ou intercalées, et les flux qui ne respectent pas l'ordre "
                        + "métier attendu. Signale chaque rupture en type SEQUENCE."));

        register(new AuditPrompt(
                "duplicates",
                "Duplicate messages",
                "DATA_QUALITY",
                "Same event emitted or reprocessed more than once.",
                "Détecte les messages dupliqués (même correlation id, même clé ou payload identique) "
                        + "émis ou retraités plusieurs fois. Indique les offsets concernés et classe l'anomalie "
                        + "en type CARDINALITY."));

        register(new AuditPrompt(
                "orphans",
                "Orphan / incomplete flows",
                "SEQUENCE",
                "Flows that start but never reach a terminal state, or reference an unseen event.",
                "Identifie les correlation ids qui démarrent un flux mais n'atteignent jamais un état "
                        + "terminal, ainsi que les événements référençant un message amont non observé. "
                        + "Classe en type SEQUENCE et précise l'étape où le flux s'interrompt."));

        register(new AuditPrompt(
                "latency",
                "Latency & SLA breaches",
                "TEMPORAL",
                "Abnormal delays between correlated steps or SLA violations.",
                "Analyse les délais entre étapes corrélées : signale les latences anormales, les temps "
                        + "d'attente excessifs et les dépassements de SLA apparents. Classe en type TEMPORAL et "
                        + "donne l'écart temporel constaté."));

        register(new AuditPrompt(
                "throughput",
                "Throughput & silence windows",
                "TEMPORAL",
                "Bursts, sudden drops, or unexpected silence per topic.",
                "Évalue le débit par topic dans le temps : détecte les pics soudains, les chutes de "
                        + "volume et les fenêtres de silence inattendues. Classe en type TEMPORAL."));

        register(new AuditPrompt(
                "schema-drift",
                "Schema drift",
                "DATA_QUALITY",
                "Inconsistent field presence or types across messages of a topic.",
                "Compare la structure des messages d'un même topic : détecte les champs apparaissant/"
                        + "disparaissant, les types incohérents et les changements de schéma implicites. "
                        + "Classe en type STRUCTURAL."));

        register(new AuditPrompt(
                "required-fields",
                "Missing required fields",
                "DATA_QUALITY",
                "Messages missing correlation id, timestamp or status.",
                "Vérifie la présence des champs obligatoires (correlation id, timestamp, statut) d'après "
                        + "le mapping fourni. Signale tout message où ils sont absents, nuls ou vides. "
                        + "Classe en type STRUCTURAL."));

        register(new AuditPrompt(
                "status-transitions",
                "Invalid status transitions",
                "BUSINESS",
                "Illegal state changes (e.g. CANCELLED then SHIPPED).",
                "À partir du champ statut, reconstitue les transitions d'état par correlation id et "
                        + "détecte les transitions invalides ou impossibles (ex. CANCELLED → SHIPPED). "
                        + "Classe en type BUSINESS."));

        register(new AuditPrompt(
                "error-retries",
                "Error states & retries",
                "RELIABILITY",
                "Failed statuses, repeated retries and dead-letter patterns.",
                "Repère les statuts d'erreur/échec, les retries répétés sur le même correlation id et les "
                        + "schémas de type dead-letter. Classe en type BUSINESS et estime le taux d'erreur."));

        register(new AuditPrompt(
                "amount-outliers",
                "Amount outliers",
                "BUSINESS",
                "Outlier, negative or spiking monetary amounts.",
                "Analyse les champs de montant : détecte les valeurs aberrantes, négatives ou anormalement "
                        + "élevées par rapport à la distribution observée. Classe en type BUSINESS."));

        register(new AuditPrompt(
                "pii-leakage",
                "Sensitive data (PII) exposure",
                "SECURITY",
                "Plaintext emails, card numbers, phone numbers or IBANs in payloads.",
                "Recherche des données personnelles ou sensibles en clair dans les payloads (emails, "
                        + "numéros de carte, téléphones, IBAN, identifiants nationaux). Ne recopie jamais la "
                        + "valeur sensible en clair : indique seulement le champ et le type de donnée. "
                        + "Classe en type STRUCTURAL avec sévérité CRITICAL."));

        register(new AuditPrompt(
                "correlation-integrity",
                "Correlation integrity",
                "SEQUENCE",
                "Inconsistent fan-out or mismatched joins across topics.",
                "Vérifie l'intégrité de corrélation entre topics : détecte les correlation ids dont la "
                        + "propagation est incohérente (fan-out partiel, jointures dépareillées, id présent "
                        + "sur un topic mais absent d'un topic attendu). Classe en type SEQUENCE."));
    }

    private void register(AuditPrompt prompt) {
        byId.put(prompt.id(), prompt);
    }

    /** All audit prompts, in catalog (display) order. */
    public List<AuditPrompt> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Resolves the given ids to their audit prompts, preserving catalog order and skipping
     * unknown ids. Returns an empty list when {@code ids} is null or empty.
     */
    public List<AuditPrompt> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return byId.values().stream()
                .filter(p -> ids.contains(p.id()))
                .collect(Collectors.toList());
    }
}
