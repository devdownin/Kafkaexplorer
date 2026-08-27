// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmKeyStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelCheck;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmTestResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelShortlist;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClient;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClientFactory;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClientProvider;
import com.compagnonsdudev.kafkasqlexplorer.service.OpenRouterModelCatalog;
import com.compagnonsdudev.kafkasqlexplorer.service.SettingsStore;
import com.compagnonsdudev.kafkasqlexplorer.service.SseEmitterManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings, under {@code /api} only.
 *
 * <p>There used to be a {@code @GetMapping("/config")} returning the view name {@code "config"}.
 * {@code /config} is a client-side route (the Settings page) and there is no template engine, so
 * that mapping shadowed {@link SpaController} and answered a page refresh with a circular-view-path
 * 500 — the same trap already documented for {@code /stream-flow} and {@code /audit}. The
 * form-encoded {@code POST /config} went with it: the SPA posts JSON to {@code /api/config}.
 */
@RestController
public class ConfigController {

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;
    private final ClaudeConfig claudeConfig;
    private final AuditService auditService;
    private final FlinkSqlService flinkSqlService;
    private final SseEmitterManager sseEmitterManager;
    private final LlmClientProvider llmClientProvider;
    private final SettingsStore settingsStore;
    private final OpenRouterModelCatalog modelCatalog;

    public ConfigController(KafkaConfig kafkaConfig,
                            KafkaAdminService kafkaAdminService,
                            ClaudeConfig claudeConfig,
                            AuditService auditService,
                            FlinkSqlService flinkSqlService,
                            SseEmitterManager sseEmitterManager,
                            LlmClientProvider llmClientProvider,
                            SettingsStore settingsStore,
                            OpenRouterModelCatalog modelCatalog) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.claudeConfig = claudeConfig;
        this.auditService = auditService;
        this.flinkSqlService = flinkSqlService;
        this.sseEmitterManager = sseEmitterManager;
        this.llmClientProvider = llmClientProvider;
        this.settingsStore = settingsStore;
        this.modelCatalog = modelCatalog;
    }

    @GetMapping("/api/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("clusters", kafkaConfig.getClusters());
        result.put("isConnected", kafkaAdminService.ping());
        appendConnectionDetail(result);
        appendLlmConfig(result);
        appendPersistence(result);
        return result;
    }

    /**
     * The rest of what the connection is actually made with — <b>paths and the account name, never
     * the passwords</b>.
     *
     * <p>These were absent, so the SSL and Confluent Cloud sections of the Settings page opened
     * empty whatever the application was running on: the fields could be written and never read
     * back. That was survivable while nothing was kept between restarts, and stops being so now
     * that it is — an operator who restarts and finds the keystore path blank has every reason to
     * conclude their input was lost, when it is in force.
     *
     * <p>The credentials stay out, on the rule {@code llmApiKeyConfigured} already follows: the
     * page needs to know whether one is set, not what it is.
     */
    private void appendConnectionDetail(Map<String, Object> result) {
        result.put("truststorePath", kafkaConfig.getTruststorePath());
        result.put("keystorePath", kafkaConfig.getKeystorePath());
        result.put("confluentKey", kafkaConfig.getConfluentKey());
        result.put("truststorePasswordConfigured", isSet(kafkaConfig.getTruststorePassword()));
        result.put("keystorePasswordConfigured", isSet(kafkaConfig.getKeystorePassword()));
        result.put("keyPasswordConfigured", isSet(kafkaConfig.getKeyPassword()));
        result.put("confluentSecretConfigured", isSet(kafkaConfig.getConfluentSecret()));
    }

    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Whether what is typed on this page will still be here after a restart.
     *
     * <p>The page has to be able to say so, because the two answers call for different behaviour
     * from whoever is filling it in: on a deployment with persistence off, or with a store that
     * cannot be written, the settings are a session's worth of work and the real fix belongs in the
     * deployment's own configuration. Silence would let it read as the reassuring case.
     *
     * <p>Never the values themselves — {@code storesSecrets} is a boolean for the same reason
     * {@code llmApiKeyConfigured} is one.
     */
    private void appendPersistence(Map<String, Object> result) {
        result.put("settingsPersisted", settingsStore.isEnabled());
        result.put("settingsStoreSecrets", settingsStore.storesSecrets());
        java.nio.file.Path path = settingsStore.storePath();
        result.put("settingsStorePath", path == null ? null : path.toString());
        SettingsStore.StoredSettings stored = settingsStore.current();
        result.put("settingsSavedAt", stored.savedAt());
        result.put("settingsStoredFields", stored.values().size());
    }

    /**
     * Work that a settings change would pull the ground out from under.
     *
     * <p>Repointing Kafka mutates shared singletons and re-initialises the admin client, while an
     * audit keeps scanning, Flink jobs keep streaming and a live Process Mining session keeps
     * polling — all against the cluster that was configured when they started. The report, the job
     * and the session would then describe two different clusters under one heading. Nothing here
     * stops that work: it is bounded and cancellable through its own screens, and killing it from
     * a settings save would be a worse surprise than refusing the save.
     */
    private List<String> workInFlight() {
        List<String> running = new ArrayList<>();
        String auditId = auditService.runningAuditId();
        if (auditId != null) {
            running.add("an audit (" + auditId + ")");
        }
        int jobs = flinkSqlService.getActiveJobsDetails().size();
        if (jobs > 0) {
            running.add(jobs + " Flink job" + (jobs > 1 ? "s" : ""));
        }
        int sessions = sseEmitterManager.activeSessions();
        if (sessions > 0) {
            running.add(sessions + " live Process Mining session" + (sessions > 1 ? "s" : ""));
        }
        return running;
    }

    /**
     * What the settings in {@code body} would produce, checked before anything is written.
     *
     * <p>Two different failures used to reach the operator as a 500 with no body. A mode this
     * application does not know was accepted outright and silently connected in plaintext (see
     * {@link KafkaConfig#problemsWith}); a mistyped provider or a non-numeric timeout threw out of
     * {@code valueOf} / {@code parseInt} halfway through {@code applyConfig}, which mutates the
     * shared singletons field by field — so the save "failed" with half of it applied. Everything
     * is checked first, and the answer names the field.
     */
    private List<String> settingsProblems(Map<String, Object> body) {
        List<String> problems = new ArrayList<>(KafkaConfig.problemsWith(
            effective(body, "bootstrapServers", kafkaConfig.getBootstrapServers()),
            effective(body, "mode", kafkaConfig.getMode()),
            effective(body, "confluentKey", kafkaConfig.getConfluentKey()),
            effective(body, "confluentSecret", kafkaConfig.getConfluentSecret()),
            effective(body, "truststorePath", kafkaConfig.getTruststorePath()),
            effective(body, "keystorePath", kafkaConfig.getKeystorePath())));

        checkEndpoint(body, problems);
        checkEnum(body, "llmProvider", ClaudeConfig.Provider.class, problems);
        checkEnum(body, "llmStructuredOutput", ClaudeConfig.StructuredOutput.class, problems);
        checkEnum(body, "llmOpenrouterDataCollection", ClaudeConfig.DataCollection.class, problems);
        for (String field : List.of("llmRequestTimeoutSeconds", "llmMaxTokens",
                                    "llmSnapshotWindowSize", "llmSnapshotWindowTimeoutSeconds")) {
            checkPositiveInt(body, field, problems);
        }
        return problems;
    }

    /** The value this save would leave in place: what it carries, or what is already stored. */
    private String effective(Map<String, Object> body, String key, String current) {
        return body.containsKey(key) && body.get(key) != null ? asString(body.get(key)) : current;
    }

    private <E extends Enum<E>> void checkEnum(Map<String, Object> body, String field,
                                               Class<E> type, List<String> problems) {
        if (!body.containsKey(field) || body.get(field) == null) return;
        String value = asString(body.get(field));
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(value)) return;
        }
        problems.add(field + " is '" + value + "', which is not one of "
            + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")) + ".");
    }

    /**
     * A base URL that was typed has to be an address something can be sent to.
     *
     * <p>Only when it is non-blank, deliberately: blank means "use the provider's own default",
     * which is a legitimate thing to save — and for {@code OPENAI_COMPATIBLE} there is no default,
     * a state {@link ClaudeConfig#configurationProblem()} reports when a call is attempted rather
     * than one this refuses. What is refused here is the mistake that cannot mean anything:
     * {@code gpu-box:11434}, which {@code java.net.URI} parses happily as a scheme of its own and
     * the HTTP client then rejects several layers from the operator who typed it.
     */
    private void checkEndpoint(Map<String, Object> body, List<String> problems) {
        if (!body.containsKey("llmBaseUrl") || body.get("llmBaseUrl") == null) return;
        String value = asString(body.get("llmBaseUrl"));
        if (isBlank(value) || ClaudeConfig.isAbsoluteHttpUrl(value)) return;
        problems.add("llmBaseUrl is '" + value + "', which is not an absolute http(s) URL — it "
            + "needs the scheme and the host, for example http://gpu-box:11434/v1.");
    }

    private void checkPositiveInt(Map<String, Object> body, String field, List<String> problems) {
        if (!body.containsKey(field) || body.get(field) == null) return;
        String value = asString(body.get(field));
        try {
            if (Integer.parseInt(value) <= 0) {
                problems.add(field + " is " + value + "; it has to be greater than zero.");
            }
        } catch (NumberFormatException e) {
            problems.add(field + " is '" + value + "', which is not a whole number.");
        }
    }

    /**
     * @param body {@code force: true} proceeds anyway — the operator who knows what is running is
     *             not blocked, they are told
     */
    @PostMapping(value = "/api/config", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        List<String> problems = settingsProblems(body);
        if (!problems.isEmpty()) {
            Map<String, Object> rejected = new HashMap<>();
            rejected.put("message", "Not applied: " + String.join(" ", problems));
            rejected.put("problems", problems);
            return ResponseEntity.badRequest().body(rejected);
        }

        boolean repointsCluster = body.containsKey("bootstrapServers") || body.containsKey("mode");
        if (repointsCluster && !Boolean.parseBoolean(asString(body.get("force")))) {
            List<String> running = workInFlight();
            if (!running.isEmpty()) {
                Map<String, Object> conflict = new HashMap<>();
                conflict.put("message", "Not applied: " + String.join(", ", running)
                    + " still running against the current cluster. Stop it, or send force=true to "
                    + "repoint anyway — what is already running keeps reading the previous cluster.");
                conflict.put("inFlight", running);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
            }
        }
        return ResponseEntity.ok(applyConfig(body));
    }

    private Map<String, Object> applyConfig(Map<String, Object> body) {
        ClaudeConfig.Provider previousProvider = claudeConfig.getProvider();
        Set<String> touched = touchedFields(body, settingsSnapshot());

        if (body.containsKey("bootstrapServers") && body.get("bootstrapServers") != null) {
            kafkaConfig.setBootstrapServers(asString(body.get("bootstrapServers")));
        }
        if (body.containsKey("mode") && body.get("mode") != null) {
            kafkaConfig.setMode(asString(body.get("mode")));
        }
        if (body.containsKey("truststorePath")) kafkaConfig.setTruststorePath(asString(body.get("truststorePath")));
        if (body.containsKey("truststorePassword")) kafkaConfig.setTruststorePassword(asString(body.get("truststorePassword")));
        if (body.containsKey("keystorePath")) kafkaConfig.setKeystorePath(asString(body.get("keystorePath")));
        if (body.containsKey("keystorePassword")) kafkaConfig.setKeystorePassword(asString(body.get("keystorePassword")));
        if (body.containsKey("keyPassword")) kafkaConfig.setKeyPassword(asString(body.get("keyPassword")));
        if (body.containsKey("confluentKey")) kafkaConfig.setConfluentKey(asString(body.get("confluentKey")));
        if (body.containsKey("confluentSecret")) kafkaConfig.setConfluentSecret(asString(body.get("confluentSecret")));

        // Where the model was being called before this request, kept so a credential cannot follow
        // the endpoint somewhere the operator did not deliberately send it — the block after the
        // LLM fields below acts on it. Read here rather than at the top: the provider is what
        // decides a blank base URL's meaning, and it has not changed yet at this point.
        String previousEndpoint = claudeConfig.getResolvedBaseUrl();
        boolean keySupplied = body.containsKey("llmApiKey")
            && !isBlank(asString(body.get("llmApiKey")));

        if (body.containsKey("llmProvider") && body.get("llmProvider") != null) {
            claudeConfig.setProvider(ClaudeConfig.Provider.valueOf(asString(body.get("llmProvider"))));
        }
        if (body.containsKey("llmApiKey")) {
            claudeConfig.setApiKey(asString(body.get("llmApiKey")));
        }
        if (body.containsKey("llmBaseUrl")) {
            claudeConfig.setBaseUrl(asString(body.get("llmBaseUrl")));
        } else if (previousProvider != claudeConfig.getProvider()
            && isBlank(claudeConfig.getBaseUrl())) {
            claudeConfig.setBaseUrl(ClaudeConfig.defaultBaseUrl(claudeConfig.getProvider()));
            // A derived value is still one the operator will be looking at, so it has to be stored
            // like an entered one. Without this, switching to Anthropic stored the provider alone
            // and the next boot took the base URL from application.yml — which ships Ollama's —
            // pointing the new provider at the old one's endpoint, silently.
            touched.add("llmBaseUrl");
        }
        if (body.containsKey("llmModel")) {
            claudeConfig.setModel(asString(body.get("llmModel")));
        }
        if (body.containsKey("llmUseRag") && body.get("llmUseRag") != null) {
            claudeConfig.setUseRag(Boolean.parseBoolean(asString(body.get("llmUseRag"))));
        }
        if (body.containsKey("llmCollection")) {
            claudeConfig.setCollection(asString(body.get("llmCollection")));
        }
        if (body.containsKey("llmStructuredOutput") && body.get("llmStructuredOutput") != null) {
            claudeConfig.setStructuredOutput(
                ClaudeConfig.StructuredOutput.valueOf(asString(body.get("llmStructuredOutput"))));
        }
        if (body.containsKey("llmOpenrouterDataCollection")
            && body.get("llmOpenrouterDataCollection") != null) {
            claudeConfig.setOpenrouterDataCollection(
                ClaudeConfig.DataCollection.valueOf(asString(body.get("llmOpenrouterDataCollection"))));
        }
        if (body.containsKey("llmOpenrouterRequireParameters")
            && body.get("llmOpenrouterRequireParameters") != null) {
            claudeConfig.setOpenrouterRequireParameters(
                Boolean.parseBoolean(asString(body.get("llmOpenrouterRequireParameters"))));
        }
        if (body.containsKey("llmRequestTimeoutSeconds") && body.get("llmRequestTimeoutSeconds") != null) {
            claudeConfig.setRequestTimeoutSeconds(Integer.parseInt(asString(body.get("llmRequestTimeoutSeconds"))));
        }
        if (body.containsKey("llmMaxTokens") && body.get("llmMaxTokens") != null) {
            claudeConfig.setMaxTokens(Integer.parseInt(asString(body.get("llmMaxTokens"))));
        }
        if (body.containsKey("llmSnapshotWindowSize") && body.get("llmSnapshotWindowSize") != null) {
            claudeConfig.setSnapshotWindowSize(Integer.parseInt(asString(body.get("llmSnapshotWindowSize"))));
        }
        if (body.containsKey("llmSnapshotWindowTimeoutSeconds")
            && body.get("llmSnapshotWindowTimeoutSeconds") != null) {
            claudeConfig.setSnapshotWindowTimeoutSeconds(
                Integer.parseInt(asString(body.get("llmSnapshotWindowTimeoutSeconds")))
            );
        }

        /*
         * A credential does not follow the endpoint to a different host.
         *
         * `POST /api/config` accepts any base URL and guards the key with containsKey, so a body
         * naming a new host and omitting `llmApiKey` used to repoint the deployment while leaving
         * the stored key in place — and the very next `test-llm` sent that key to the new host,
         * reflecting up to 300 bytes of its answer back to the caller. Two unauthenticated calls,
         * no key needed to start with. This application has no authentication, and "an operator
         * may repoint it" is a different statement from "anyone may have its credentials".
         *
         * The rule is deliberately about the *host* and not the whole URL: a changed port or path
         * is the same endpoint, a changed hostname is not. And it is a save, so re-entering the
         * key is a reasonable thing to ask — which is exactly why the same rule would be wrong on
         * the probe, where nothing is committed and nothing is being decided.
         */
        List<String> credentialsCleared = new ArrayList<>();
        if (!keySupplied && claudeConfig.isApiKeyConfigured()
            && !ClaudeConfig.sameEndpointHost(previousEndpoint, claudeConfig.getResolvedBaseUrl())) {
            claudeConfig.setApiKey("");
            touched.add("llmApiKey");
            credentialsCleared.add("llmApiKey");
        }

        kafkaAdminService.init();

        // After the singletons, not instead of them: the store keeps what was applied, so a
        // derived value (a blank base URL filled in by a provider change, just above) is stored as
        // the operator will see it rather than as the request happened to leave it blank.
        SettingsStore.SaveOutcome saved = settingsStore.save(touched, settingsSnapshot());

        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("isConnected", kafkaAdminService.ping());
        // The same shape a GET answers with, so the page's picture of the connection after a save
        // is the one it would get by reloading. Without it a password the save just cleared went
        // on reporting itself as set, and the field's hint offered to clear it again.
        appendConnectionDetail(result);
        appendLlmConfig(result);
        appendPersistence(result);
        // What the save achieved, stated rather than implied by the 200. A store that could not be
        // written leaves settings that work now and are gone on the next restart, and that is
        // precisely the surprise this whole mechanism exists to remove — so it is reported here,
        // where the page can show it, not only in a log nobody is reading at that moment.
        result.put("settingsPersistedNow", saved.persisted());
        result.put("settingsPersistenceError", saved.error());
        result.put("settingsNotStored", saved.notStored());
        // Said rather than done silently: the operator is looking at a form whose key field is
        // blank either way, so without this the next call fails on a missing credential with
        // nothing connecting the two.
        result.put("credentialsCleared", credentialsCleared);
        return result;
    }

    /**
     * The settings fields this request actually <em>changes</em>.
     *
     * <p>Restricted to {@link SettingsStore#FIELDS}, and it is what stops the store from freezing
     * this release's defaults: only a field an operator has entered is taken over from
     * {@code application.yml}, so a default that changes in a later version still reaches a
     * deployment that never touched it.
     *
     * <p><b>Changed</b>, not merely present, and that distinction is the whole rule rather than a
     * refinement of it. The Settings page posts its entire form — it has to, since Test connection
     * applies before it probes — so "the fields this request carries" is every field there is, and
     * the first click on Test connection would have taken over the lot. Comparing against what is
     * in force before anything is applied puts the rule where no client can miss it: a value equal
     * to the one already running is not something anybody entered.
     *
     * @param before the effective values as they stand, read <em>before</em> {@code applyConfig}
     *               writes anything
     */
    private Set<String> touchedFields(Map<String, Object> body, Map<String, String> before) {
        Set<String> touched = new LinkedHashSet<>();
        for (SettingsStore.Field field : SettingsStore.FIELDS) {
            if (!body.containsKey(field.apiField())) continue;
            Object submitted = body.get(field.apiField());
            if (submitted == null) continue;
            if (!asString(submitted).equals(before.get(field.apiField()))) {
                touched.add(field.apiField());
            }
        }
        return touched;
    }

    /** The effective value of every settings field, read back from the live configuration. */
    private Map<String, String> settingsSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        snapshot.put("mode", kafkaConfig.getMode());
        snapshot.put("truststorePath", kafkaConfig.getTruststorePath());
        snapshot.put("truststorePassword", kafkaConfig.getTruststorePassword());
        snapshot.put("keystorePath", kafkaConfig.getKeystorePath());
        snapshot.put("keystorePassword", kafkaConfig.getKeystorePassword());
        snapshot.put("keyPassword", kafkaConfig.getKeyPassword());
        snapshot.put("confluentKey", kafkaConfig.getConfluentKey());
        snapshot.put("confluentSecret", kafkaConfig.getConfluentSecret());
        snapshot.put("llmProvider", claudeConfig.getProvider().name());
        snapshot.put("llmApiKey", claudeConfig.getApiKey());
        // getBaseUrl(), not getResolvedBaseUrl(): blank means "this provider's default", and
        // storing the resolved value would pin today's default into the file, so a later release
        // that moves it would never reach a deployment that had simply left the field empty.
        snapshot.put("llmBaseUrl", claudeConfig.getBaseUrl());
        snapshot.put("llmModel", claudeConfig.getModel());
        snapshot.put("llmUseRag", String.valueOf(claudeConfig.isUseRag()));
        snapshot.put("llmCollection", claudeConfig.getCollection());
        snapshot.put("llmStructuredOutput", claudeConfig.getStructuredOutput().name());
        snapshot.put("llmOpenrouterDataCollection", claudeConfig.getOpenrouterDataCollection().name());
        snapshot.put("llmOpenrouterRequireParameters",
            String.valueOf(claudeConfig.isOpenrouterRequireParameters()));
        snapshot.put("llmRequestTimeoutSeconds", String.valueOf(claudeConfig.getRequestTimeoutSeconds()));
        snapshot.put("llmMaxTokens", String.valueOf(claudeConfig.getMaxTokens()));
        snapshot.put("llmSnapshotWindowSize", String.valueOf(claudeConfig.getSnapshotWindowSize()));
        snapshot.put("llmSnapshotWindowTimeoutSeconds",
            String.valueOf(claudeConfig.getSnapshotWindowTimeoutSeconds()));
        return snapshot;
    }

    /**
     * Probes an LLM endpoint and reports what its model can do.
     *
     * <p>The body is optional and carries one field, {@code llmModel}. With none, this tests the
     * running configuration exactly as before. With one, it tests a <strong>candidate model</strong>
     * the operator has not committed to — and applies nothing: no bean is mutated, nothing reaches
     * {@link SettingsStore}. The page used to have to save the whole form before it could probe, so
     * trying a model repointed the live deployment and, where persistence is on, wrote it to disk.
     * Exploring and committing were the same gesture, which is why comparing two models was never
     * worth the risk.
     *
     * <p><strong>The endpoint and the credential are never taken from the request</strong> — see
     * {@link ClaudeConfig#probeCopy}. Accepting them turned this into an unauthenticated
     * server-side request forgery whose response came back to the caller, and, since a blank key
     * fell through to the stored one, into a one-call exfiltration of the operator's API key.
     * Repointing this deployment is what {@code POST /api/config} is for.
     *
     * <p>That is why this one builds its own client instead of going through
     * {@link LlmClientProvider}, and the exception is deliberate rather than a lapse: the rule
     * exists because a probe must not claim to have tested what the engine uses when it has not.
     * A candidate probe is testing something the engine explicitly is <em>not</em> using, so it
     * says so in {@code candidate} and names the model it actually called.
     */
    @PostMapping("/api/config/test-llm")
    public LlmTestResponse testLlm(@RequestBody(required = false) Map<String, Object> body) {
        ClaudeConfig target = candidateFrom(body);
        boolean candidate = target != claudeConfig && target.differsFrom(claudeConfig);

        // The key and the address are the two things that make a call impossible before it is
        // made, and only the first was asked about — an OPENAI_COMPATIBLE deployment with no base
        // URL got as far as the HTTP client and came back with "URI with undefined scheme".
        String problem = target.configurationProblem();
        if (problem != null) {
            return new LlmTestResponse(false, problem,
                target.getProviderLabel(), target.getModel(), candidate, null);
        }

        boolean ok;
        String message;

        try {
            // For the running configuration this is the shared provider, not a private client:
            // that endpoint exists to prove what the analyses will use, and building a separate
            // client here is precisely how it came to report a provider reachable that Process
            // Mining was not talking to. A candidate has no shared client by definition.
            LlmClient client = candidate
                ? LlmClientFactory.create(target)
                : llmClientProvider.get();
            String reply = client.generate(
                "You are a connectivity health check. Answer in one short word.",
                "Reply with the word OK.");
            ok = true;
            message = (candidate ? "Candidate reachable via " : "LLM reachable via ")
                + target.getResolvedBaseUrl() + ". Sample reply: " + summarize(reply);
        } catch (Exception e) {
            ok = false;
            message = e.getMessage() != null ? e.getMessage() : e.toString();
        }

        // Deliberately after both branches, and reported on both. "Something answered" is not the
        // question an operator has when Process Mining misbehaves, and on a failure it is the
        // capability report that most often explains it: OpenRouter answers an unroutable model
        // with the same 404 it uses for a mistyped slug, so "this model emits embeddings only"
        // turns an unactionable status into a diagnosis. Never allowed to change the verdict
        // above — this is a side read, and a catalogue that cannot be reached is a catalogue that
        // cannot be reached, not an endpoint that is down.
        LlmModelCheck modelCheck =
            modelCatalog.isSupported(target) ? modelCatalog.describeModel(target) : null;
        // And what is left on the key, on the same press. A key out of credit answers 402 — which
        // `LlmHttpSupport.remedyFor` reads correctly, but only after an analysis has failed. It is
        // published, so this is the moment to say it. Same side-read rules: it never changes the
        // verdict above, and a credit that could not be read is reported as unread.
        LlmKeyStatus keyStatus =
            modelCatalog.isSupported(target) ? modelCatalog.describeKey(target) : null;
        return new LlmTestResponse(ok, message,
            target.getProviderLabel(), target.getModel(), candidate, modelCheck, keyStatus);
    }

    /**
     * The models this application could be pointed at.
     *
     * <p>Read against the endpoint <em>in force</em>, never one named by the caller: the same rule
     * as the probe, and for the same reason — a URL taken from a request is a server-side request
     * forgery whatever it is used for. The consequence is stated on the page: switching provider in
     * the form has to be saved before the list describes the new endpoint.
     */
    @GetMapping("/api/config/llm-models")
    public LlmModelShortlist llmModels(
            @RequestParam(name = "includeUnconstrained", defaultValue = "false")
            boolean includeUnconstrained,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return modelCatalog.shortlist(claudeConfig, includeUnconstrained, limit);
    }

    private String summarize(String reply) {
        if (reply == null || reply.isBlank()) {
            return "(empty)";
        }
        String trimmed = reply.strip();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed;
    }

    /**
     * The configuration a probe should target: the running one when the caller named no model, a
     * throw-away copy naming theirs otherwise. Returns the bean itself in the first case, which is
     * what lets the caller tell "test what is running" from "test this model" by identity.
     *
     * <p>Only {@code llmModel} is read. Anything else in the body is ignored rather than honoured
     * — see {@link ClaudeConfig#probeCopy} for why the endpoint and the credential are not the
     * caller's to choose.
     */
    private ClaudeConfig candidateFrom(Map<String, Object> body) {
        if (body == null) {
            return claudeConfig;
        }
        String model = string(body.get("llmModel"));
        return model == null ? claudeConfig : claudeConfig.probeCopy(model);
    }

    /** Blank counts as absent: an empty form field means "keep what is configured", not "clear it". */
    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text;
    }

    private void appendLlmConfig(Map<String, Object> result) {
        // What each provider gets when it is chosen and nothing is named. Served rather than
        // restated in the browser: Config.tsx carried `openai/gpt-4o-mini` twice and a table
        // mirroring defaultBaseUrl beside it, which is the mirror-drift pattern this codebase
        // keeps removing — the day a shipped default moves, the form offers one model while the
        // engine runs another.
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (ClaudeConfig.Provider provider : ClaudeConfig.Provider.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("baseUrl", ClaudeConfig.defaultBaseUrl(provider));
            entry.put("model", ClaudeConfig.defaultModel(provider));
            defaults.put(provider.name(), entry);
        }
        result.put("llmProviderDefaults", defaults);

        result.put("llmProvider", claudeConfig.getProvider().name());
        result.put("llmProviderLabel", claudeConfig.getProviderLabel());
        result.put("llmApiKeyConfigured", claudeConfig.isApiKeyConfigured());
        result.put("llmApiKeyRequired", claudeConfig.isApiKeyRequired());
        result.put("llmBaseUrl", claudeConfig.getResolvedBaseUrl());
        result.put("llmModel", claudeConfig.getModel());
        result.put("llmUseRag", claudeConfig.isUseRag());
        result.put("llmCollection", claudeConfig.getCollection());
        result.put("llmStructuredOutput", claudeConfig.getStructuredOutput().name());
        result.put("llmOpenrouterDataCollection", claudeConfig.getOpenrouterDataCollection().name());
        result.put("llmOpenrouterRequireParameters", claudeConfig.isOpenrouterRequireParameters());
        // What the banner needs to qualify "remote": whether the gateway was asked to keep
        // message content away from providers that would retain it. False wherever the
        // question cannot be enforced, which is every provider but OpenRouter.
        result.put("llmDataRetentionRefused", claudeConfig.isDataRetentionRefused());
        // What AUTO actually resolves to for the provider in force — the setting alone does not
        // say whether a schema will be sent, and that is the question an operator has.
        result.put("llmStructuredOutputActive", claudeConfig.isStructuredOutputEnabled());
        // Why a call would fail before it is made — a missing key, a missing address, an address
        // that is not one — or null when nothing can be said without asking the endpoint. Both
        // pages that call a model render it: the failure it describes used to arrive after a
        // topic read, from inside the HTTP client, naming no setting.
        result.put("llmConfigurationProblem", claudeConfig.configurationProblem());
        result.put("llmRequestTimeoutSeconds", claudeConfig.getRequestTimeoutSeconds());
        result.put("llmMaxTokens", claudeConfig.getMaxTokens());
        result.put("llmSnapshotWindowSize", claudeConfig.getSnapshotWindowSize());
        result.put("llmSnapshotWindowTimeoutSeconds", claudeConfig.getSnapshotWindowTimeoutSeconds());
        result.put("llmLocalDeployment", claudeConfig.isLocalDeployment());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
