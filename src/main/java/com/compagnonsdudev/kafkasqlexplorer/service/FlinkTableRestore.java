// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the hand-written table definitions back into Flink's catalogue after startup.
 *
 * <p>The boot half of {@link FlinkTableStore}, and a separate component for the reason
 * {@link FlinkWarmupService} is one: {@code FlinkSqlService} is large enough already, and what
 * this does — listen for readiness, replay on a daemon thread, report — is a lifecycle concern
 * rather than part of the query engine.
 *
 * <p><b>Through {@code executeSql}, not a private path.</b> The statements go back in the way they
 * came out: same whitelist, same validator, same runtime coordinator, same wait budget. A second
 * route into the engine for the same statement is how the two come to behave differently, and a
 * replay that bypassed the guard would be a way to execute stored SQL that the API itself would
 * refuse. Re-storing the definition on the way through is a no-op, since the store is keyed by
 * table name and the statement is unchanged.
 *
 * <p><b>After {@code ApplicationReadyEvent}, on a daemon thread</b> — {@code FlinkWarmupService}'s
 * rule. Each statement takes the runtime's exclusive mutation lock, and boot time here is
 * deliberately short; putting a series of catalogue writes in front of readiness would trade a
 * defect for a worse one. The window this opens is small and closes on its own: a query naming one
 * of these tables in the first moments could auto-register a generated table under the name before
 * the replay reaches it, in which case the replay reports the collision instead of overwriting
 * anything — and the operator's next {@code CREATE TABLE} settles it, exactly as it does today.
 *
 * <p>Everything is best-effort and <b>each failure costs one table</b>, never the rest: a
 * definition that no longer works — a topic that has been deleted, a connector option a Flink
 * upgrade renamed — is reported with its reason and skipped. A restore that stopped at the first
 * bad statement would lose every definition written after it, for a fault in one.
 */
@Service
public class FlinkTableRestore {

    private static final Logger log = LoggerFactory.getLogger(FlinkTableRestore.class);

    /**
     * Bounded like every statement this application submits. Generous, because a
     * {@code CREATE TABLE} against a broker that is slow to answer is still worth waiting for, and
     * nothing is waiting on this thread.
     */
    private static final long PER_TABLE_TIMEOUT_MS = 30_000;

    private final FlinkSqlService flinkSqlService;
    private final FlinkTableStore store;

    public FlinkTableRestore(FlinkSqlService flinkSqlService, FlinkTableStore store) {
        this.flinkSqlService = flinkSqlService;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!store.isEnabled() || store.all().isEmpty()) return;
        Thread thread = new Thread(this::restore, "flink-table-restore");
        thread.setDaemon(true);
        thread.start();
    }

    /** Package-private so the test can run it synchronously. */
    void restore() {
        List<FlinkTableStore.StoredTable> stored = store.all();
        List<String> restored = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long startedAt = System.currentTimeMillis();

        for (FlinkTableStore.StoredTable table : stored) {
            try {
                QueryResult result = flinkSqlService.executeSql(new QueryRequest(
                    table.sql(), null, 1, PER_TABLE_TIMEOUT_MS, null));
                if (result.error() == null) {
                    restored.add(table.name());
                } else {
                    failed.add(table.name());
                    log.warn("Table '{}' could not be restored: {}", table.name(), result.error());
                }
            } catch (Exception e) {
                failed.add(table.name());
                log.warn("Table '{}' could not be restored: {}",
                    table.name(), SqlErrorClassifier.explain(e));
            }
        }

        long ms = System.currentTimeMillis() - startedAt;
        if (!restored.isEmpty()) {
            log.info("Restored {} table definition(s) written in the SQL editor in {} ms: {}",
                restored.size(), ms, String.join(", ", restored));
        }
        if (!failed.isEmpty()) {
            // WARN and named: a query on one of these will now either fail, or — worse and more
            // likely — auto-register a *generated* table under the same name and quietly answer
            // with it. That substitution is the whole reason this store exists, so the one boot
            // where it can still happen has to say which tables are affected. Each is removable
            // with DELETE /api/query/table/{name} once it is no longer wanted.
            log.warn("{} table definition(s) could not be restored and are still stored: {}. A "
                    + "query naming one of them may auto-register a generated table instead — "
                    + "re-run the CREATE TABLE, or drop it from the schema browser.",
                failed.size(), String.join(", ", failed));
        }
    }
}
