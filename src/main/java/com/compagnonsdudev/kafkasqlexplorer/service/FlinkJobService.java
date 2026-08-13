// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class FlinkJobService {

    private final FlinkSqlService flinkSqlService;

    public FlinkJobService(FlinkSqlService flinkSqlService) {
        this.flinkSqlService = flinkSqlService;
    }

    public FlinkJobSummary submit(QueryRequest request) {
        return flinkSqlService.submitJob(request);
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
