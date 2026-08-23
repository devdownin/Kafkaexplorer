// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClientProvider;
import com.compagnonsdudev.kafkasqlexplorer.service.OpenRouterModelCatalog;
import com.compagnonsdudev.kafkasqlexplorer.service.SettingsStore;
import com.compagnonsdudev.kafkasqlexplorer.service.SseEmitterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settings endpoint's contract.
 *
 * <p>Standalone MockMvc, like {@link StreamFlowControllerTest}: registering only this controller is
 * what makes the "no {@code GET /config} mapping" assertion mean something — that mapping existed,
 * shadowed {@link SpaController} and turned a refresh of the Settings page into a 500.
 */
class ConfigControllerTest {

    private KafkaConfig kafkaConfig;
    private KafkaAdminService kafkaAdminService;
    private AuditService auditService;
    private FlinkSqlService flinkSqlService;
    private SseEmitterManager sseEmitterManager;
    private ClaudeConfig claudeConfig;
    private SettingsStore settingsStore;
    private Path storePath;
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        kafkaConfig = new KafkaConfig();
        kafkaConfig.setBootstrapServers("old:9092");
        claudeConfig = new ClaudeConfig();
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        auditService = Mockito.mock(AuditService.class);
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        sseEmitterManager = Mockito.mock(SseEmitterManager.class);
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Map.of());
        when(sseEmitterManager.activeSessions()).thenReturn(0);

        storePath = tempDir.resolve("settings.json");
        settingsStore = new SettingsStore(explorerConfigStoringAt(storePath.toString(), true));

        mockMvc = MockMvcBuilders
            .standaloneSetup(new ConfigController(kafkaConfig, kafkaAdminService, claudeConfig,
                auditService, flinkSqlService, sseEmitterManager, new LlmClientProvider(claudeConfig),
                settingsStore, modelCatalog()))
            .build();
    }

    /**
     * The point of the candidate probe: trying a model must not repoint the deployment. Before
     * this, the page had to apply the whole form before it could test anything, so exploring and
     * committing were one gesture — and with persistence on, the candidate reached disk.
     */
    @Test
    void probingACandidateChangesNeitherTheRunningConfigNorTheStore() throws Exception {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENROUTER);
        claudeConfig.setModel("openai/gpt-4o-mini");
        claudeConfig.setApiKey("sk-or-configured");

        mockMvc.perform(post("/api/config/test-llm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmModel\":\"some-vendor/candidate\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidate").value(true))
            .andExpect(jsonPath("$.model").value("some-vendor/candidate"));

        assertEquals("openai/gpt-4o-mini", claudeConfig.getModel(),
            "the running configuration must be exactly where it was");
        assertFalse(java.nio.file.Files.exists(storePath),
            "a probe is not a save; nothing may reach the settings store");
    }

    /**
     * The security boundary of the whole feature, and the reason only the model is overridable.
     *
     * <p>A probe that accepted a base URL would be an unauthenticated server-side request forgery
     * with the response handed back to the caller — and since a blank key falls through to the
     * configured one, a single call would post the operator's API key to any host. The endpoint
     * must therefore come from the running configuration whatever the body says.
     */
    @Test
    void aProbeCannotRedirectTheRequestOrBorrowTheStoredKeyForIt() throws Exception {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);
        claudeConfig.setBaseUrl("http://localhost:11434/v1");
        claudeConfig.setModel("qwen3:4b");
        claudeConfig.setApiKey("sk-configured");

        mockMvc.perform(post("/api/config/test-llm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmBaseUrl\":\"https://attacker.example/v1\","
                    + "\"llmProvider\":\"OPENROUTER\",\"llmApiKey\":\"\"}"))
            .andExpect(status().isOk())
            // Neither the endpoint nor the provider moved: the body's connection fields are
            // ignored, not honoured, so nothing was sent anywhere the operator did not configure.
            .andExpect(jsonPath("$.provider").value("Ollama"))
            .andExpect(jsonPath("$.candidate").value(false));

        assertEquals("http://localhost:11434/v1", claudeConfig.getBaseUrl());
        assertEquals(ClaudeConfig.Provider.OLLAMA, claudeConfig.getProvider());
    }

    /** No body at all is the historical call, and it must behave exactly as it did. */
    @Test
    void anEmptyProbeStillTestsWhatIsRunning() throws Exception {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);
        claudeConfig.setModel("qwen3:4b");

        mockMvc.perform(post("/api/config/test-llm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidate").value(false))
            .andExpect(jsonPath("$.model").value("qwen3:4b"));
    }

    /**
     * A body naming the same model as the running configuration is not a candidate. The flag drives
     * what the answer claims to have tested, so it has to follow the difference and not the mere
     * presence of a body.
     */
    @Test
    void abodyThatChangesNothingIsNotReportedAsACandidate() throws Exception {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);
        claudeConfig.setModel("qwen3:4b");

        mockMvc.perform(post("/api/config/test-llm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmModel\":\"qwen3:4b\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidate").value(false));
    }

    /**
     * The defaults the form used to restate.    /**
     * The defaults the form used to restate. Every provider is present, so the page cannot fall
     * back to a literal for the one that was forgotten.
     */
    @Test
    void servesTheDefaultBaseUrlAndModelOfEveryProvider() throws Exception {
        mockMvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llmProviderDefaults.OPENROUTER.baseUrl")
                .value("https://openrouter.ai/api/v1"))
            .andExpect(jsonPath("$.llmProviderDefaults.OPENROUTER.model")
                .value(ClaudeConfig.defaultModel(ClaudeConfig.Provider.OPENROUTER)))
            .andExpect(jsonPath("$.llmProviderDefaults.OLLAMA.model").value("qwen3:4b"))
            // Empty is a real answer here: we have nothing to propose for an endpoint we know
            // nothing about, and for a provider that ignores the field entirely.
            .andExpect(jsonPath("$.llmProviderDefaults.OPENAI_COMPATIBLE.model").value(""))
            .andExpect(jsonPath("$.llmProviderDefaults.SPECTRA.model").value(""));
    }

    /**
     * The shortlist has no catalogue to read on a provider that publishes none — and it reads the
     * provider in force, so no query parameter can send it somewhere else.
     */
    @Test
    void theModelShortlistIsUnavailableWithoutOpenRouterWhateverTheCallerAsksFor() throws Exception {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);

        mockMvc.perform(get("/api/config/llm-models")
                .param("provider", "OPENROUTER")
                .param("baseUrl", "https://attacker.example/v1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.error").isNotEmpty())
            .andExpect(jsonPath("$.models").isEmpty());
    }

    /**
     * The catalogue is a side read on {@code test-llm} against OpenRouter's own host, and these
     * tests are about the settings form. A real one built over the running config is enough: with
     * no key and no network reached, {@code isSupported()} decides whether it is consulted at all.
     */
    private OpenRouterModelCatalog modelCatalog() {
        return new OpenRouterModelCatalog(claudeConfig, new ProcessMiningConfig());
    }

    private static ExplorerConfig explorerConfigStoringAt(String path, boolean secrets) {
        ExplorerConfig config = new ExplorerConfig();
        config.setSettingsStorePath(path);
        config.setSettingsStoreSecrets(secrets);
        return config;
    }

    /** `/config` belongs to the SPA. A controller mapping there answers a refresh with a 500. */
    @Test
    void thereIsNoServerSideConfigPage() throws Exception {
        mockMvc.perform(get("/config")).andExpect(status().isNotFound());
        mockMvc.perform(post("/config").param("bootstrapServers", "new:9092"))
            .andExpect(status().isNotFound());
    }

    /**
     * {@code POST /api/config} repoints the cluster through the very same
     * {@code KafkaConfig.getKafkaProperties()}, so it is the second entry point into the
     * silent-plaintext defect: it accepted any string as a mode and answered 200.
     */
    @Test
    void aModeThatWouldSilentlyBecomePlaintextIsRefused() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SASL_SSL\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("kafka.mode")));

        assertEquals("PLAIN", kafkaConfig.getMode());
        verify(kafkaAdminService, never()).init();
    }

    /**
     * Nothing is applied when anything is refused. {@code applyConfig} mutates shared singletons
     * field by field, so a {@code valueOf} throwing halfway through used to answer 500 with the
     * fields before it already written — a save that "failed" and half happened.
     */
    @Test
    void aMistypedProviderIsRefusedAndLeavesEverythingUntouched() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\",\"llmProvider\":\"OLAMA\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.problems").isArray());

        assertEquals("old:9092", kafkaConfig.getBootstrapServers());
        verify(kafkaAdminService, never()).init();
    }

    @Test
    void aTimeoutThatIsNotANumberIsRefusedRatherThanThrown() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmRequestTimeoutSeconds\":\"soon\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("llmRequestTimeoutSeconds")));
    }

    /** Confluent Cloud without credentials would carry the literal text `null` as the username. */
    @Test
    void repointingToConfluentCloudWithoutCredentialsIsRefused() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CONFLUENT_CLOUD\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("kafka.confluent-key")));
    }

    @Test
    void anIdleClusterAcceptsTheChange() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\"}"))
            .andExpect(status().isOk());

        assertEquals("new:9092", kafkaConfig.getBootstrapServers());
        verify(kafkaAdminService).init();
    }

    /** Repointing under a running audit would have one report describe two clusters. */
    @Test
    void repointingIsRefusedWhileWorkRunsAgainstTheCurrentCluster() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("audit-7")))
            .andExpect(jsonPath("$.inFlight").isArray());

        assertEquals("old:9092", kafkaConfig.getBootstrapServers(), "nothing was applied");
        verify(kafkaAdminService, never()).init();
    }

    /** The operator who knows what is running is told, not blocked. */
    @Test
    void forceAppliesTheChangeAnyway() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\",\"force\":true}"))
            .andExpect(status().isOk());

        assertEquals("new:9092", kafkaConfig.getBootstrapServers());
    }

    /**
     * Only a change of cluster is guarded. Editing an LLM setting while an audit runs touches
     * nothing the audit reads, and refusing it would be a rule with no reason behind it.
     */
    @Test
    void llmSettingsAreNotGuardedByClusterActivity() throws Exception {
        when(auditService.runningAuditId()).thenReturn("audit-7");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmModel\":\"claude-opus-4-6\"}"))
            .andExpect(status().isOk());
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    // The Settings page is the one screen whose whole purpose is data entry, and it was the only
    // one whose input did not survive a restart: applyConfig mutated two singletons and wrote
    // nothing anywhere.

    @Test
    void whatWasAppliedIsWrittenToTheStore() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\",\"llmModel\":\"qwen3:8b\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.settingsPersistedNow").value(true));

        Map<String, String> stored = SettingsStore.read(storePath).values();
        assertEquals("new:9092", stored.get("kafka.bootstrap-servers"));
        assertEquals("qwen3:8b", stored.get("claude.model"));
    }

    /**
     * A field the operator never touched is not taken over from {@code application.yml}.
     *
     * <p>A store that captured the whole configuration would freeze this release's defaults into
     * the file, so a default changed in a later version could never reach the deployment again —
     * and nothing would say why.
     */
    @Test
    void onlyTheFieldsThatWereEnteredAreStored() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"llmModel\":\"qwen3:8b\"}"))
            .andExpect(status().isOk());

        Map<String, String> stored = SettingsStore.read(storePath).values();
        assertEquals(Map.of("claude.model", "qwen3:8b"), stored);
    }

    /**
     * The Settings page posts its <b>entire form</b> — Test connection applies before it probes —
     * so "the fields this request carries" is every field there is. Only what actually differs
     * from what is running counts as entered, or the first click on Test connection would freeze
     * this release's whole configuration into the file.
     */
    @Test
    void submittingTheWholeFormUnchangedTakesOverNothing() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content(wholeFormWithModel(claudeConfig.getModel()))).andExpect(status().isOk());

        assertTrue(SettingsStore.read(storePath).isEmpty(),
            "nothing was changed, so nothing was taken over from application.yml");
    }

    @Test
    void submittingTheWholeFormWithOneChangeTakesOverOnlyThatOne() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content(wholeFormWithModel("some-vendor/some-other-model"))).andExpect(status().isOk());

        assertEquals(Map.of("claude.model", "some-vendor/some-other-model"),
            SettingsStore.read(storePath).values());
    }

    /**
     * The form as the page posts it: every field, carrying what is <em>currently in force</em>
     * except the model. Read off the running configuration rather than written out, because what
     * these two tests are about is the difference between "carried" and "changed" — spelling the
     * shipped defaults into the fixture made them fail the day one of those defaults moved, on a
     * rule that has nothing to say about which provider ships.
     */
    private String wholeFormWithModel(String model) {
        return "{"
            + "\"bootstrapServers\":\"" + kafkaConfig.getBootstrapServers() + "\","
            + "\"mode\":\"" + kafkaConfig.getMode() + "\","
            + "\"llmProvider\":\"" + claudeConfig.getProvider().name() + "\","
            + "\"llmBaseUrl\":\"" + claudeConfig.getBaseUrl() + "\","
            + "\"llmModel\":\"" + model + "\","
            + "\"llmMaxTokens\":\"" + claudeConfig.getMaxTokens() + "\"}";
    }

    /** A second save keeps what the first one took over, and adds to it. */
    @Test
    void aLaterSaveKeepsTheFieldsAnEarlierOneTookOver() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"bootstrapServers\":\"new:9092\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"llmModel\":\"qwen3:8b\"}")).andExpect(status().isOk());

        Map<String, String> stored = SettingsStore.read(storePath).values();
        assertEquals("new:9092", stored.get("kafka.bootstrap-servers"));
        assertEquals("qwen3:8b", stored.get("claude.model"));
    }

    /**
     * The value stored is the one the operator will see, not the one the request carried.
     *
     * <p>Applying is allowed to derive a value: switching provider fills a blank base URL with that
     * provider's default. A store that held the request body would hand back something that was
     * never on screen.
     */
    @Test
    void theStoredValueIsTheOneThatEndedUpInForce() throws Exception {
        claudeConfig.setBaseUrl("");

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"llmProvider\":\"ANTHROPIC\"}")).andExpect(status().isOk());

        Map<String, String> stored = SettingsStore.read(storePath).values();
        assertEquals("ANTHROPIC", stored.get("claude.provider"));
        assertEquals("https://api.anthropic.com", stored.get("claude.base-url"),
            "the base URL applyConfig derived from the provider change");
    }

    /**
     * A credential left out because secrets are off is <b>named</b>, in the answer the page shows.
     *
     * <p>Storing everything except the passwords keeps half the promise: the mode and the keystore
     * path come back, and the connection then fails for a password nothing said had been dropped.
     */
    @Test
    void aSecretThatWasNotStoredIsNamedRatherThanDroppedSilently() throws Exception {
        Path path = tempDir.resolve("no-secrets.json");
        SettingsStore store = new SettingsStore(explorerConfigStoringAt(path.toString(), false));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(kafkaConfig,
            kafkaAdminService, claudeConfig, auditService, flinkSqlService, sseEmitterManager,
            new LlmClientProvider(claudeConfig), store, modelCatalog())).build();

        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keystorePassword\":\"hunter2\",\"keystorePath\":\"/tmp\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.settingsNotStored[0]").value("keystorePassword"));

        SettingsStore.StoredSettings stored = SettingsStore.read(path);
        assertFalse(stored.values().containsKey("kafka.keystore-password"));
        assertTrue(stored.secretsOmitted().contains("kafka.keystore-password"),
            "the boot has to be able to say which credential it is missing");
        assertEquals("/tmp", stored.values().get("kafka.keystore-path"),
            "the rest of the save is still kept");
    }

    /**
     * A store that cannot be written leaves settings that work now and are gone on the next
     * restart. That is exactly the surprise this mechanism exists to remove, so the save says so
     * rather than implying success with a 200.
     */
    @Test
    void aStoreThatCannotBeWrittenIsReportedRatherThanFailingTheSave() throws Exception {
        // A path whose parent is an existing regular file: createDirectories cannot make it.
        Path blocker = tempDir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "not a directory");
        SettingsStore store = new SettingsStore(
            explorerConfigStoringAt(blocker.resolve("settings.json").toString(), true));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(kafkaConfig,
            kafkaAdminService, claudeConfig, auditService, flinkSqlService, sseEmitterManager,
            new LlmClientProvider(claudeConfig), store, modelCatalog())).build();

        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bootstrapServers\":\"new:9092\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.settingsPersistedNow").value(false))
            .andExpect(jsonPath("$.settingsPersistenceError").isNotEmpty());

        assertEquals("new:9092", kafkaConfig.getBootstrapServers(), "still applied to this process");
    }

    /** The page has to be able to say whether what is typed on it will outlive the process. */
    @Test
    void theConfigResponseStatesWhetherSettingsAreKept() throws Exception {
        mockMvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.settingsPersisted").value(true))
            .andExpect(jsonPath("$.settingsStorePath").isNotEmpty());
    }

    /**
     * The connection settings that are not credentials come back, so the page can show what the
     * application is actually running on.
     *
     * <p>They did not, which was survivable while nothing was kept between restarts: the SSL and
     * Confluent sections opened empty whatever was in force, and an operator restarting now would
     * read that as their input having been lost.
     */
    @Test
    void theConfigResponseCarriesThePathsAndTheAccountName() throws Exception {
        kafkaConfig.setTruststorePath("/certs/t.jks");
        kafkaConfig.setKeystorePath("/certs/k.jks");
        kafkaConfig.setConfluentKey("AK123");

        mockMvc.perform(get("/api/config"))
            .andExpect(jsonPath("$.truststorePath").value("/certs/t.jks"))
            .andExpect(jsonPath("$.keystorePath").value("/certs/k.jks"))
            .andExpect(jsonPath("$.confluentKey").value("AK123"));
    }

    /**
     * A password is answered as a boolean, never as itself — the rule
     * {@code llmApiKeyConfigured} already follows. An empty field otherwise cannot distinguish
     * "no password" from "one is set and simply never returned".
     */
    @Test
    void aPasswordIsReportedAsSetWithoutBeingReturned() throws Exception {
        kafkaConfig.setKeystorePassword("hunter2");

        mockMvc.perform(get("/api/config"))
            .andExpect(jsonPath("$.keystorePasswordConfigured").value(true))
            .andExpect(jsonPath("$.truststorePasswordConfigured").value(false))
            .andExpect(jsonPath("$.keystorePassword").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("hunter2"))));
    }

    /** Nothing is written when the request is refused — the store must not diverge from the beans. */
    @Test
    void aRefusedSaveWritesNothing() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"SASL_SSL\"}")).andExpect(status().isBadRequest());

        assertTrue(SettingsStore.read(storePath).isEmpty());
    }

    /** Live Process Mining sessions and Flink jobs count as work in flight too. */
    @Test
    void flinkJobsAndLiveSessionsAlsoRefuseTheChange() throws Exception {
        when(flinkSqlService.getActiveJobsDetails())
            .thenReturn(Map.of("j1", Mockito.mock(FlinkSqlService.JobInfo.class)));
        when(sseEmitterManager.activeSessions()).thenReturn(2);

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SSL\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Flink job")))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("live Process Mining session")));
    }
}
