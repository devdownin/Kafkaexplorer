// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code explorer.internal-topic-prefix} reaches the three topic names this application
 * writes for itself, and only those.
 *
 * <p>Driven against a real context for the same reason as {@code ExplorerConsumerGroupPrefixTest}:
 * the value is bound by one post-processor and resolved by a {@code @PostConstruct}, so a typo in
 * the property name — or the two running in the other order — would leave the prefix silently
 * unapplied, and every unit test that calls the getters would still pass.
 */
class InternalTopicPrefixTest {

    @EnableConfigurationProperties
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Binding.class, ExplorerConfig.class);

    /** Absent, the topics keep exactly the names they have always had. */
    @Test
    void anAbsentPropertyChangesNothing() {
        runner.run(context -> {
            ExplorerConfig config = context.getBean(ExplorerConfig.class);
            assertEquals("internal.audit.history", config.getAuditHistoryTopic());
            assertEquals("internal.metrics.config", config.getMetricsConfigTopic());
            assertEquals("internal.field.mappings", config.getFieldMappingTopic());
        });
    }

    /** An empty value is the same answer as an absent one — the property is opt-in. */
    @Test
    void anEmptyPropertyChangesNothing() {
        runner.withPropertyValues("explorer.internal-topic-prefix=").run(context ->
            assertEquals("internal.audit.history",
                         context.getBean(ExplorerConfig.class).getAuditHistoryTopic()));
    }

    @Test
    void aConfiguredPrefixReachesAllThreeStores() {
        runner.withPropertyValues("explorer.internal-topic-prefix=acme.").run(context -> {
            ExplorerConfig config = context.getBean(ExplorerConfig.class);
            assertEquals("acme.internal.audit.history", config.getAuditHistoryTopic());
            assertEquals("acme.internal.metrics.config", config.getMetricsConfigTopic());
            assertEquals("acme.internal.field.mappings", config.getFieldMappingTopic());
        });
    }

    /** {@code acme} would otherwise yield {@code acmeinternal.audit.history}. */
    @Test
    void aPrefixWithoutASeparatorGetsOne() {
        runner.withPropertyValues("explorer.internal-topic-prefix=acme").run(context ->
            assertEquals("acme.internal.audit.history",
                         context.getBean(ExplorerConfig.class).getAuditHistoryTopic()));
        runner.withPropertyValues("explorer.internal-topic-prefix=acme_").run(context ->
            assertEquals("acme_internal.audit.history",
                         context.getBean(ExplorerConfig.class).getAuditHistoryTopic()));
    }

    /**
     * A prefix Kafka could not accept in a topic name is refused rather than applied: the three
     * stores would fail to write with nothing saying why, which is worse than no prefix.
     */
    @Test
    void anUnusablePrefixIsRefusedAndTheRunContinues() {
        runner.withPropertyValues("explorer.internal-topic-prefix=acme corp/prod").run(context ->
            assertEquals("internal.audit.history",
                         context.getBean(ExplorerConfig.class).getAuditHistoryTopic()));
    }

    /** Whitespace around a value pasted from a console is not a different prefix. */
    @Test
    void aPaddedValueIsTrimmed() {
        runner.withPropertyValues("explorer.internal-topic-prefix=  acme.  ").run(context ->
            assertEquals("acme.internal.audit.history",
                         context.getBean(ExplorerConfig.class).getAuditHistoryTopic()));
    }

    /**
     * The "is this one of ours?" test follows the prefix. It is what the KPI suggestions filter
     * on, and it was a literal {@code "internal."} that stopped being the marker the moment a
     * prefix could be configured.
     */
    @Test
    void theInternalTopicTestFollowsThePrefix() {
        runner.run(context -> {
            ExplorerConfig config = context.getBean(ExplorerConfig.class);
            assertTrue(config.isInternalTopic("internal.audit.history"));
            assertTrue(config.isInternalTopic("internal.demo.seeded"));
            assertFalse(config.isInternalTopic("demo.orders.1.received"));
            assertFalse(config.isInternalTopic(null));
        });
        runner.withPropertyValues("explorer.internal-topic-prefix=acme.").run(context -> {
            ExplorerConfig config = context.getBean(ExplorerConfig.class);
            assertTrue(config.isInternalTopic("acme.internal.audit.history"));
            // Sans le préfixe, ce n'est plus un topic de cette instance : c'est peut-être celui
            // d'une autre, qui partage le cluster — précisément ce que le préfixe sert à séparer.
            assertFalse(config.isInternalTopic("internal.audit.history"));
        });
    }
}
