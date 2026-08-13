// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldProfileResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FieldProfilingServiceTest {

    private static final PayloadDigestService DIGEST_SERVICE =
        new PayloadDigestService(new ProcessMiningConfig());

    private KafkaSnapshotReader snapshotReader;
    private ClaudeConfig claudeConfig;
    private FieldProfilingService fieldProfilingService;

    /** Digests a raw payload the way the snapshot reader does, so tests exercise the real format. */
    private static PayloadDigest digestOf(String topic, String key, String value) {
        return DIGEST_SERVICE.digest(topic, 0, 1L, 1000L, key,
            value.getBytes(StandardCharsets.UTF_8), Set.of());
    }

    @BeforeEach
    void setUp() {
        snapshotReader = mock(KafkaSnapshotReader.class);
        claudeConfig = new ClaudeConfig();
        claudeConfig.setApiKey("test-key");
        // Using actual service but it will internally create a client
        // For testing we might want to mock the client, but it's created in constructor
    }

    @Test
    void testProfileWithMissingApiKeyAnthropic() {
        claudeConfig.setProvider(ClaudeConfig.Provider.ANTHROPIC);
        claudeConfig.setApiKey("");
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, mock(LlmClient.class));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertTrue(result.warnings().contains("LLM API key not configured."));
    }

    @Test
    void testProfileWithMissingApiKeyOpenAiCompatible() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);
        claudeConfig.setApiKey("");
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));
        when(llmClient.generate(anyString(), anyString())).thenReturn("{\"topics\": [], \"warnings\": []}");

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testProfileSuccess() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));

        when(llmClient.generate(anyString(), anyString())).thenReturn("{\"topics\": [], \"warnings\": []}");

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testProfileParsesJsonWrappedInProse() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));

        when(llmClient.generate(anyString(), anyString())).thenReturn("""
            Here is the requested payload:
            ```json
            {"topics": [], "warnings": []}
            ```
            """);

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }
}
