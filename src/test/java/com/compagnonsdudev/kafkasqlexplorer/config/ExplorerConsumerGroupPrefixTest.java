// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import com.compagnonsdudev.kafkasqlexplorer.service.ExplorerConsumerGroups;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@code explorer.consumer-group-prefix} actually reaches the class that names internal
 * consumers.
 *
 * <p>Worth pinning against a real context rather than reasoning about, for the same reason
 * {@code HealthProbesTest} exists: the value is bound by one bean post-processor and applied by
 * another ({@code @PostConstruct}), and if those ran in the other order — or if the property name
 * carried a typo — the prefix would silently stay at its default. Nothing else in the suite would
 * notice, because every unit test calls {@code usePrefix} directly.
 */
class ExplorerConsumerGroupPrefixTest {

    @EnableConfigurationProperties
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Binding.class, ExplorerConfig.class);

    /** Le préfixe est un état statique : sans remise à zéro, il fuit vers les tests suivants. */
    @AfterEach
    void restoreDefaultPrefix() {
        ExplorerConsumerGroups.usePrefix(null);
    }

    @Test
    void aConfiguredPrefixIsBoundAndApplied() {
        runner.withPropertyValues("explorer.consumer-group-prefix=acme.kse.").run(context -> {
            assertEquals("acme.kse.", context.getBean(ExplorerConfig.class).getConsumerGroupPrefix());
            assertEquals("acme.kse.", ExplorerConsumerGroups.prefix());
            assertTrue(ExplorerConsumerGroups.transientGroup("metadata").startsWith("acme.kse.metadata-"));
        });
    }

    @Test
    void anAbsentPropertyLeavesTheDefaultInForce() {
        runner.run(context -> assertEquals(ExplorerConsumerGroups.DEFAULT_PREFIX,
                                           ExplorerConsumerGroups.prefix()));
    }

    @Test
    void anEmptyPropertyLeavesTheDefaultInForce() {
        runner.withPropertyValues("explorer.consumer-group-prefix=").run(context ->
            assertEquals(ExplorerConsumerGroups.DEFAULT_PREFIX, ExplorerConsumerGroups.prefix()));
    }

    /** Le séparateur manquant est ajouté sur le chemin réel, pas seulement dans `resolvePrefix`. */
    @Test
    void aPrefixWithoutASeparatorIsNormalisedOnTheWayIn() {
        runner.withPropertyValues("explorer.consumer-group-prefix=myapp").run(context ->
            assertEquals("myapp-", ExplorerConsumerGroups.prefix()));
    }
}
