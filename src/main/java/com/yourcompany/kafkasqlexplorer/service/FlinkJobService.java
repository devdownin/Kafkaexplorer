// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.yourcompany.kafkasqlexplorer.domain.FlinkJobSummary;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
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

    public void cancel(String queryId) {
        flinkSqlService.cancelJob(queryId);
    }
}
