// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import com.compagnonsdudev.kafkasqlexplorer.service.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts what the operator typed on the Settings page back into the environment, before any bean
 * reads it.
 *
 * <p>This is the boot half of {@link SettingsStore}. It runs as a property source rather than as a
 * bean that copies values into {@link KafkaConfig} and {@link ClaudeConfig} afterwards, and the
 * timing is the reason: {@code KafkaConfig} validates its settings in a {@code @PostConstruct} and
 * refuses to start on a mode it cannot honour, while {@code MetricService} and
 * {@code FieldMappingStore} each open a Kafka consumer in theirs. A bean that assigned the stored
 * bootstrap address would be racing all three, and would leave the validation having passed on
 * values the application is no longer using. A property source is read by the binder before any of
 * that exists, so every one of those components sees the restored settings as its own configuration
 * — none of them needs a line of code for this.
 *
 * <p>Registered explicitly from {@code Application.main} rather than through {@code spring.factories}
 * so that it is greppable from the entry point, and so that {@code @SpringBootTest} contexts stay
 * hermetic: a test must not pick up a settings file that happens to exist in the working directory.
 *
 * <h2>Precedence</h2>
 * <ol>
 *   <li>an environment variable, a {@code -D} system property or a command-line argument</li>
 *   <li>the stored settings, i.e. what was typed on the Settings page</li>
 *   <li>{@code application.yml}, the shipped default</li>
 * </ol>
 *
 * <p>The first level wins because it is the deployment stating its intent for <em>this</em> boot —
 * an operator who edits {@code KAFKA_BOOTSTRAP_SERVERS} in their compose file and restarts must not
 * be silently ignored by a file written weeks ago — and because it is the only way back out of a
 * stored address that points at a cluster which no longer answers. It is implemented by leaving
 * those properties out of the source entirely rather than by ordering it below
 * {@code systemEnvironment}, which keeps the rule independent of where Spring happens to place the
 * config-data sources, and lets the boot log <b>name the fields the environment overrode</b>. Two
 * sources of truth for one setting are tolerable; two sources of truth and no way to tell which
 * won are not.
 */
public class StoredSettingsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(StoredSettingsInitializer.class);

    /** Ahead of {@code application.yml}; it holds nothing the environment also names, see above. */
    static final String SOURCE_NAME = "storedSettings";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        try {
            apply(applicationContext.getEnvironment());
        } catch (Exception e) {
            // Restoring settings is worth a lot and is worth nothing at all next to starting: an
            // unreadable store must never be able to keep the application down, since the Settings
            // page is where an operator would go to fix it.
            log.warn("Stored settings were not restored: {}", e.toString());
        }
    }

    /** Package-private so the test can drive it against a {@code StandardEnvironment}. */
    void apply(ConfigurableEnvironment environment) {
        if (!environment.getProperty("explorer.settings-persistence", Boolean.class, true)) {
            return;
        }
        Path path = SettingsStore.resolvePath(
            environment.getProperty("explorer.settings-store-path", ExplorerConfig.DEFAULT_SETTINGS_STORE_PATH));
        SettingsStore.StoredSettings stored = SettingsStore.read(path);
        if (stored.isEmpty()) {
            return;
        }

        Map<String, String> restored = new LinkedHashMap<>();
        List<String> overriddenByEnvironment = new ArrayList<>();
        for (SettingsStore.Field field : SettingsStore.FIELDS) {
            String value = stored.values().get(field.property());
            if (value == null) continue;
            if (namedByEnvironment(environment, field)) {
                overriddenByEnvironment.add(field.property());
                continue;
            }
            restored.put(field.property(), value);
        }

        if (!restored.isEmpty()) {
            environment.getPropertySources()
                .addFirst(new MapPropertySource(SOURCE_NAME, Map.copyOf(restored)));
        }
        report(path, stored, restored, overriddenByEnvironment);
    }

    /**
     * Whether this boot's environment already names the property.
     *
     * <p>Only the three sources that carry a deliberate act by whoever started the process are
     * consulted. {@code SystemEnvironmentPropertySource} does the relaxed-name mapping itself, so
     * {@code kafka.bootstrap-servers} finds {@code KAFKA_BOOTSTRAP_SERVERS} without this having to
     * know the rule. The {@link SettingsStore.Field#envAlias() alias} covers the one property that
     * is bound through a placeholder and therefore answers to a name the mapping cannot derive.
     */
    private boolean namedByEnvironment(ConfigurableEnvironment environment, SettingsStore.Field field) {
        for (String sourceName : List.of(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME)) {
            PropertySource<?> source = environment.getPropertySources().get(sourceName);
            if (source == null) continue;
            if (source.containsProperty(field.property())) return true;
            if (field.envAlias() != null && source.containsProperty(field.envAlias())) return true;
        }
        return false;
    }

    /**
     * One line about what this boot is running on, and one about anything it could not restore.
     *
     * <p>Field <em>names</em> only — a value here is a bootstrap address or a keystore password,
     * and a log file is the wrong place for the second even though it is the right place for the
     * first. The address that ends up in force is printed by {@code StartupSummary}, next to a
     * probe that says whether it answered, which is the honest form of that statement.
     */
    private void report(Path path, SettingsStore.StoredSettings stored,
                        Map<String, String> restored, List<String> overriddenByEnvironment) {
        if (!restored.isEmpty()) {
            log.info("Restored {} setting(s) saved from the Settings page{} ({}): {}",
                restored.size(),
                stored.savedAt() != null ? " on " + stored.savedAt() : "",
                path, String.join(", ", restored.keySet()));
        }
        if (!overriddenByEnvironment.isEmpty()) {
            // INFO, not WARN: this is the documented precedence doing its job. It still has to be
            // said, or the Settings page shows one value and the application uses another.
            log.info("The environment sets {}, so the stored value is not used for {}. Saving from "
                    + "the Settings page will not change what this deployment connects to until "
                    + "the variable is removed.",
                overriddenByEnvironment.size() == 1 ? "1 of those settings" : "some of those settings",
                String.join(", ", overriddenByEnvironment));
        }
        if (!stored.secretsOmitted().isEmpty()) {
            // WARN, and this one is: a connection whose password was deliberately not stored will
            // fail, and the operator has to be able to connect that failure to this choice.
            log.warn("{} credential(s) were entered on the Settings page but not stored, because "
                    + "explorer.settings-store-secrets is false: {}. Whatever the environment "
                    + "provides is used instead, and they have to be re-entered to change them.",
                stored.secretsOmitted().size(), String.join(", ", stored.secretsOmitted()));
        }
    }
}
