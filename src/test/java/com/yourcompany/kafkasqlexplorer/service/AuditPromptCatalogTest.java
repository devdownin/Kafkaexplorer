// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.AuditPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AuditPromptCatalogTest {

    private final AuditPromptCatalog catalog = new AuditPromptCatalog();

    @Test
    void catalogIsNonEmptyWithUniqueIdsAndCompleteFields() {
        List<AuditPrompt> all = catalog.all();
        assertFalse(all.isEmpty());

        Set<String> ids = all.stream().map(AuditPrompt::id).collect(Collectors.toSet());
        assertEquals(all.size(), ids.size(), "ids must be unique");

        for (AuditPrompt p : all) {
            assertNotNull(p.id());
            assertFalse(p.name().isBlank());
            assertFalse(p.category().isBlank());
            assertFalse(p.description().isBlank());
            assertFalse(p.prompt().isBlank());
        }
    }

    @Test
    void findByIdsPreservesCatalogOrderAndSkipsUnknown() {
        List<String> catalogOrder = catalog.all().stream().map(AuditPrompt::id).toList();
        String first = catalogOrder.get(0);
        String third = catalogOrder.get(2);

        // Request them out of order + an unknown id
        List<AuditPrompt> found = catalog.findByIds(List.of(third, "does-not-exist", first));

        assertEquals(2, found.size());
        // Order follows the catalog, not the request
        assertEquals(first, found.get(0).id());
        assertEquals(third, found.get(1).id());
    }

    @Test
    void findByIdsReturnsEmptyForNullOrEmpty() {
        assertTrue(catalog.findByIds(null).isEmpty());
        assertTrue(catalog.findByIds(List.of()).isEmpty());
    }
}
