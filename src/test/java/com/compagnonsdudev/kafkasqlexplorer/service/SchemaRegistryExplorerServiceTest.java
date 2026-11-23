// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaRegistryExplorerService.RegistryFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryExplorerServiceTest {
    private HttpServer server;

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    private SchemaRegistryExplorerService service() {
        KafkaConfig config = new KafkaConfig();
        config.setSchemaRegistryUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new SchemaRegistryExplorerService(config, new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }

    @Test
    void listsSortedSubjectsAndVersionsAndPreservesSchemaReferences() throws Exception {
        startServer();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            switch (path) {
                case "/subjects" -> reply(exchange, 200, "[\"orders-value\",\"customers-key\"]");
                case "/subjects/orders-value/versions" -> reply(exchange, 200, "[3,1,2]");
                case "/subjects/orders-value/versions/2" -> reply(exchange, 200,
                        "{\"subject\":\"orders-value\",\"version\":2,\"id\":91,\"schemaType\":\"PROTOBUF\","
                        + "\"schema\":\"message Order {}\",\"references\":[{\"name\":\"Base\",\"subject\":\"base-value\",\"version\":1}]}");
                default -> reply(exchange, 404, "{}");
            }
        });
        var service = service();
        var subjects = service.subjects();
        assertEquals(List.of("customers-key", "orders-value"), subjects.subjects());
        assertEquals(2, subjects.total());
        assertFalse(subjects.truncated());
        assertEquals(List.of(1, 2, 3), service.versions("orders-value"));
        var schema = service.version("orders-value", 2);
        assertEquals("PROTOBUF", schema.schemaType());
        assertEquals(91, schema.id());
        assertEquals("base-value", schema.references().getFirst().subject());
    }

    @Test
    void checksTwoStoredVersionsWithoutRegisteringAnything() throws Exception {
        startServer();
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            if (path.equals("/subjects/orders-value/versions/2")) {
                reply(exchange, 200,
                    "{\"subject\":\"orders-value\",\"version\":2,\"id\":92,"
                    + "\"schema\":\"{\\\"type\\\":\\\"string\\\"}\"}");
            } else if (path.equals("/compatibility/subjects/orders-value/versions/1")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("\"schemaType\":\"AVRO\""));
                assertTrue(body.contains("\"type\""));
                reply(exchange, 200, "{\"is_compatible\":false,\"messages\":[\"BREAKING_CHANGE\"]}");
            } else if (path.equals("/config/orders-value")) {
                reply(exchange, 200, "{\"compatibilityLevel\":\"BACKWARD\"}");
            } else {
                reply(exchange, 404, "{}");
            }
        });
        var result = service().compatibility("orders-value", 1, 2);
        assertFalse(result.compatible());
        assertEquals("BACKWARD", result.level());
        assertEquals(List.of("BREAKING_CHANGE"), result.messages());
        assertEquals(List.of(
            "GET /subjects/orders-value/versions/2",
            "POST /compatibility/subjects/orders-value/versions/1",
            "GET /config/orders-value"), requests);
    }

    @Test
    void reportsDisabledRegistryAndRejectsInvalidSubjectAndVersions() throws Exception {
        KafkaConfig disabled = new KafkaConfig();
        disabled.setSchemaRegistryUrl("");
        var service = new SchemaRegistryExplorerService(disabled, new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(RegistryFailure.class, service::subjects).status());

        startServer();
        var live = service();
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(RegistryFailure.class, () -> live.versions("  ")).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(RegistryFailure.class, () -> live.version("orders-value", 0)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(RegistryFailure.class, () -> live.compatibility("orders-value", 2, 2)).status());
    }
}
