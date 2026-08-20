// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * States, once, what this process is actually pointed at and whether the cluster answered.
 *
 * <p>It exists because of a count. A boot with nothing listening on the bootstrap address was
 * measured at <b>103 775 log lines</b>, of which the application's own package contributed
 * <b>six</b> — and not one of the six said which address had not answered. Everything an operator
 * needed was either absent (both state restores reported their failure at DEBUG) or buried under
 * ~102 000 {@code Rebootstrapping with Cluster(…)} lines from the Kafka admin client. The log said
 * a great deal and explained nothing.
 *
 * <p>What is printed here is deliberately only what has been <em>checked</em>: the address comes
 * from the running {@link KafkaConfig}, so it follows a runtime repoint, and the verdict beside it
 * is a real probe rather than a configuration value read back to itself — the same rule the header's
 * connection pill was rewritten under, after it spent releases asserting a Kafka version nothing had
 * ever asked the broker for. When the probe fails, the reason is on the line: a broker that is down,
 * a bootstrap address pointing nowhere and an authorisation failure are three different problems
 * with three different fixes, and "Disconnected" distinguishes none of them.
 *
 * <p>On a daemon thread after {@code ApplicationReadyEvent}, for the reason {@code FlinkWarmupService}
 * gives: the probe is bounded but it is still a network call, and nothing whose only product is a
 * log line may sit in front of readiness.
 */
@Component
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final KafkaConfig kafkaConfig;
    private final ClaudeConfig claudeConfig;
    private final KafkaAdminService kafkaAdminService;

    public StartupSummary(KafkaConfig kafkaConfig, ClaudeConfig claudeConfig,
                          KafkaAdminService kafkaAdminService) {
        this.kafkaConfig = kafkaConfig;
        this.claudeConfig = claudeConfig;
        this.kafkaAdminService = kafkaAdminService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread thread = new Thread(this::report, "startup-summary");
        thread.setDaemon(true);
        thread.start();
    }

    /** Package-private so the test can run it synchronously. */
    void report() {
        try {
            KafkaAdminService.PingResult ping = kafkaAdminService.pingDetail();
            if (ping.reachable()) {
                log.info("Kafka {} ({}) answered.", kafkaConfig.getBootstrapServers(), kafkaConfig.getMode());
            } else {
                // WARN, not ERROR: the UI serves, and the Settings page can repoint the application
                // at another cluster — which is exactly what an operator needs to be able to do at
                // this moment. It is a degraded start, not a failed one.
                log.warn("Kafka {} ({}) did not answer: {}. The UI is serving and the cluster can be "
                        + "repointed from Settings; queries and audits will fail until it does.",
                    kafkaConfig.getBootstrapServers(), kafkaConfig.getMode(), ping.error());
            }
            log.info("LLM provider {} ({}) at {}{}.",
                claudeConfig.getProviderLabel(), claudeConfig.getModel(),
                claudeConfig.getResolvedBaseUrl(),
                claudeConfig.isLocalDeployment() ? " — a loopback address, so payloads stay on this host" : "");
        } catch (Exception e) {
            // A summary that cannot be produced must not be able to affect anything: it is one log
            // line, and the application has already started.
            log.debug("Startup summary could not be produced: {}", SqlErrorClassifier.explain(e));
        }
    }
}
