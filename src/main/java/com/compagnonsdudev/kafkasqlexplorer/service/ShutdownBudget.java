// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One deadline shared by every executor pool shut down during bean destruction.
 *
 * <p>Six services own a pool and each used to await its own five seconds:
 * {@code FlinkSqlService}, {@code StreamFlowService}, {@code FlinkRuntimeCoordinator},
 * {@code AuditService} (two) and {@code KafkaLiveConsumer} (two). Bean destruction is
 * sequential, so those waits <em>added up</em> — up to ~35 s, on top of the graceful web
 * shutdown, and growing by five seconds every time a service gained a pool. Nobody had
 * bounded the total, and the container's grace period had to be stretched to cover a
 * number nobody had computed.
 *
 * <p>Here the first pool to be shut down starts the clock and all of them share what is
 * left of it. The common case is unaffected: with nothing in flight every pool terminates
 * at once and the budget is never touched. The case that matters — stopping the stack
 * during an audit — now costs {@link #TOTAL_MS} in total rather than per pool.
 *
 * <p>Each pool still gets {@link #MIN_SLICE_MS} even once the budget is spent, so the last
 * one destroyed is not systematically interrupted mid-work; the overshoot is bounded by
 * that floor times the number of pools.
 */
public final class ShutdownBudget {

    private static final Logger log = LoggerFactory.getLogger(ShutdownBudget.class);

    /** Total wall clock granted to bean destruction, across every pool. */
    static final long TOTAL_MS = 10_000;

    /** Floor granted to each pool once the shared budget is exhausted. */
    static final long MIN_SLICE_MS = 500;

    private static long deadline = -1;

    private ShutdownBudget() {
    }

    /**
     * Stops {@code executor} and waits for it within the remaining shared budget,
     * interrupting whatever is left when that runs out.
     */
    public static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(remainingMs(), TimeUnit.MILLISECONDS)) {
                log.debug("Shutdown budget spent, interrupting remaining tasks");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static synchronized long remainingMs() {
        if (deadline < 0) {
            deadline = System.currentTimeMillis() + TOTAL_MS;
        }
        return Math.max(MIN_SLICE_MS, deadline - System.currentTimeMillis());
    }

    /** The clock is process-wide; a test that drives it has to be able to rewind it. */
    static synchronized void reset() {
        deadline = -1;
    }

    /** Test seam: pins the shared deadline, so a test need not sleep the real budget. */
    static synchronized void deadlineIn(long millis) {
        deadline = System.currentTimeMillis() + millis;
    }
}
