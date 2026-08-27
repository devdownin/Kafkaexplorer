// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * One KPI the model would follow, out of the candidates it was shown.
 *
 * <p>Deliberately an <b>id and a sentence</b>, and nothing else. Every other field of a KPI card —
 * the SQL, the thresholds, the evidence — is derived from a measurement and says which one; a model
 * filling any of them would produce something that looks derived and is not, which is the single
 * thing the suggestion panel exists to refuse. So the model does the part a model is for: choosing
 * among things the server can already defend, and saying why.
 *
 * <p>Unlike {@code usage}, {@code coverage} and {@code processModel} on the same result, this one
 * <em>is</em> the model's answer and is bound from its JSON. What protects it is not read-only
 * access but the filter on the way out: an id that was not in the prompt's candidate list is
 * dropped and counted, because a model naming a card nobody offered has invented it.
 *
 * @param id  the candidate id, matching what the suggestion panel mints for that card
 * @param why one sentence, in the model's own words, on what makes this one worth watching here
 */
public record MetricPriority(String id, String why) {}
