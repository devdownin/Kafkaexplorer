// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One declared window, every prompt budget capped by it. Process Mining's and the assistant's
 * budgets were tuned by hand against the same local window, and what does not fit is lost.
 */
class ClaudeConfigWindowTest {

    @Test
    void anUndeclaredWindowLeavesEveryBudgetAsConfigured() {
        ClaudeConfig config = new ClaudeConfig();

        assertEquals(0, config.getContextWindowTokens());
        assertEquals(Integer.MAX_VALUE, config.promptCharCeiling());
        assertEquals(120_000, config.effectivePromptCharBudget(120_000));
    }

    @Test
    void aDeclaredWindowLeavesRoomForTheAnswer() {
        ClaudeConfig config = new ClaudeConfig();
        config.setMaxTokens(4096);
        config.setContextWindowTokens(16_384);

        // (16 384 - 4 096) x 3: the llama.cpp stack's window, which neither shipped budget reaches.
        assertEquals(36_864, config.promptCharCeiling());
        assertEquals(16_000, config.effectivePromptCharBudget(16_000), "under the ceiling: unchanged");
        assertEquals(36_864, config.effectivePromptCharBudget(120_000),
            "the shipped Process Mining default does not fit a 16k window");
    }

    @Test
    void aWindowSmallerThanTheAnswerStillLeavesAFloor() {
        ClaudeConfig config = new ClaudeConfig();
        config.setMaxTokens(4096);
        config.setContextWindowTokens(2_048);

        assertEquals(1_000, config.promptCharCeiling());
    }

    @Test
    void aProbeCopyCarriesTheWindow() {
        ClaudeConfig config = new ClaudeConfig();
        config.setContextWindowTokens(8_192);

        assertEquals(8_192, config.probeCopy("other").getContextWindowTokens());
    }
}
