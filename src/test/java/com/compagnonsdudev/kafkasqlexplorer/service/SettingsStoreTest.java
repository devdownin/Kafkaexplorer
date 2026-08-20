// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store that keeps what the Settings page is used to change.
 *
 * <p>What is pinned here is mostly about the ways this can go wrong quietly: a field taken over
 * that nobody entered, a credential dropped without a word, a file from another version read as if
 * it were this one. The store exists because the Settings page lost its input silently, and a fix
 * for that which itself fails silently would be no fix.
 */
class SettingsStoreTest {

    @TempDir
    Path tempDir;

    private SettingsStore storeAt(Path path, boolean secrets) {
        ExplorerConfig config = new ExplorerConfig();
        config.setSettingsStorePath(path == null ? "" : path.toString());
        config.setSettingsStoreSecrets(secrets);
        return new SettingsStore(config);
    }

    private Map<String, String> snapshot(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingsStore.Field field : SettingsStore.FIELDS) values.put(field.apiField(), "");
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }

    @Test
    void whatIsSavedIsWhatComesBack() {
        Path path = tempDir.resolve("settings.json");
        SettingsStore store = storeAt(path, true);

        SettingsStore.SaveOutcome outcome = store.save(Set.of("bootstrapServers", "mode"),
            snapshot("bootstrapServers", "broker:9092", "mode", "SSL"));

        assertTrue(outcome.persisted());
        Map<String, String> stored = SettingsStore.read(path).values();
        assertEquals("broker:9092", stored.get("kafka.bootstrap-servers"));
        assertEquals("SSL", stored.get("kafka.mode"));
    }

    /**
     * Values are keyed by Spring property name, which is what lets the boot replay be a plain
     * property source with no translation table of its own. Storing the API's own field names
     * would put a second mapping between the two halves, and the two would drift.
     */
    @Test
    void valuesAreKeyedByThePropertyTheyBindTo() throws IOException {
        Path path = tempDir.resolve("settings.json");
        storeAt(path, true).save(Set.of("llmMaxTokens"), snapshot("llmMaxTokens", "8192"));

        String json = Files.readString(path);
        assertTrue(json.contains("claude.max-tokens"), json);
        assertFalse(json.contains("llmMaxTokens"), json);
    }

    /**
     * A field nobody entered keeps taking its value from the deployment's own configuration.
     *
     * <p>The alternative — capture everything on the first save — freezes this release's defaults
     * into a file, so a default that moves in a later version can never reach the deployment again.
     */
    @Test
    void onlyTheFieldsThatWereTouchedAreTakenOver() {
        Path path = tempDir.resolve("settings.json");
        storeAt(path, true).save(Set.of("mode"), snapshot("mode", "SSL", "llmModel", "qwen3:4b"));

        assertEquals(Set.of("kafka.mode"), SettingsStore.read(path).values().keySet());
    }

    @Test
    void aFieldStaysOwnedOnceItHasBeenEntered() {
        Path path = tempDir.resolve("settings.json");
        SettingsStore store = storeAt(path, true);

        store.save(Set.of("mode"), snapshot("mode", "SSL"));
        store.save(Set.of("llmModel"), snapshot("mode", "SSL", "llmModel", "qwen3:8b"));

        Map<String, String> stored = SettingsStore.read(path).values();
        assertEquals("SSL", stored.get("kafka.mode"));
        assertEquals("qwen3:8b", stored.get("claude.model"));
    }

    /** Clearing a field is a save like any other: it must not read back as "never entered". */
    @Test
    void clearingAValueIsStoredAsAnEmptyOneRatherThanAsAnAbsence() {
        Path path = tempDir.resolve("settings.json");
        SettingsStore store = storeAt(path, true);

        store.save(Set.of("llmCollection"), snapshot("llmCollection", "corpus"));
        store.save(Set.of("llmCollection"), snapshot("llmCollection", ""));

        assertEquals("", SettingsStore.read(path).values().get("claude.collection"));
    }

    // ── Secrets ───────────────────────────────────────────────────────────────

    @Test
    void secretsAreStoredWhenThatIsAllowed() {
        Path path = tempDir.resolve("settings.json");
        storeAt(path, true).save(Set.of("keystorePassword"), snapshot("keystorePassword", "hunter2"));

        assertEquals("hunter2", SettingsStore.read(path).values().get("kafka.keystore-password"));
    }

    /**
     * With secrets off the field is <b>named</b>, in the answer and in the file.
     *
     * <p>Storing everything except the passwords keeps half the promise: the mode and the keystore
     * path come back and the connection fails, for a credential nothing said had been dropped. The
     * boot reads {@code secretsOmitted} to say which one it is missing.
     */
    @Test
    void aSecretLeftOutIsNamedInBothTheAnswerAndTheFile() {
        Path path = tempDir.resolve("settings.json");

        SettingsStore.SaveOutcome outcome = storeAt(path, false).save(
            Set.of("keystorePassword", "keystorePath"),
            snapshot("keystorePassword", "hunter2", "keystorePath", "/certs/k.jks"));

        assertEquals(java.util.List.of("keystorePassword"), outcome.notStored());
        SettingsStore.StoredSettings stored = SettingsStore.read(path);
        assertFalse(stored.values().containsKey("kafka.keystore-password"));
        assertEquals(java.util.List.of("kafka.keystore-password"), stored.secretsOmitted());
        assertEquals("/certs/k.jks", stored.values().get("kafka.keystore-path"));
    }

    /** An empty password is not a credential the store is failing to keep. */
    @Test
    void anEmptySecretIsNotReportedAsSomethingLost() {
        Path path = tempDir.resolve("settings.json");

        SettingsStore.SaveOutcome outcome = storeAt(path, false)
            .save(Set.of("keystorePassword"), snapshot("keystorePassword", ""));

        assertTrue(outcome.notStored().isEmpty());
        assertTrue(SettingsStore.read(path).secretsOmitted().isEmpty());
    }

    /** The file holds a keystore password and an API key, on a volume shared with whatever else. */
    @Test
    void theFileIsReadableByItsOwnerAlone() throws IOException {
        Path path = tempDir.resolve("settings.json");
        storeAt(path, true).save(Set.of("llmApiKey"), snapshot("llmApiKey", "sk-ant-secret"));

        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException e) {
            return;   // not a POSIX filesystem; the store says so and carries on
        }
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions);
    }

    // ── Reading a file this build did not write ───────────────────────────────

    /**
     * A file from another format version is reported and ignored, never interpreted.
     *
     * <p>It is a small map of connection settings, and a wrong guess about one of them points the
     * application at the wrong cluster.
     */
    @Test
    void anUnknownFormatVersionIsIgnoredRatherThanGuessedAt() throws IOException {
        Path path = tempDir.resolve("settings.json");
        Files.writeString(path, "{\"version\":99,\"settings\":{\"kafka.mode\":\"SSL\"}}");

        assertTrue(SettingsStore.read(path).isEmpty());
    }

    @Test
    void anUnreadableFileIsIgnoredRatherThanThrown() throws IOException {
        Path path = tempDir.resolve("settings.json");
        Files.writeString(path, "{ not json at all");

        assertTrue(SettingsStore.read(path).isEmpty());
    }

    @Test
    void anAbsentOrEmptyFileIsAnEmptyStore() throws IOException {
        assertTrue(SettingsStore.read(tempDir.resolve("nothing-here.json")).isEmpty());
        assertTrue(SettingsStore.read(null).isEmpty());

        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "");
        assertTrue(SettingsStore.read(empty).isEmpty());
    }

    /** A file this build cannot read is left on disk for an operator to look at. */
    @Test
    void anUnreadableFileIsNotDeleted() throws IOException {
        Path path = tempDir.resolve("settings.json");
        Files.writeString(path, "{ not json at all");

        SettingsStore.read(path);

        assertTrue(Files.exists(path));
    }

    /** And the next save replaces it, so a bad file is not a dead end. */
    @Test
    void aSaveOverAnUnreadableFileReplacesIt() throws IOException {
        Path path = tempDir.resolve("settings.json");
        Files.writeString(path, "{ not json at all");

        storeAt(path, true).save(Set.of("mode"), snapshot("mode", "SSL"));

        assertEquals("SSL", SettingsStore.read(path).values().get("kafka.mode"));
    }

    // ── Off, and unusable ─────────────────────────────────────────────────────

    @Test
    void aStoreWithNoPathKeepsNothingAndSaysSo() {
        SettingsStore store = storeAt(null, true);

        assertFalse(store.isEnabled());
        assertNull(store.storePath());
        SettingsStore.SaveOutcome outcome = store.save(Set.of("mode"), snapshot("mode", "SSL"));
        assertFalse(outcome.persisted());
        assertNull(outcome.error(), "off is not a failure");
    }

    @Test
    void persistenceCanBeTurnedOffWithTheSwitchRatherThanThePath() {
        ExplorerConfig config = new ExplorerConfig();
        config.setSettingsStorePath(tempDir.resolve("settings.json").toString());
        config.setSettingsPersistence(false);

        assertFalse(new SettingsStore(config).isEnabled());
    }

    /**
     * A store that cannot be written reports the reason instead of failing the save: the settings
     * are applied and this process is using them. What must not be left standing is the belief
     * that they will still be there after a restart.
     */
    @Test
    void aWriteThatCannotSucceedIsReportedNotThrown() throws IOException {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "a regular file, so it cannot be a parent directory");

        SettingsStore.SaveOutcome outcome = storeAt(blocker.resolve("settings.json"), true)
            .save(Set.of("mode"), snapshot("mode", "SSL"));

        assertFalse(outcome.persisted());
        assertNotNull(outcome.error());
    }

    @Test
    void aFailedWriteLeavesNoTemporaryFileBehind() throws IOException {
        Path path = tempDir.resolve("settings.json");
        storeAt(path, true).save(Set.of("mode"), snapshot("mode", "SSL"));

        try (var entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    // ── The field table ───────────────────────────────────────────────────────

    /**
     * Every field the API accepts has to be in the table, or it is a setting that is applied and
     * then lost — the exact defect this store was written for, reintroduced one field at a time.
     */
    @Test
    void everyFieldMapsToADistinctPropertyAndApiName() {
        Map<String, String> byApi = new HashMap<>();
        Map<String, String> byProperty = new HashMap<>();
        for (SettingsStore.Field field : SettingsStore.FIELDS) {
            assertNull(byApi.put(field.apiField(), field.property()),
                "duplicate API field " + field.apiField());
            assertNull(byProperty.put(field.property(), field.apiField()),
                "duplicate property " + field.property());
            assertTrue(field.property().startsWith("kafka.") || field.property().startsWith("claude."),
                field.property() + " binds to neither KafkaConfig nor ClaudeConfig");
        }
    }

    /** Every credential the Settings page accepts is marked, or it is written under a flag off. */
    @Test
    void everyCredentialIsMarkedAsOne() {
        for (SettingsStore.Field field : SettingsStore.FIELDS) {
            String name = field.apiField().toLowerCase(java.util.Locale.ROOT);
            boolean looksLikeOne = name.contains("password") || name.contains("secret")
                || name.contains("apikey");
            assertEquals(looksLikeOne, field.secret(), field.apiField());
        }
    }
}
