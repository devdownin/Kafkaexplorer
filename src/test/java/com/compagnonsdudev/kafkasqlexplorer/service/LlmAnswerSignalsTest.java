// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two failures that leave no error behind, and were therefore invisible: a schema the endpoint
 * accepted and did not apply, and a prompt it silently read only part of.
 */
class LlmAnswerSignalsTest {

    // ── The constraint that did not hold ─────────────────────────────────────

    @Test
    void saysNothingWhenTheAnswerCameBackAsPureJson() {
        String answer = "{\"flowchart\":\"graph TD\"}";
        assertNull(LlmAnswerSignals.constraintWarning(true, answer, answer));
    }

    @Test
    void reportsASchemaThatWasSentAndPlainlyNotApplied() {
        // A constrained decoder cannot emit a reasoning block: if one had to be stripped, the
        // field went out and the endpoint ignored it. The run succeeded, and nothing said the
        // guarantee was not in force.
        String raw = "<think>let me plan the JSON</think>{\"flowchart\":\"graph TD\"}";
        String payload = LlmJsonSupport.extractJsonPayload(raw);

        String warning = LlmAnswerSignals.constraintWarning(true, raw, payload);

        assertNotNull(warning);
        assertTrue(warning.contains("did not apply"), warning);
    }

    @Test
    void saysNothingAboutRepairWhenNoSchemaWasSent() {
        // Repair is the expected, unremarkable case on a provider that constrains nothing —
        // SpectraLLM's query API has no notion of a schema. Warning here would be noise on every
        // call, and noise is what stops a warning being read.
        String raw = "```json\n{\"flowchart\":\"graph TD\"}\n```";

        assertNull(LlmAnswerSignals.constraintWarning(false, raw, LlmJsonSupport.extractJsonPayload(raw)));
    }

    @Test
    void claimsNothingWhenThereIsNoAnswerToJudge() {
        assertNull(LlmAnswerSignals.constraintWarning(true, null, null));
    }

    // ── The prompt that was read only in part ────────────────────────────────

    @Test
    void reportsACountFarBelowWhatWasSent() {
        // 120 000 characters is a floor of 30 000 tokens on the optimistic ratio; an endpoint
        // reporting 4 000 did not read the prompt. That is Ollama's documented behaviour — it
        // drops the oldest messages until the prompt fits and logs it at debug, i.e. nowhere.
        String warning = LlmAnswerSignals.truncationWarning(120_000, 4_000L);

        assertNotNull(warning);
        assertTrue(warning.contains("4000"), warning);
        assertTrue(warning.contains("120000"), warning);
        assertTrue(warning.contains("estimate"),
            "it must present itself as a reason to check, not as a measurement: " + warning);
    }

    @Test
    void saysNothingWhenTheCountIsConsistentWithWhatWasSent() {
        // Real prompts tokenise nearer three characters per token, so a genuine count lands above
        // the four-character floor. Neither that nor anything modestly under it is a finding.
        assertNull(LlmAnswerSignals.truncationWarning(120_000, 40_000L));
        assertNull(LlmAnswerSignals.truncationWarning(120_000, 30_000L));
        assertNull(LlmAnswerSignals.truncationWarning(120_000, 20_000L));
    }

    @Test
    void claimsNothingWhenTheProviderCountedNothing() {
        // SpectraLLM returns no accounting at all and a lean gateway may omit `usage`. An absent
        // count is not a small one — the same rule the token fields themselves are boxed for.
        assertNull(LlmAnswerSignals.truncationWarning(120_000, null));
        assertNull(LlmAnswerSignals.truncationWarning(120_000, 0L));
        assertNull(LlmAnswerSignals.truncationWarning(0, 4_000L));
    }

    @Test
    void theThresholdIsHalfTheOptimisticFloorInBothDirections() {
        // Exactly at the boundary is not a finding; below it is. Pinned because the constant is
        // the whole of the rule, and a drift in it would silently widen or close the check.
        long floor = 40_000 / LlmAnswerSignals.CHARS_PER_TOKEN;          // 10 000
        long atThreshold = (long) (floor * LlmAnswerSignals.TRUNCATION_RATIO);   // 5 000

        assertNull(LlmAnswerSignals.truncationWarning(40_000, atThreshold));
        assertNotNull(LlmAnswerSignals.truncationWarning(40_000, atThreshold - 1));
    }
}
