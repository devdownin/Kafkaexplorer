package com.yourcompany.kafkasqlexplorer.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "kafka")
public class KafkaConfig {
    private String bootstrapServers = "localhost:9092";
    private String mode = "PLAIN"; // PLAIN, SSL, CONFLUENT_CLOUD
    private Map<String, String> clusters = new HashMap<>();

    // SSL properties
    private String truststorePath;
    private String truststorePassword;
    private String keystorePath;
    private String keystorePassword;
    private String keyPassword;

    // Confluent Cloud properties
    private String confluentKey;
    private String confluentSecret;

    public Map<String, String> getKafkaProperties() {
        Map<String, String> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

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

    public Map<String, String> getClusters() {
        return clusters;
    }

    public void setClusters(Map<String, String> clusters) {
        this.clusters = clusters;
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

    public String getConfluentSecret() {
        return confluentSecret;
    }

    public void setConfluentSecret(String confluentSecret) {
        this.confluentSecret = confluentSecret;
    }
}
