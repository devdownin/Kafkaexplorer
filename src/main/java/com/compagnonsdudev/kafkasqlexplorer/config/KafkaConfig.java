// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "kafka")
public class KafkaConfig {
    private String bootstrapServers = "localhost:9092";
    private String schemaRegistryUrl = "http://localhost:8081";
    private String mode = "PLAIN"; // PLAIN, SSL, CONFLUENT_CLOUD

    /**
     * Rebalance protocol for consumers that use group management (subscribe), i.e. the
     * live Process Mining consumer. "classic" (default) works against any broker;
     * "consumer" opts into the KIP-848 incremental protocol — near-instant rebalances,
     * but requires Kafka 4.x brokers (or 3.x with the new group coordinator enabled).
     */
    private String consumerGroupProtocol = "classic";

    public String getSchemaRegistryUrl() {
        return schemaRegistryUrl;
    }

    public void setSchemaRegistryUrl(String schemaRegistryUrl) {
        this.schemaRegistryUrl = schemaRegistryUrl;
    }
    // SSL properties
    private String truststorePath;
    private String truststorePassword;
    private String keystorePath;
    private String keystorePassword;
    private String keyPassword;

    // Confluent Cloud properties
    private String confluentKey;
    private String confluentSecret;

    /** The three values {@code kafka.mode} accepts, in the spelling the message quotes back. */
    public static final List<String> MODES = List.of("PLAIN", "SSL", "CONFLUENT_CLOUD");

    /**
     * Refuses to start on a connection setting that cannot mean what it says.
     *
     * <p>{@link #getKafkaProperties()} builds the client properties with an if/else on the mode and
     * <b>no final else</b>: an unrecognised value therefore fell through to the PLAIN branch, so
     * {@code mode: SASL_SSL} — a plausible thing to write, since that is the spelling Kafka's own
     * {@code security.protocol} uses — connected in <em>plaintext</em>, silently, in the one
     * setting whose whole purpose is to say how the connection is protected. The same branch built
     * {@code username="null" password="null"} into the JAAS string when Confluent Cloud
     * credentials were absent, which surfaces much later as an authentication failure naming
     * nothing.
     *
     * <p>Fail fast, and name the property. A downgrade that nobody asked for is not a degraded
     * mode this application is entitled to choose on an operator's behalf; the fix is one word in
     * one file, and the message says which word and which file.
     */
    @PostConstruct
    void validateAtStartup() {
        List<String> problems = validationProblems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "Kafka connection settings are not usable:\n  - " + String.join("\n  - ", problems));
        }
    }

    /** The problems with the settings currently held, empty when there are none. */
    public List<String> validationProblems() {
        return problemsWith(bootstrapServers, mode, confluentKey, confluentSecret,
            truststorePath, keystorePath);
    }

    /**
     * The same rule, applied to values that have not been stored yet.
     *
     * <p>{@code POST /api/config} repoints the cluster at runtime through the very same
     * {@code getKafkaProperties()}, so it is the second entry point into this defect: it used to
     * accept any string as a mode and answer 200. Validating the requested values before they are
     * written is what keeps one rule from having two behaviours.
     */
    public static List<String> problemsWith(String bootstrapServers, String mode,
                                            String confluentKey, String confluentSecret,
                                            String truststorePath, String keystorePath) {
        List<String> problems = new ArrayList<>();
        if (isBlank(bootstrapServers)) {
            problems.add("kafka.bootstrap-servers is empty — there is no address to connect to.");
        }
        String normalized = mode == null ? "" : mode.trim();
        boolean known = MODES.stream().anyMatch(m -> m.equalsIgnoreCase(normalized));
        if (!known) {
            problems.add("kafka.mode is '" + mode + "', which is not one of " + String.join(", ", MODES)
                + ". An unrecognised mode used to fall through to PLAIN, i.e. connect in plaintext.");
        } else if ("CONFLUENT_CLOUD".equalsIgnoreCase(normalized)) {
            if (isBlank(confluentKey)) {
                problems.add("kafka.mode is CONFLUENT_CLOUD but kafka.confluent-key is empty — the "
                    + "SASL configuration would carry the literal text 'null' as the username.");
            }
            if (isBlank(confluentSecret)) {
                problems.add("kafka.mode is CONFLUENT_CLOUD but kafka.confluent-secret is empty — the "
                    + "SASL configuration would carry the literal text 'null' as the password.");
            }
        }
        // Checked whatever the mode: a path that points nowhere is a mistake in every one of them,
        // and reading it at boot beats a Kafka client error on whichever page happens to be first.
        missingFile("kafka.truststore-path", truststorePath, problems);
        missingFile("kafka.keystore-path", keystorePath, problems);
        return problems;
    }

    private static void missingFile(String property, String path, List<String> problems) {
        if (!isBlank(path) && !Files.isReadable(Path.of(path))) {
            problems.add(property + " is '" + path + "', which is not a readable file.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public Map<String, String> getKafkaProperties() {
        Map<String, String> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Trimmed, because the validation above trims before deciding the mode is known: a value
        // the operator was told is valid must not take the plaintext branch here over a space.
        String mode = this.mode == null ? "" : this.mode.trim();
        if ("SSL".equalsIgnoreCase(mode)) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
            if (truststorePath != null) props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststorePath);
            if (truststorePassword != null) props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword);
            if (keystorePath != null) props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keystorePath);
            if (keystorePassword != null) props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, keystorePassword);
            if (keyPassword != null) props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, keyPassword);
        } else if ("CONFLUENT_CLOUD".equalsIgnoreCase(mode)) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
            String jaasConfig = String.format(
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                confluentKey, confluentSecret
            );
            props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
        }
        return props;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTruststorePath() {
        return truststorePath;
    }

    public void setTruststorePath(String truststorePath) {
        this.truststorePath = truststorePath;
    }

    public String getTruststorePassword() {
        return truststorePassword;
    }

    public void setTruststorePassword(String truststorePassword) {
        this.truststorePassword = truststorePassword;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getConfluentKey() {
        return confluentKey;
    }

    public void setConfluentKey(String confluentKey) {
        this.confluentKey = confluentKey;
    }

    public String getConsumerGroupProtocol() {
        return consumerGroupProtocol;
    }

    public void setConsumerGroupProtocol(String consumerGroupProtocol) {
        this.consumerGroupProtocol = consumerGroupProtocol;
    }

    public String getConfluentSecret() {
        return confluentSecret;
    }

    public void setConfluentSecret(String confluentSecret) {
        this.confluentSecret = confluentSecret;
    }
}
