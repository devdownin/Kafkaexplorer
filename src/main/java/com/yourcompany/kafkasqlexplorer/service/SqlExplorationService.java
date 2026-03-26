// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import org.springframework.stereotype.Service;

@Service
public class SqlExplorationService {

    private final FlinkSqlService flinkSqlService;

    public SqlExplorationService(FlinkSqlService flinkSqlService) {
        this.flinkSqlService = flinkSqlService;
    }

    public QueryResult runSync(QueryRequest request) {
        return flinkSqlService.executeSync(request);
    }
}
