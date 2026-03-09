package com.yourcompany.kafkasqlexplorer.config;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class FlinkConfig {

    @Bean
    public StreamExecutionEnvironment streamEnv() {
        Configuration cfg = new Configuration();
        cfg.set(TaskManagerOptions.NUM_TASK_SLOTS, 4);
        cfg.set(RestOptions.BIND_PORT, "0");   // port UI Flink aleatoire
        return StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(cfg);
    }

    @Bean
    public StreamTableEnvironment tableEnv(StreamExecutionEnvironment env) {
        EnvironmentSettings settings = EnvironmentSettings
            .newInstance().inStreamingMode().build();
        return StreamTableEnvironment.create(env, settings);
    }
}
