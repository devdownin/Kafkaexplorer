// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

@org.springframework.context.annotation.Configuration
public class FlinkConfig {

    private static final Logger log = LoggerFactory.getLogger(FlinkConfig.class);

    @Bean
    public TableEnvironment tableEnv() {
        // Flink 2.x removed the untyped Configuration setters (setString/…) and the
        // `table.exec.legacy-cast-behaviour` option (legacy casting no longer exists),
        // so only the typed NUM_TASK_SLOTS option is set here; the time zone is applied
        // through TableConfig after the environment is built.
        Configuration cfg = new Configuration();
        cfg.set(TaskManagerOptions.NUM_TASK_SLOTS, 8);
        applyJobClasspath(cfg);

        EnvironmentSettings settings = EnvironmentSettings.newInstance()
            .inStreamingMode()
            .withConfiguration(cfg)
            .build();
        TableEnvironment tableEnv = TableEnvironment.create(settings);
        tableEnv.getConfig().setLocalTimeZone(ZoneId.of("UTC"));
        return tableEnv;
    }

    /**
     * Tells the embedded MiniCluster which classpath its jobs run on, when this application is
     * not loaded by the system class loader.
     *
     * <p><b>The defect this exists for.</b> A job submitted here carries no user jars and, left
     * alone, no classpath either — and Flink reads that pair as "this job needs nothing of its
     * own", so {@code BlobLibraryCacheManager} hands the JobManager
     * {@code ClassLoader.getSystemClassLoader()} to deserialize the job graph with. That is a
     * sound default for a real cluster, where Flink itself sits on the system classpath. It is
     * wrong for every packaged form of this application: inside a Spring Boot jar — run as
     * {@code java -jar}, or as the extracted layers behind {@code JarLauncher}, which is what
     * both runtime images do — the dependencies live in {@code BOOT-INF/lib} and are reachable
     * only through Boot's own loader. The system loader sees the launcher and nothing else, so
     * deserializing the very first operator threw
     * {@code ClassNotFoundException: org.apache.flink.table.runtime.operators.CodeGenOperatorFactory}
     * and the job never started. What reached the log was
     * {@code FlinkException: Failed to execute job '…'}, once per SELECT, stack trace included.
     *
     * <p>Nothing about it was visible as what it is. Every SELECT was classified as an engine
     * failure and fell back to the direct Kafka reader, so results kept arriving — from a reader
     * that regex-matches a table name out of {@code FROM} and supports neither JOIN nor
     * subqueries — and after three of them the circuit breaker disabled the planner for the rest
     * of the process. {@code CREATE TABLE} and {@code EXPLAIN}, which have no fallback, simply
     * failed. The whole Flink half of this application was dead in the shipped artefact while
     * the test suite, which runs on a plain classpath, stayed green.
     *
     * <p><b>What this does.</b> Naming a non-empty classpath is what makes Flink build a real
     * user-code class loader instead of taking that shortcut, and the parent it gives it is the
     * loader that loaded Flink — i.e. ours, the one that has everything. So the fix is to state
     * the truth: these URLs are the classpath this job runs on. It is paired with
     * <b>parent-first</b> resolution, which is not decoration — Flink's default is child-first,
     * and a child-first loader over our own URLs would load a second copy of every class outside
     * the always-parent-first patterns (our UDFs among them) and hand back
     * {@code ClassCastException} between two versions of the same type. Parent-first keeps one
     * loader in charge and leaves the URL list doing the one job it is here for.
     *
     * <p>On a plain classpath — {@code mvn spring-boot:run}, the tests, an exploded run — the
     * application loader <i>is</i> the system loader, so there is nothing to correct and this
     * writes nothing: the default behaviour is already right and is left exactly as it was.
     */
    private static void applyJobClasspath(Configuration cfg) {
        ClassLoader appLoader = FlinkConfig.class.getClassLoader();
        if (appLoader == null || appLoader == ClassLoader.getSystemClassLoader()) {
            return;
        }
        if (!(appLoader instanceof URLClassLoader urlLoader)) {
            // Nothing to name, and guessing would be worse than the honest warning: without a
            // classpath Flink takes the system loader and every Flink-engine query fails to
            // submit, so an operator reading "Failed to execute job" needs this line to exist.
            log.warn("This application is loaded by {} rather than the system class loader, and "
                    + "that loader does not expose its URLs. Flink jobs will be deserialized with "
                    + "the system class loader, which cannot see the dependencies — SELECT through "
                    + "the Flink planner will fail to submit. Run the application from a real "
                    + "classpath instead.", appLoader.getClass().getName());
            return;
        }
        List<String> urls = Arrays.stream(urlLoader.getURLs()).map(URL::toString).toList();
        if (urls.isEmpty()) {
            return;
        }
        cfg.set(PipelineOptions.CLASSPATHS, urls);
        cfg.set(CoreOptions.CLASSLOADER_RESOLVE_ORDER, "parent-first");
        log.debug("Flink jobs will run on this application's own classpath ({} entries, parent-first); "
                + "the loader is {}, not the system one", urls.size(), appLoader.getClass().getName());
    }
}
