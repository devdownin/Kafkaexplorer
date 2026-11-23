// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistryCompatibility;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistrySchemaReference;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistrySchemaVersion;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistrySubjects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only explorer for the configured Confluent Schema Registry.
 *
 * Calls use the server-side kafka.schema-registry-url, never a URL supplied by the browser.
 * Compatibility uses Confluent's non-mutating POST /compatibility endpoint, not registration.
 * The configured registry is optional; KafkaExplorer itself never waits for it to start.
 */
@Service
public class SchemaRegistryExplorerService {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SUBJECTS = 1000;
    private static final int MAX_SUBJECT_LENGTH = 1024;
    private final KafkaConfig config;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public SchemaRegistryExplorerService(KafkaConfig config) {
        this(config, new ObjectMapper(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    // Injectable client keeps connection and HTTP failure tests deterministic.
    SchemaRegistryExplorerService(KafkaConfig config, ObjectMapper mapper, HttpClient client) {
        this.config = config;
        this.mapper = mapper;
        this.client = client;
    }

    public RegistrySubjects subjects() {
        JsonNode result = call("GET", "/subjects", null);
        if (!result.isArray()) throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Unexpected subject list from Schema Registry.");
        List<String> names = new ArrayList<>();
        result.forEach(node -> { if (node.isTextual()) names.add(node.asText()); });
        names.sort(Comparator.naturalOrder());
        return new RegistrySubjects(names.stream().limit(MAX_SUBJECTS).toList(),
                names.size(), names.size() > MAX_SUBJECTS);
    }

    public List<Integer> versions(String subject) {
        JsonNode result = call("GET", "/subjects/" + segment(subject) + "/versions", null);
        if (!result.isArray()) throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Unexpected version list from Schema Registry.");
        List<Integer> versions = new ArrayList<>();
        result.forEach(node -> { if (node.canConvertToInt() && node.asInt() > 0) versions.add(node.asInt()); });
        return versions.stream().distinct().sorted().toList();
    }

    public RegistrySchemaVersion version(String subject, int version) {
        validVersion(version);
        JsonNode root = call("GET", "/subjects/" + segment(subject) + "/versions/" + version, null);
        if (!root.isObject() || !root.path("schema").isTextual()) {
            throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Schema Registry returned an invalid schema version.");
        }
        List<RegistrySchemaReference> references = new ArrayList<>();
        JsonNode refs = root.path("references");
        if (refs.isArray()) {
            refs.forEach(ref -> references.add(new RegistrySchemaReference(
                    ref.path("name").asText(""), ref.path("subject").asText(""),
                    ref.path("version").asInt())));
        }
        String type = root.path("schemaType").asText("AVRO");
        return new RegistrySchemaVersion(subject, root.path("version").asInt(version),
                root.path("id").asInt(), type, root.path("schema").asText(), references);
    }

    /**
     * Candidate is a schema that already exists under the chosen subject. The registry evaluates
     * it against the selected baseline using its currently effective compatibility setting.
     * Direction matters: candidate -> baseline is not necessarily interchangeable with reverse.
     */
    public RegistryCompatibility compatibility(String subject, int baselineVersion, int candidateVersion) {
        validVersion(baselineVersion);
        validVersion(candidateVersion);
        if (baselineVersion == candidateVersion) {
            throw new RegistryFailure(HttpStatus.BAD_REQUEST, "Choose two different versions to check compatibility.");
        }
        RegistrySchemaVersion candidate = version(subject, candidateVersion);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("schema", candidate.schema());
        payload.put("schemaType", candidate.schemaType());
        var refs = payload.putArray("references");
        for (RegistrySchemaReference reference : candidate.references()) {
            refs.addObject().put("name", reference.name()).put("subject", reference.subject())
                    .put("version", reference.version());
        }

        String basePath = "/subjects/" + segment(subject);
        JsonNode answer = call("POST", "/compatibility" + basePath
                + "/versions/" + baselineVersion + "?verbose=true", payload);
        if (!answer.path("is_compatible").isBoolean()) {
            throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Schema Registry returned an invalid compatibility result.");
        }
        List<String> messages = new ArrayList<>();
        if (answer.path("messages").isArray()) {
            answer.path("messages").forEach(message -> {
                if (message.isTextual() && messages.size() < 30) messages.add(message.asText());
            });
        }
        String level = "UNKNOWN";
        try {
            JsonNode policy = call("GET", "/config/" + segment(subject) + "?defaultToGlobal=true", null);
            level = policy.path("compatibilityLevel").asText("UNKNOWN");
        } catch (RegistryFailure failure) {
            // Some registries do not expose the effective policy. The check itself remains valid.
            if (failure.status() != HttpStatus.NOT_FOUND && failure.status() != HttpStatus.BAD_GATEWAY) throw failure;
        }
        return new RegistryCompatibility(answer.path("is_compatible").asBoolean(),
                baselineVersion, candidateVersion, level, messages);
    }

    private static String segment(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_SUBJECT_LENGTH) {
            throw new RegistryFailure(HttpStatus.BAD_REQUEST, "Select a valid schema subject.");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void validVersion(int version) {
        if (version <= 0) throw new RegistryFailure(HttpStatus.BAD_REQUEST, "Schema version must be positive.");
    }

    private JsonNode call(String method, String path, JsonNode body) {
        String configuredUrl = config.getSchemaRegistryUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new RegistryFailure(HttpStatus.SERVICE_UNAVAILABLE, "Schema Registry is not configured. Set KAFKA_SCHEMA_REGISTRY_URL.");
        }
        URI uri;
        try {
            URI base = URI.create(configuredUrl.strip().replaceAll("/+$", ""));
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getQuery() != null || base.getFragment() != null
                    || base.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid registry URL");
            }
            uri = URI.create(base.toASCIIString() + path);
        } catch (IllegalArgumentException e) {
            throw new RegistryFailure(HttpStatus.SERVICE_UNAVAILABLE, "Schema Registry URL is invalid.");
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/vnd.schemaregistry.v1+json");
            if ("POST".equals(method)) {
                request.header("Content-Type", "application/vnd.schemaregistry.v1+json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                request.GET();
            }
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                // Never parse or display arbitrary HTML / error payloads from an upstream proxy.
                if (response.statusCode() >= 400) {
                    HttpStatus status = response.statusCode() == 404 ? HttpStatus.NOT_FOUND
                            : response.statusCode() == 401 || response.statusCode() == 403
                                ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
                    throw new RegistryFailure(status, response.statusCode() == 404
                            ? "Schema subject or version was not found."
                            : "Schema Registry request failed (HTTP " + response.statusCode() + ").");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Unexpected Schema Registry response.");
                }
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new RegistryFailure(HttpStatus.BAD_GATEWAY, "Schema Registry response exceeds the 2 MB safety limit.");
                }
                return mapper.readTree(bytes);
            }
        } catch (RegistryFailure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryFailure(HttpStatus.SERVICE_UNAVAILABLE, "Schema Registry request was interrupted.");
        } catch (IOException | IllegalArgumentException e) {
            throw new RegistryFailure(HttpStatus.SERVICE_UNAVAILABLE, "Schema Registry is unreachable or returned invalid data.");
        }
    }

    public static final class RegistryFailure extends RuntimeException {
        private final HttpStatus status;

        public RegistryFailure(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() { return status; }
    }
}
