// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import com.compagnonsdudev.kafkasqlexplorer.service.SettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot half: what the Settings page saved, back in the environment before any bean binds it.
 *
 * <p>The precedence rule is the whole subject. Getting it wrong in the safe-looking direction —
 * letting the stored file win outright — is how a deployment ends up ignoring the
 * {@code KAFKA_BOOTSTRAP_SERVERS} its operator just changed in a compose file, with no way out of
 * a stored address pointing at a cluster that no longer answers. Getting it wrong the other way
 * means the store does nothing at all, since {@code application.yml} always has a value.
 */
class StoredSettingsInitializerTest {

    @TempDir
    Path tempDir;

    /** An environment shaped like a real boot: a YAML-provided default under a system-env source. */
    private StandardEnvironment environmentWith(Path store, Map<String, String> osEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.copyOf(new LinkedHashMap<>(osEnvironment))));
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("explorer.settings-store-path", store.toString());
        yaml.put("kafka.bootstrap-servers", "from-yaml:9092");
        yaml.put("kafka.mode", "PLAIN");
        yaml.put("claude.model", "qwen3:4b");
        yaml.put("claude.api-key", "");
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", yaml));
        return environment;
    }

    private void store(Path path, String... propertyValuePairs) throws IOException {
        StringBuilder json = new StringBuilder("{\"version\":1,\"settings\":{");
        for (int i = 0; i < propertyValuePairs.length; i += 2) {
            if (i > 0) json.append(',');
            json.append('"').append(propertyValuePairs[i]).append("\":\"")
                .append(propertyValuePairs[i + 1]).append('"');
        }
        json.append("}}");
        Files.writeString(path, json.toString());
    }

    // ── Precedence ────────────────────────────────────────────────────────────

    /** Level 2 beats level 3: what was typed on the page beats the shipped default. */
    @Test
    void aStoredSettingBeatsApplicationYaml() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "kafka.bootstrap-servers", "from-store:9092");
        StandardEnvironment environment = environmentWith(path, Map.of());

        new StoredSettingsInitializer().apply(environment);

        assertEquals("from-store:9092", environment.getProperty("kafka.bootstrap-servers"));
    }

    /**
     * Level 1 beats level 2: an operator who edits the variable in their compose file and restarts
     * must not be silently overruled by a file written weeks earlier — and this is the only way
     * back out of a stored address pointing at a cluster that is gone.
     */
    @Test
    void anEnvironmentVariableBeatsTheStoredSetting() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "kafka.bootstrap-servers", "from-store:9092");
        StandardEnvironment environment = environmentWith(path,
            Map.of("KAFKA_BOOTSTRAP_SERVERS", "from-env:9092"));

        new StoredSettingsInitializer().apply(environment);

        assertEquals("from-env:9092", environment.getProperty("kafka.bootstrap-servers"));
    }

    /** One field held by the environment must not cost the others their stored value. */
    @Test
    void theOverrideIsPerFieldNotPerFile() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "kafka.bootstrap-servers", "from-store:9092", "kafka.mode", "SSL",
            "claude.model", "qwen3:8b");
        StandardEnvironment environment = environmentWith(path,
            Map.of("KAFKA_BOOTSTRAP_SERVERS", "from-env:9092"));

        new StoredSettingsInitializer().apply(environment);

        assertEquals("from-env:9092", environment.getProperty("kafka.bootstrap-servers"));
        assertEquals("SSL", environment.getProperty("kafka.mode"));
        assertEquals("qwen3:8b", environment.getProperty("claude.model"));
    }

    @Test
    void aSystemPropertyBeatsTheStoredSettingToo() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "kafka.mode", "SSL");
        StandardEnvironment environment = environmentWith(path, Map.of());
        System.setProperty("kafka.mode", "PLAIN");
        try {
            new StoredSettingsInitializer().apply(environment);
            assertEquals("PLAIN", environment.getProperty("kafka.mode"));
        } finally {
            System.clearProperty("kafka.mode");
        }
    }

    /**
     * {@code claude.api-key} is bound through {@code ${ANTHROPIC_API_KEY:}}, so the relaxed name
     * {@code CLAUDE_API_KEY} is not what a deployment sets. Without the alias, a stored key would
     * silently outrank the one the operator put in their environment — on a credential.
     */
    @Test
    void theAnthropicKeyEnvironmentVariableIsRecognisedThroughItsAlias() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "claude.api-key", "sk-ant-from-store");
        StandardEnvironment environment = environmentWith(path,
            Map.of("ANTHROPIC_API_KEY", "sk-ant-from-env"));

        new StoredSettingsInitializer().apply(environment);

        assertNull(environment.getPropertySources().get("storedSettings"),
            "nothing was restored, so no source was added");
        assertEquals("", environment.getProperty("claude.api-key"),
            "the YAML placeholder resolves the environment variable itself");
    }

    // ── Not being restored ────────────────────────────────────────────────────

    @Test
    void nothingHappensWithoutAStore() {
        StandardEnvironment environment = environmentWith(tempDir.resolve("absent.json"), Map.of());

        new StoredSettingsInitializer().apply(environment);

        assertNull(environment.getPropertySources().get("storedSettings"));
        assertEquals("from-yaml:9092", environment.getProperty("kafka.bootstrap-servers"));
    }

    @Test
    void theSwitchTurnsTheWholeReplayOff() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "kafka.bootstrap-servers", "from-store:9092");
        StandardEnvironment environment = environmentWith(path, Map.of());
        environment.getPropertySources().addFirst(new MapPropertySource("test",
            Map.of("explorer.settings-persistence", "false")));

        new StoredSettingsInitializer().apply(environment);

        assertEquals("from-yaml:9092", environment.getProperty("kafka.bootstrap-servers"));
    }

    /** An unreadable store must never be able to keep the application down. */
    @Test
    void abrokenStoreLeavesTheEnvironmentAsItWas() throws IOException {
        Path path = tempDir.resolve("settings.json");
        Files.writeString(path, "{ not json");
        StandardEnvironment environment = environmentWith(path, Map.of());

        new StoredSettingsInitializer().apply(environment);

        assertEquals("from-yaml:9092", environment.getProperty("kafka.bootstrap-servers"));
    }

    /** Only what the table names is replayed — a key someone added to the file by hand is not. */
    @Test
    void anUnknownPropertyInTheFileIsNotApplied() throws IOException {
        Path path = tempDir.resolve("settings.json");
        store(path, "explorer.cleanup-own-groups", "true", "kafka.mode", "SSL");
        StandardEnvironment environment = environmentWith(path, Map.of());

        new StoredSettingsInitializer().apply(environment);

        assertEquals("SSL", environment.getProperty("kafka.mode"));
        assertFalse(environment.getPropertySources().get("storedSettings")
            .containsProperty("explorer.cleanup-own-groups"),
            "the store restores settings the Settings page owns, not arbitrary configuration");
    }

    /**
     * The round trip the operator actually experiences: type it, stop, start, find it.
     *
     * <p>Both halves through their real code paths — the store writes, the initializer reads —
     * because that is the seam where a format written one way and read another would show up.
     */
    @Test
    void whatTheSettingsPageSavesIsWhatTheNextBootRunsOn() {
        Path path = tempDir.resolve("settings.json");
        ExplorerConfig config = new ExplorerConfig();
        config.setSettingsStorePath(path.toString());

        Map<String, String> snapshot = new LinkedHashMap<>();
        for (SettingsStore.Field field : SettingsStore.FIELDS) snapshot.put(field.apiField(), "");
        snapshot.put("bootstrapServers", "kafka.internal:9093");
        snapshot.put("mode", "SSL");
        snapshot.put("keystorePassword", "hunter2");
        snapshot.put("llmModel", "qwen3:8b");
        assertTrue(new SettingsStore(config).save(
            Set.of("bootstrapServers", "mode", "keystorePassword", "llmModel"), snapshot).persisted());

        StandardEnvironment nextBoot = environmentWith(path, Map.of());
        new StoredSettingsInitializer().apply(nextBoot);

        assertEquals("kafka.internal:9093", nextBoot.getProperty("kafka.bootstrap-servers"));
        assertEquals("SSL", nextBoot.getProperty("kafka.mode"));
        assertEquals("hunter2", nextBoot.getProperty("kafka.keystore-password"));
        assertEquals("qwen3:8b", nextBoot.getProperty("claude.model"));
    }
}
