// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * What the state restores that run while the context is still building are allowed to spend, and
 * the one verdict they share about the broker.
 *
 * <p>Two beans read a Kafka topic during their {@code @PostConstruct}: {@link MetricService}
 * restores the metric configurations, {@link FieldMappingStore} the Process Mining field mappings
 * an operator validated by hand. Both are right to be synchronous — a page that answers "no
 * metrics" while a restore is still in flight gives the same answer as a cluster that has none,
 * which is the confusion this codebase removes everywhere else.
 *
 * <p>What they were not right about is the price of failure. Each opened its own consumer under
 * the Kafka client's 5 s default API timeout, so an unreachable broker was discovered twice, in
 * series, over two clients. Measured with nothing listening on the bootstrap address: <b>10.1 s of
 * a 14.5 s boot</b>, for two reads that returned nothing and, both logging at DEBUG, said nothing
 * either.
 *
 * <p>This class fixes the second half of that. The first restore to give up records the reason
 * here, and the next one reads the verdict instead of re-paying for it — the same "take the
 * measurement once and share it" rule the audit applies to its consumer-group snapshot. The
 * verdict is deliberately <em>only</em> about a broker that did not answer within the budget: a
 * restore that failed for any other reason (a corrupt record, a deserialisation problem) reports
 * its own failure and leaves the next one to try, because nothing about it says the cluster is
 * unreachable.
 *
 * <p>It lives for the boot and no longer. Nothing clears it, because nothing needs to: both
 * restores run within a second of each other during context startup, and every read after that
 * goes through {@link KafkaAdminService}, which probes the broker itself.
 */
@Component
public class StartupRestore {

    private static final Logger log = LoggerFactory.getLogger(StartupRestore.class);

    /**
     * Set by the first restore whose broker did not answer, and read by the next one. An
     * {@code AtomicReference} rather than a plain volatile field so the first reason wins: the
     * second restore's identical timeout must not overwrite the account of what actually happened
     * first.
     */
    private final AtomicReference<String> brokerUnreachable = new AtomicReference<>();

    private final long discoveryTimeoutMs;

    public StartupRestore(ExplorerConfig explorerConfig) {
        this.discoveryTimeoutMs = Math.max(500, explorerConfig.getStartupRestoreTimeoutMs());
    }

    /**
     * How long a restore may wait for the broker's first answer, as an API timeout for its
     * consumer. Floored at 500 ms: a budget below that is not a stricter policy, it is a restore
     * that can only fail, and losing an operator's hand-corrected field mappings to a mistyped
     * property is worse than a slow boot.
     */
    public long discoveryTimeoutMs() {
        return discoveryTimeoutMs;
    }

    /** The request timeout that goes with {@link #discoveryTimeoutMs()}, kept just below it. */
    public long requestTimeoutMs() {
        return Math.max(400, discoveryTimeoutMs - 500);
    }

    /**
     * The reason an earlier restore in this boot gave up on the broker, or {@code null} when none
     * has.
     */
    public String brokerUnreachableReason() {
        return brokerUnreachable.get();
    }

    /**
     * Records that the broker did not answer a restore within its budget.
     *
     * @param what   the restore that gave up, named for the log
     * @param reason the flattened failure, from {@link SqlErrorClassifier#explain}
     */
    public void brokerDidNotAnswer(String what, String reason) {
        if (brokerUnreachable.compareAndSet(null, reason)) {
            log.warn("The broker did not answer within {} ms while restoring {}. State kept on the "
                    + "cluster is not available to this process, and the restores that follow are "
                    + "skipped rather than waiting for the same answer: {}",
                discoveryTimeoutMs, what, reason);
        }
    }
}
