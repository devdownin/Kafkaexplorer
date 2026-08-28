// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The second thing this application writes to a cluster, so it is tested like the first: what it
 * may touch, what it must not, and that a failure costs nothing.
 */
class InternalTopicProvisionerTest {

    private KafkaAdminService admin;
    private ExplorerConfig config;
    private InternalTopicProvisioner provisioner;

    @BeforeEach
    void setUp() throws Exception {
        admin = Mockito.mock(KafkaAdminService.class);
        config = new ExplorerConfig();
        provisioner = new InternalTopicProvisioner(admin, config);
        when(admin.createTopicIfAbsent(anyString(), anyMap())).thenReturn(true);
    }

    @Test
    void theKeyedStoresAreCreatedCompacted() throws Exception {
        provisioner.provision();

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> configs = ArgumentCaptor.forClass(Map.class);
        verify(admin, Mockito.times(3)).createTopicIfAbsent(names.capture(), configs.capture());

        int metrics = names.getAllValues().indexOf("internal.metrics.config");
        int mappings = names.getAllValues().indexOf("internal.field.mappings");
        assertEquals("compact", configs.getAllValues().get(metrics).get("cleanup.policy"));
        assertEquals("compact", configs.getAllValues().get(mappings).get("cleanup.policy"));
        // A retention beside compaction would delete by age the very records compaction keeps.
        assertFalse(configs.getAllValues().get(metrics).containsKey("retention.ms"));
    }

    @Test
    void theHistoryTopicCarriesTheConfiguredRetention() throws Exception {
        config.setAuditHistoryRetentionMs(86_400_000L);

        provisioner.provision();

        verify(admin).createTopicIfAbsent(eq("internal.audit.history"),
            eq(Map.of("cleanup.policy", "delete", "retention.ms", "86400000")));
    }

    /**
     * Zero is "leave the broker's retention alone" — a third answer, not a zero-length one.
     * Writing {@code retention.ms=0} would delete every report as it landed.
     */
    @Test
    void aRetentionOfZeroSetsNoRetentionAtAll() throws Exception {
        config.setAuditHistoryRetentionMs(0);

        provisioner.provision();

        verify(admin).createTopicIfAbsent("internal.audit.history", Map.of("cleanup.policy", "delete"));
    }

    /** It renames nothing of the user's: the names come from the configured prefix. */
    @Test
    void itFollowsTheConfiguredInternalTopicPrefix() throws Exception {
        config.setInternalTopicPrefix("acme");

        provisioner.provision();

        verify(admin).createTopicIfAbsent(eq("acme.internal.metrics.config"), anyMap());
        verify(admin, never()).createTopicIfAbsent(eq("internal.metrics.config"), anyMap());
    }

    @Test
    void nothingIsCreatedWhenProvisioningIsOff() throws Exception {
        config.setInternalTopicProvisioning(false);

        assertEquals(List.of(), provisioner.provision());
        verify(admin, never()).createTopicIfAbsent(anyString(), anyMap());
    }

    /**
     * An existing topic is reported, not corrected: it may have been configured deliberately, and
     * altering someone's topic is a different act from creating one.
     */
    @Test
    void anExistingTopicWithTheWrongPolicyIsNotAlteredByDefault() throws Exception {
        when(admin.createTopicIfAbsent(anyString(), anyMap())).thenReturn(false);
        when(admin.getTopicConfigs(anyString())).thenReturn(Map.of("cleanup.policy", "delete"));

        provisioner.provision();

        verify(admin, never()).alterTopicConfigs(anyString(), anyMap());
    }

    @Test
    void reconcileFixesTheKeyedStores() throws Exception {
        config.setInternalTopicReconcile(true);
        when(admin.createTopicIfAbsent(anyString(), anyMap())).thenReturn(false);
        when(admin.getTopicConfigs(anyString())).thenReturn(Map.of("cleanup.policy", "delete"));

        provisioner.provision();

        verify(admin).alterTopicConfigs("internal.metrics.config", Map.of("cleanup.policy", "compact"));
        verify(admin).alterTopicConfigs("internal.field.mappings", Map.of("cleanup.policy", "compact"));
    }

    /** A configuration that could not be read is not a finding about the topic. */
    @Test
    void anUnreadableConfigurationChangesNothing() throws Exception {
        config.setInternalTopicReconcile(true);
        when(admin.createTopicIfAbsent(anyString(), anyMap())).thenReturn(false);
        when(admin.getTopicConfigs(anyString())).thenReturn(Map.of());

        provisioner.provision();

        verify(admin, never()).alterTopicConfigs(anyString(), anyMap());
    }

    /** One topic costs itself: the other two are separate stores. */
    @Test
    void aFailureOnOneTopicDoesNotCostTheOthers() throws Exception {
        when(admin.createTopicIfAbsent(eq("internal.metrics.config"), anyMap()))
            .thenThrow(new RuntimeException("refused"));

        List<String> created = provisioner.provision();

        assertEquals(2, created.size());
        assertFalse(created.contains("internal.metrics.config"));
    }

    @Test
    void startupNeverThrows() throws Exception {
        when(admin.createTopicIfAbsent(anyString(), anyMap())).thenThrow(new RuntimeException("no broker"));

        provisioner.provisionOnStartup();   // must not propagate
    }

    /**
     * {@code cleanup.policy} is a list. A topic set to {@code compact,delete} does compact, which
     * is the property the store depends on; whether it also deletes by age is the operator's
     * business.
     */
    @Test
    void aCompoundCleanupPolicySatisfiesCompact() {
        assertTrue(InternalTopicProvisioner.satisfies("cleanup.policy", "compact,delete", "compact"));
        assertTrue(InternalTopicProvisioner.satisfies("cleanup.policy", "delete,compact", "delete"));
        assertFalse(InternalTopicProvisioner.satisfies("cleanup.policy", "delete", "compact"));
        assertFalse(InternalTopicProvisioner.satisfies("retention.ms", "600000", "86400000"));
    }
}
