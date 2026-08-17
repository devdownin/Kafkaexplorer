// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Runs one throw-away query after startup so a user's first query does not pay for the
 * planner waking up.
 *
 * <p>The cost is real and was measured rather than assumed, against the embedded runtime on
 * in-memory views: the first SELECT of a process takes ~5.5 s where a warm one takes ~1.2 s,
 * and the ~4 s difference is one-off — Calcite class loading, Janino codegen of the metadata
 * handlers, the first job graph. What an operator sees without this is a Run button spinning
 * for five seconds once per deployment, on the gesture that forms their first impression.
 *
 * <p><b>Why a table-less {@code SELECT}, and not an EXPLAIN.</b> Both were measured. An
 * EXPLAIN warms the parser and gets the first query to ~3.0 s — only half the gain, because
 * the cost is in code generation and the job lifecycle rather than in parsing. A full query
 * gets it to ~1.6 s, i.e. essentially warm. It carries no table on purpose: nothing to
 * register, nothing to drop afterwards, no dependency on a reachable broker at boot, and no
 * chance of leaving a probe view in the catalogue where the schema browser would show it to
 * the user. (Flink also distinguishes {@code DROP VIEW} from {@code DROP TEMPORARY VIEW},
 * so a view-based probe leaks unless that subtlety is respected — one more reason not to
 * create one.)
 *
 * <p><b>Why after {@code ApplicationReadyEvent}, on a daemon thread.</b> Boot time is
 * deliberately short here — the app waits for the broker only, not for the demo seeder — and a
 * warmup that delayed readiness would trade a defect for a worse one. Running it in the
 * background means the UI serves immediately and the probe finishes while the operator is
 * still finding their way to the editor.
 *
 * <p>One honest cost: a SELECT takes the runtime's shared read lock, so a user query that
 * needs a table *registration* (an exclusive mutation) in the first few seconds can queue
 * behind the probe. That wait is bounded by the coordinator's own budget, and the query it
 * delays is precisely the one that would otherwise have paid the 4 s itself — so the worst
 * case is roughly a wash and the common case is much better. Everything is best-effort: a
 * failed probe is logged and forgotten, never allowed to affect startup.
 */
@Service
public class FlinkWarmupService {

    private static final Logger log = LoggerFactory.getLogger(FlinkWarmupService.class);

    /** No table, no catalogue entry, no broker. See the class comment on why not an EXPLAIN. */
    private static final String WARMUP_SQL = "SELECT 1 AS kse_warmup_probe";

    private final FlinkSqlService flinkSqlService;
    private final ExplorerConfig config;

    public FlinkWarmupService(FlinkSqlService flinkSqlService, ExplorerConfig config) {
        this.flinkSqlService = flinkSqlService;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!config.isFlinkWarmupEnabled()) {
            log.debug("Flink warmup disabled (explorer.flink-warmup-enabled=false); "
                    + "the first query will pay for the planner starting up");
            return;
        }
        Thread thread = new Thread(this::warmUp, "flink-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Package-private so the test can run it synchronously. */
    void warmUp() {
        long startedAt = System.nanoTime();
        try {
            QueryResult result = flinkSqlService.executeSql(
                    QueryRequest.sql(WARMUP_SQL, 1, null, null));
            long ms = (System.nanoTime() - startedAt) / 1_000_000;
            if (result != null && result.error() != null) {
                // Not a failure worth alarming about: the probe is an optimisation, and the
                // reason is logged so an operator debugging a slow first query can see it.
                log.info("Flink warmup query did not complete ({} ms): {}", ms, result.error());
            } else {
                log.info("Flink planner warmed up in {} ms; the first user query no longer "
                        + "pays for it", ms);
            }
            reconcileJobStore();
        } catch (Exception e) {
            log.info("Flink warmup query failed after {} ms, continuing without it: {}",
                    (System.nanoTime() - startedAt) / 1_000_000, e.toString());
        }
    }

    /**
     * Clears the probe out of the managed-job map, which it would otherwise sit in until
     * something else happened to reconcile it.
     *
     * <p>This is not tidiness. A synchronous SELECT that gets a JobClient is recorded in
     * {@code activeJobs}, and entries are only dropped inside {@code syncPersistedJobs()} —
     * which {@code getActiveJobs()} calls but {@code getActiveJobsDetails()} does not. That
     * second one is what {@code POST /api/config} counts to decide whether to refuse a cluster
     * repoint with 409, and what the lineage graph reads. For a user's query the asymmetry is
     * invisible: they are using the UI, whose dashboard poll calls {@code getActiveJobs()}
     * every 30 s and reconciles it. A probe the *application* runs by itself has no such
     * companion — on a fresh boot with no browser open, nothing would clear it, and an operator
     * repointing the cluster over the API would be refused in the name of a finished query they
     * never ran. One call to {@code getActiveJobs()} reconciles it through the existing path.
     */
    private void reconcileJobStore() {
        try {
            flinkSqlService.getActiveJobs();
        } catch (Exception e) {
            log.debug("Could not reconcile the job store after the warmup probe: {}", e.toString());
        }
    }
}
