// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read side of the Flink job registry: what the engine has been asked to run, and stopping one.
 *
 * <p>It carried a {@code submit(QueryRequest)} that posted an {@code INSERT INTO} as a continuous
 * job, for the SQL editor's Flink Job mode. That mode did not work and was removed, so submission
 * went with it — what feeds the registry now is a synchronous read, which registers its
 * {@code JobClient} for the length of its HTTP request so {@code POST /api/query/cancel/{queryId}}
 * can find it.
 */
@Service
public class FlinkJobService {

    private final FlinkSqlService flinkSqlService;

    public FlinkJobService(FlinkSqlService flinkSqlService) {
        this.flinkSqlService = flinkSqlService;
    }

    public List<FlinkJobSummary> listJobs() {
        return flinkSqlService.listRecentJobs();
    }

    public Optional<FlinkManagedJobDetails> getJob(String queryId) {
        return flinkSqlService.getJob(queryId);
    }

    public FlinkSqlService.CancelOutcome cancel(String queryId) {
        return flinkSqlService.cancelJob(queryId);
    }
}
