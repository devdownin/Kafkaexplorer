// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The field mappings validated by the Process Mining pipeline, kept for the life of the process.
 *
 * <p>This used to be a {@code ConcurrentHashMap} field on {@code ProcessMiningController}, which
 * made it unreachable to anything else — and the mapping is the one place in this application that
 * knows a topic's <em>real</em> correlation key rather than an assumed {@code id}. The Metrics
 * suggestions want exactly that, so the cache became a component both can read.
 *
 * <p>Bounded while moving it, because nothing ever evicted an entry: every validation added a
 * mapping that stayed for the life of the JVM. Access-ordered, so the {@link #MAX_ENTRIES} kept
 * are the ones still being used rather than the oldest ever created — a mapping is re-read on
 * every analysis it drives.
 */
@Component
public class FieldMappingStore {

    /** Enough for any realistic number of concurrent pipelines; a mapping is a few hundred bytes. */
    static final int MAX_ENTRIES = 50;

    private final Map<String, FieldMapping> mappings = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FieldMapping> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    public void put(FieldMapping mapping) {
        if (mapping == null || mapping.id() == null) return;
        mappings.put(mapping.id(), mapping);
    }

    /** The mapping with that id, or empty — a restart loses them, and callers must say so. */
    public Optional<FieldMapping> find(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(mappings.get(id));
    }
}
