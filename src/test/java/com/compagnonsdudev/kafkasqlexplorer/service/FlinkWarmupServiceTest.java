// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The warmup is an optimisation, so what has to be pinned is that it cannot become a
 * liability: it must not run when switched off, must not need a table or a broker, and must
 * not let a failure escape into startup.
 */
class FlinkWarmupServiceTest {

    private final FlinkSqlService flinkSqlService = mock(FlinkSqlService.class);

    private FlinkWarmupService service(boolean enabled) {
        ExplorerConfig config = new ExplorerConfig();
        config.setFlinkWarmupEnabled(enabled);
        return new FlinkWarmupService(flinkSqlService, config);
    }

    @Test
    @DisplayName("disabled: the probe never runs, so nothing is spent on a deployment that opted out")
    void disabledRunsNothing() {
        service(false).onApplicationReady();
        verify(flinkSqlService, never()).executeSql(any());
    }

    @Test
    @DisplayName("the probe carries no table, so it needs neither a catalogue entry nor a broker")
    void probeIsTableLess() {
        when(flinkSqlService.executeSql(any())).thenReturn(new QueryResult(List.of("kse_warmup_probe"), List.of(), 5L, null));

        service(true).warmUp();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(flinkSqlService).executeSql(captor.capture());
        QueryRequest request = captor.getValue();
        assertThat(request.topic()).isNull();
        assertThat(request.sql()).doesNotContainIgnoringCase(" from ");
    }

    @Test
    @DisplayName("the probe reconciles the job store, so it cannot be counted as a running job")
    void probeReconcilesTheJobStore() {
        when(flinkSqlService.executeSql(any()))
                .thenReturn(new QueryResult(List.of("kse_warmup_probe"), List.of(), 5L, null));

        service(true).warmUp();

        // getActiveJobs() is the call that runs syncPersistedJobs(); getActiveJobsDetails(),
        // which POST /api/config counts for its 409 guard, does not. Without this the finished
        // probe would sit in the map on a fresh boot and refuse a legitimate cluster repoint.
        verify(flinkSqlService).getActiveJobs();
    }

    @Test
    @DisplayName("a probe that throws is swallowed — a warmup must never be able to fail startup")
    void failureIsSwallowed() {
        when(flinkSqlService.executeSql(any())).thenThrow(new IllegalStateException("planner down"));

        service(true).warmUp();  // must not propagate

        verify(flinkSqlService).executeSql(any());
    }

    @Test
    @DisplayName("a probe that reports an error is swallowed too, not just one that throws")
    void reportedErrorIsSwallowed() {
        when(flinkSqlService.executeSql(any())).thenReturn(new QueryResult(List.of(), List.of(), 5L, "no runtime"));

        service(true).warmUp();

        verify(flinkSqlService).executeSql(any());
    }
}
