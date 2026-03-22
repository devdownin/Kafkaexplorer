package com.yourcompany.kafkasqlexplorer.config;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class FlinkConfig {

    @Bean
    public TableEnvironment tableEnv() {
        Configuration cfg = new Configuration();
        cfg.set(TaskManagerOptions.NUM_TASK_SLOTS, 8);
        cfg.setString("table.exec.legacy-cast-behaviour", "enabled");
        cfg.setString("table.local-time-zone", "UTC");

        EnvironmentSettings settings = EnvironmentSettings.newInstance()
            .inStreamingMode()
            .withConfiguration(cfg)
            .build();
        return TableEnvironment.create(settings);
    }
}
