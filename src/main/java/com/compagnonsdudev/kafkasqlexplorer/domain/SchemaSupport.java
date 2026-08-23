// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Whether the configured model will really constrain its answer to the JSON Schema this
 * application sends.
 *
 * <p>Four values rather than a boolean, because the interesting case is the third one and a
 * boolean cannot express it. OpenRouter's catalogue lists {@code response_format} and
 * {@code structured_outputs} as <em>separate</em> capabilities, and the difference between them is
 * the difference between a guarantee and the appearance of one:
 *
 * <ul>
 *   <li>{@link #CONSTRAINED} — both are listed. The decoder cannot emit anything but the schema.
 *   <li>{@link #UNSUPPORTED} — neither is listed. The request will be refused with a 400 or 422,
 *       which {@code OpenAiCompatibleLlmClient} already handles: one unconstrained retry, and the
 *       refusal is remembered against that model.
 *   <li>{@link #ACCEPTED_UNCONSTRAINED} — {@code response_format} is listed and
 *       {@code structured_outputs} is not. <strong>This is the one the running code cannot
 *       see.</strong> The field is accepted, so there is no 4xx and nothing latches; the schema is
 *       then ignored and the answer arrives as ordinary prose, recovered — if it can be — by
 *       {@code LlmJsonSupport}. A guarantee that silently is not one is worse than an outright
 *       refusal, which is why this is reported rather than inferred from a failure that never
 *       comes.
 *   <li>{@link #UNKNOWN} — the catalogue did not say, or was not consulted. Not a verdict.
 * </ul>
 *
 * <p>Nothing acts on this. {@code claude.structured-output: AUTO} already declines to decide for an
 * endpoint it does not know, and the same restraint applies here: the operator is told what the
 * gateway says about their model, and picks.
 */
public enum SchemaSupport { CONSTRAINED, ACCEPTED_UNCONSTRAINED, UNSUPPORTED, UNKNOWN }
