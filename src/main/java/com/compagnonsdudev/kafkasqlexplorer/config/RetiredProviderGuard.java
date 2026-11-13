// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

package com.compagnonsdudev.kafkasqlexplorer.config;

import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Refuses to start on a provider name this build retired, and says what replaced it.
 *
 * <p>A provider removed from {@link ClaudeConfig.Provider} is otherwise reported by the binder as
 * a failed enum conversion — true, and of no use to the operator whose compose file has said that
 * name for a year: it names the constant that is missing, not the one to write instead. The name
 * can also come from the settings file the Settings page wrote, which no one remembers editing,
 * so the sentence says that an environment variable overrides it — the only way back in, since
 * the page that would fix it is behind the boot that fails.
 *
 * <p>Registered from {@code Application.main} after {@link StoredSettingsInitializer}, so a
 * stored value is seen too.
 */
public class RetiredProviderGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** Retired name → the provider to name instead. */
    static final Map<String, ClaudeConfig.Provider> RETIRED = Map.of("OLLAMA", ClaudeConfig.Provider.LLAMA_CPP);

    static final String[] PROPERTIES = {"claude.provider", "explorer.assistant.provider"};

    /** The variable application.yml reads each of them from. */
    static final Map<String, String> ENVIRONMENT = Map.of(
        "claude.provider", "CLAUDE_PROVIDER", "explorer.assistant.provider", "EXPLORER_ASSISTANT_PROVIDER");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        check(applicationContext.getEnvironment());
    }

    /** @throws IllegalStateException naming the property, the retired value and its replacement */
    static void check(Environment environment) {
        for (String property : PROPERTIES) {
            String value = environment.getProperty(property, "");
            String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            ClaudeConfig.Provider replacement = RETIRED.get(name);
            if (replacement == null) continue;
            throw new IllegalStateException(property + " is set to " + name + ", a provider this version "
                + "no longer has: the local runtime is llama.cpp. Set it to " + replacement
                + " and point the base URL at a llama-server (" + ClaudeConfig.defaultBaseUrl(replacement)
                + " by default; compose/llama-cpp.yml runs one). If the value was saved from the Settings "
                + "page, an environment variable overrides it: " + ENVIRONMENT.get(property) + "="
                + replacement + ".");
        }
    }
}
