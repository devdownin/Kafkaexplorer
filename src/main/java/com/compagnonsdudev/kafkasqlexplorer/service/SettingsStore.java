// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an operator typed on the Settings page, kept so that stopping the application does not
 * throw it away.
 *
 * <p>{@code POST /api/config} mutated {@link com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig}
 * and {@link com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig} — two singletons — and wrote
 * nothing anywhere. The Settings page is the one screen in this application whose entire purpose is
 * data entry, and it was the only one whose input did not survive a restart: a bootstrap address, a
 * connection mode, an SSL keystore path and its password, Confluent Cloud credentials, the LLM
 * provider, base URL, model and API key all reverted to {@code application.yml} on the next boot.
 * Worse than losing them, it lost them <em>silently</em> — the page then rendered the YAML values as
 * if they were the operator's own, so a cluster repointed on Monday was quietly reading a different
 * cluster on Tuesday with nothing on screen or in the log connecting the two.
 *
 * <p><b>A file, not a Kafka topic</b>, which is the one design decision here that is not
 * interchangeable. {@link MetricService}, {@link FieldMappingStore} and {@link AuditService} all
 * persist to an {@code internal.*} topic, and that is right for them; it cannot work for this,
 * because these settings <em>contain the address of the broker</em>. Writing them to the cluster
 * they describe is circular in both directions: a save that repoints from cluster A to B has no
 * answer to which of the two should receive it, and a boot cannot read the topic that would tell it
 * which broker to read. The store therefore sits beside {@link FlinkJobStore} under {@code data/},
 * which is a named volume in every bundled stack.
 *
 * <p><b>Only the fields an operator has actually set are stored.</b> A save writes the effective
 * value of the fields it touched, plus the ones already held — never the whole configuration. The
 * difference matters on the next release: a store that captured every value would freeze today's
 * defaults into the file, so a changed default in {@code application.yml} could never reach a
 * deployment again, and nothing would say why.
 *
 * <p><b>The environment still wins.</b> See {@link
 * com.compagnonsdudev.kafkasqlexplorer.config.StoredSettingsInitializer}: a value named by an
 * environment variable, a {@code -D} system property or a command-line argument beats the stored
 * one, and the boot log says which fields that applied to. Otherwise a stored address would
 * override the {@code KAFKA_BOOTSTRAP_SERVERS} an operator had just changed in their compose file,
 * and there would be no way out of a stored setting pointing at a cluster that no longer exists.
 *
 * <p><b>Secrets are written in clear</b>, and that is a deliberate, documented trade rather than an
 * oversight. The alternative — storing everything except the passwords — keeps half the promise and
 * keeps it silently, which is the failure this whole class exists to remove: the operator would
 * find the connection mode restored, the keystore path restored, and the connection failing for a
 * password nothing said had been dropped. The file is created readable by its owner alone, the
 * values never travel back out through {@code GET /api/config} (which reports a boolean), and
 * {@code explorer.settings-store-secrets: false} omits them for a deployment that will not have
 * them on disk — in which case the fields that were left out are <em>named</em>, in the save's
 * answer and in the boot log, so the half-kept promise is at least a stated one.
 */
@Component
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    /** The shape this class writes. A file carrying anything else is not guessed at — see {@link #read}. */
    static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One settings field, in the three vocabularies it has to be named in.
     *
     * @param apiField the key {@code POST /api/config} carries, which is also what the Settings
     *                 page calls it
     * @param property the Spring property the value binds to, which is what is written to the file
     *                 — storing property names rather than API names is what lets the boot replay
     *                 be a plain {@code PropertySource} with no translation table of its own, and
     *                 therefore what stops the two halves from drifting apart
     * @param secret   whether the value is a credential, for {@code explorer.settings-store-secrets}
     * @param envAliases the environment variables that also set this property when it is bound
     *                 through a placeholder, or empty. Only {@code claude.api-key} has any:
     *                 {@code application.yml} binds it as
     *                 {@code ${OPENROUTER_API_KEY:${ANTHROPIC_API_KEY:}}}, so the relaxed name
     *                 {@code CLAUDE_API_KEY} is not the only name a deployment actually sets, and
     *                 without this a stored key would silently outrank the one in the environment.
     *                 A list rather than one name since the default provider became OpenRouter: a
     *                 key set under the variable that provider's own documentation names has to
     *                 count as "the environment named this", exactly as the historical one does.
     */
    public record Field(String apiField, String property, boolean secret, List<String> envAliases) {
        Field(String apiField, String property) {
            this(apiField, property, false, List.of());
        }

        Field(String apiField, String property, boolean secret) {
            this(apiField, property, secret, List.of());
        }

        Field(String apiField, String property, boolean secret, String... envAliases) {
            this(apiField, property, secret, List.of(envAliases));
        }
    }

    /**
     * Every field {@code POST /api/config} can change, and nothing else.
     *
     * <p>This list <em>is</em> the definition of "what required data entry": a setting an operator
     * cannot type on the Settings page has no business being restored from a file, because the only
     * place it can have come from is the deployment's own configuration, which is still there.
     */
    public static final List<Field> FIELDS = List.of(
        new Field("bootstrapServers", "kafka.bootstrap-servers"),
        new Field("mode", "kafka.mode"),
        new Field("truststorePath", "kafka.truststore-path"),
        new Field("truststorePassword", "kafka.truststore-password", true),
        new Field("keystorePath", "kafka.keystore-path"),
        new Field("keystorePassword", "kafka.keystore-password", true),
        new Field("keyPassword", "kafka.key-password", true),
        // The Confluent key is an account identifier, the username half of the SASL pair; the
        // secret beside it is the credential. Marking both would mean an operator who turns
        // secrets off cannot store the half that is not one.
        new Field("confluentKey", "kafka.confluent-key"),
        new Field("confluentSecret", "kafka.confluent-secret", true),
        new Field("llmProvider", "claude.provider"),
        new Field("llmApiKey", "claude.api-key", true, "OPENROUTER_API_KEY", "ANTHROPIC_API_KEY"),
        new Field("llmBaseUrl", "claude.base-url"),
        new Field("llmModel", "claude.model"),
        new Field("llmUseRag", "claude.use-rag"),
        new Field("llmCollection", "claude.collection"),
        new Field("llmStructuredOutput", "claude.structured-output"),
        new Field("llmOpenrouterDataCollection", "claude.openrouter-data-collection"),
        new Field("llmOpenrouterRequireParameters", "claude.openrouter-require-parameters"),
        new Field("llmRequestTimeoutSeconds", "claude.request-timeout-seconds"),
        new Field("llmMaxTokens", "claude.max-tokens"),
        new Field("llmSnapshotWindowSize", "claude.snapshot-window-size"),
        new Field("llmSnapshotWindowTimeoutSeconds", "claude.snapshot-window-timeout-seconds")
    );

    /** What one save achieved, so the answer can say it rather than implying it with a 200. */
    public record SaveOutcome(boolean persisted, String path, List<String> notStored, String error) {

        static SaveOutcome disabled() {
            return new SaveOutcome(false, null, List.of(), null);
        }
    }

    /**
     * The contents of the file.
     *
     * @param values         property name → value, exactly as written
     * @param secretsOmitted properties whose value was set but deliberately not written, so a boot
     *                       can say which credential it is missing instead of failing to connect
     *                       for a reason nothing names
     * @param savedAt        when it was written, for the boot log
     */
    public record StoredSettings(Map<String, String> values, List<String> secretsOmitted, String savedAt) {

        public static final StoredSettings EMPTY = new StoredSettings(Map.of(), List.of(), null);

        public boolean isEmpty() {
            return values.isEmpty() && secretsOmitted.isEmpty();
        }
    }

    private final boolean enabled;
    private final boolean storeSecrets;
    private final Path path;

    public SettingsStore(ExplorerConfig explorerConfig) {
        this.enabled = explorerConfig.isSettingsPersistence();
        this.storeSecrets = explorerConfig.isSettingsStoreSecrets();
        this.path = resolvePath(explorerConfig.getSettingsStorePath());
    }

    /**
     * Where the store lives, absolute and normalised, or {@code null} when the path is unusable.
     *
     * <p>Shared with the boot-time replay so the two cannot resolve the same setting to two
     * different files — the whole feature is one file read by one writer and one reader.
     */
    public static Path resolvePath(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("explorer.settings-store-path is '{}', which is not a usable path: {}. "
                + "Settings typed on the Settings page will apply to this process only.",
                configured, SqlErrorClassifier.explain(e));
            return null;
        }
    }

    /** Whether this process will keep what the Settings page is used to change. */
    public boolean isEnabled() {
        return enabled && path != null;
    }

    public boolean storesSecrets() {
        return storeSecrets;
    }

    /** The file backing the store, or {@code null} when persistence is off. */
    public Path storePath() {
        return isEnabled() ? path : null;
    }

    /**
     * Reads the store, and never throws.
     *
     * <p>Static because the boot replay runs before there is a Spring context to hold a bean, and
     * having it read the file through a second code path is how the written and the read format
     * come to disagree.
     *
     * <p>A file this build does not recognise is <b>reported and ignored</b>, not interpreted: it
     * is a small map of connection settings, and a wrong guess about one of them points the
     * application at the wrong cluster. It is left on disk rather than deleted, so an operator can
     * look at it; the next save replaces it.
     */
    public static StoredSettings read(Path path) {
        JsonNode root = JsonStoreFile.read(MAPPER, path, "the settings saved from the Settings page");
        if (root == null) return StoredSettings.EMPTY;
        try {
            int version = root.path("version").asInt(-1);
            if (version != FORMAT_VERSION) {
                log.warn("Ignoring {}: it declares format version {}, and this build writes {}. "
                    + "The settings it holds are not applied — saving from the Settings page "
                    + "rewrites the file.", path, version < 0 ? "none" : version, FORMAT_VERSION);
                return StoredSettings.EMPTY;
            }
            Map<String, String> values = new LinkedHashMap<>();
            JsonNode settings = root.path("settings");
            settings.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (!value.isNull()) values.put(entry.getKey(), value.asText());
            });
            List<String> omitted = new ArrayList<>();
            root.path("secretsOmitted").forEach(node -> omitted.add(node.asText()));
            String savedAt = root.path("savedAt").isTextual() ? root.path("savedAt").asText() : null;
            return new StoredSettings(Collections.unmodifiableMap(values),
                List.copyOf(omitted), savedAt);
        } catch (Exception e) {
            log.warn("Ignoring {}: its contents are not settings this build understands ({}). "
                + "Saving from the Settings page rewrites the file.",
                path, SqlErrorClassifier.explain(e));
            return StoredSettings.EMPTY;
        }
    }

    /** What this process currently has on disk. */
    public StoredSettings current() {
        return isEnabled() ? read(path) : StoredSettings.EMPTY;
    }

    /**
     * Writes the fields this deployment has taken over.
     *
     * @param touched  the API fields the save carried — what the operator just entered
     * @param snapshot the effective value of every field in {@link #FIELDS}, read back from the
     *                 live configuration <em>after</em> it was applied. Values are read back rather
     *                 than copied out of the request body because applying is allowed to derive one
     *                 (switching provider fills a blank base URL with that provider's default), and
     *                 a store that held the request instead of the result would hand back something
     *                 the operator never saw.
     */
    public SaveOutcome save(Set<String> touched, Map<String, String> snapshot) {
        if (!isEnabled()) return SaveOutcome.disabled();

        Set<String> owned = new LinkedHashSet<>();
        StoredSettings existing = read(path);
        for (Field field : FIELDS) {
            // Already ours, or being set now. A field the operator has never touched keeps taking
            // its value from the deployment's own configuration, which is where it belongs.
            if (existing.values().containsKey(field.property())
                || existing.secretsOmitted().contains(field.property())
                || touched.contains(field.apiField())) {
                owned.add(field.apiField());
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        List<String> omittedProperties = new ArrayList<>();
        List<String> omittedFields = new ArrayList<>();
        for (Field field : FIELDS) {
            if (!owned.contains(field.apiField())) continue;
            String value = snapshot.get(field.apiField());
            if (value == null) continue;
            if (field.secret() && !storeSecrets) {
                // Only worth naming when there is something to lose: an empty password is not a
                // credential this store is failing to keep.
                if (!value.isBlank()) {
                    omittedProperties.add(field.property());
                    omittedFields.add(field.apiField());
                }
                continue;
            }
            values.put(field.property(), value);
        }

        try {
            write(values, omittedProperties);
            return new SaveOutcome(true, path.toString(), List.copyOf(omittedFields), null);
        } catch (Exception e) {
            String reason = SqlErrorClassifier.explain(e);
            // WARN and a truthful answer, never a failed save: the settings are applied and this
            // process is using them. What the operator must not be left believing is that they
            // will still be there after a restart.
            log.warn("Settings were applied but not written to {}: {}. They will be lost when this "
                + "process stops.", path, reason);
            return new SaveOutcome(false, path.toString(), List.copyOf(omittedFields), reason);
        }
    }

    /** Replaces the file in one step — see {@link JsonStoreFile#replace}. */
    private void write(Map<String, String> values, List<String> omitted) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("savedAt", Instant.now().toString());
        root.put("comment", "Written by the Kafka Explorer Settings page. Values here override "
            + "application.yml at startup, and are themselves overridden by environment variables.");
        ObjectNode settings = root.putObject("settings");
        values.forEach(settings::put);
        if (!omitted.isEmpty()) {
            root.putArray("secretsOmitted").addAll(
                omitted.stream().map(MAPPER.getNodeFactory()::textNode).toList());
        }
        JsonStoreFile.replace(MAPPER, path, root);
    }
}
