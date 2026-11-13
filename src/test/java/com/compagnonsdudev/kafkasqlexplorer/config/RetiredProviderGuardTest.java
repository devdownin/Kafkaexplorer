// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

package com.compagnonsdudev.kafkasqlexplorer.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A retired provider name stops the boot with the name to write instead — the binder alone would
 * only say which enum constant is missing.
 */
class RetiredProviderGuardTest {

    private static final String RETIRED = RetiredProviderGuard.RETIRED.keySet().iterator().next();

    @Test
    void aRetiredProviderIsRefusedWithItsReplacement() {
        var environment = new MockEnvironment().withProperty("claude.provider", RETIRED.toLowerCase());
        var refusal = assertThrows(IllegalStateException.class, () -> RetiredProviderGuard.check(environment));
        assertTrue(refusal.getMessage().contains("claude.provider"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("CLAUDE_PROVIDER=LLAMA_CPP"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(ClaudeConfig.defaultBaseUrl(ClaudeConfig.Provider.LLAMA_CPP)));
    }

    @Test
    void theAssistantsOwnProviderIsCheckedToo() {
        var environment = new MockEnvironment().withProperty("explorer.assistant.provider", RETIRED);
        var refusal = assertThrows(IllegalStateException.class, () -> RetiredProviderGuard.check(environment));
        assertTrue(refusal.getMessage().contains("EXPLORER_ASSISTANT_PROVIDER=LLAMA_CPP"), refusal.getMessage());
    }

    @Test
    void everyCurrentProviderPasses() {
        for (ClaudeConfig.Provider provider : ClaudeConfig.Provider.values()) {
            var environment = new MockEnvironment().withProperty("claude.provider", provider.name());
            assertDoesNotThrow(() -> RetiredProviderGuard.check(environment), provider.name());
        }
        assertDoesNotThrow(() -> RetiredProviderGuard.check(new MockEnvironment()));
    }
}
