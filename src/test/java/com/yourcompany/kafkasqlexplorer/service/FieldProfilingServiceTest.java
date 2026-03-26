// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.domain.FieldProfileResult;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FieldProfilingServiceTest {

    private KafkaSnapshotReader snapshotReader;
    private ClaudeConfig claudeConfig;
    private FieldProfilingService fieldProfilingService;

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
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig);

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertTrue(result.warnings().contains("LLM API key not configured."));
    }

    @Test
    void testProfileWithMissingApiKeyOpenAiCompatible() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);
        claudeConfig.setApiKey("");
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"id\":1}")
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

        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"id\":1}")
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

        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"id\":1}")
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
