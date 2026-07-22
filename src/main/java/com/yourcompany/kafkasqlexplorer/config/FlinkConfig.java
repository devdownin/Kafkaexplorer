// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.config;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.springframework.context.annotation.Bean;

import java.time.ZoneId;

@org.springframework.context.annotation.Configuration
public class FlinkConfig {

    @Bean
    public TableEnvironment tableEnv() {
        // Flink 2.x removed the untyped Configuration setters (setString/…) and the
        // `table.exec.legacy-cast-behaviour` option (legacy casting no longer exists),
        // so only the typed NUM_TASK_SLOTS option is set here; the time zone is applied
        // through TableConfig after the environment is built.
        Configuration cfg = new Configuration();
        cfg.set(TaskManagerOptions.NUM_TASK_SLOTS, 8);

        EnvironmentSettings settings = EnvironmentSettings.newInstance()
            .inStreamingMode()
            .withConfiguration(cfg)
            .build();
        TableEnvironment tableEnv = TableEnvironment.create(settings);
        tableEnv.getConfig().setLocalTimeZone(ZoneId.of("UTC"));
        return tableEnv;
    }
}
