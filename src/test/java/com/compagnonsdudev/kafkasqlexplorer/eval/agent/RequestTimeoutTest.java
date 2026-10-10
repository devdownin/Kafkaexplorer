// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A response that stops mid-way has to end the call, not the run.
 *
 * <p>The first full agent run lost 3 h 39 min to one such call: {@code HttpClient} has a connect
 * timeout and no response timeout, so a server that accepted the request and never answered blocked
 * the test until the job's own limit cancelled it — taking every report not yet printed with it.
 */
class RequestTimeoutTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopServer() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    /** A server that reads the request and then says nothing until the test ends. */
    private URI stalled() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    @DisplayName("an OpenAI-style model that never answers ends in a timeout")
    void openAiTimesOut() throws IOException {
        OpenAiToolCallingModel model = new OpenAiToolCallingModel(
                stalled(), "", "m", Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThatThrownBy(() -> model.respond("s", List.of(new AgentModel.Exchange.User("q")), List.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("an Anthropic-style model that never answers ends in a timeout")
    void anthropicTimesOut() throws IOException {
        AnthropicToolCallingModel model = new AnthropicToolCallingModel(
                stalled(), "k", "m", 100, Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThatThrownBy(() -> model.respond("s", List.of(new AgentModel.Exchange.User("q")), List.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("an MCP endpoint that never answers ends in a timeout")
    void mcpTimesOut() throws IOException {
        try (McpHttpClient client = new McpHttpClient(
                stalled(), Duration.ofSeconds(2), null, Duration.ofMillis(300))) {
            assertThatThrownBy(client::initialize)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(HttpTimeoutException.class);
        }
    }

    @Test
    @DisplayName("the hard cap returns what finished in time")
    void hardCapPassesAResultThrough() {
        assertThat(HardCap.within(Duration.ofSeconds(5), "quick", () -> "done")).isEqualTo("done");
    }

    @Test
    @DisplayName("the hard cap interrupts work that outlasts it, and says so")
    void hardCapInterrupts() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch ended = new CountDownLatch(1);

        assertThatThrownBy(() -> HardCap.within(Duration.ofMillis(200), "slow", () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                ended.countDown();
            }
            return "never";
        }))
                .isInstanceOf(HardCap.Exceeded.class)
                .hasMessageContaining("slow").hasMessageContaining("hard ceiling");
        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("an exception raised inside the cap reaches the caller as itself")
    void hardCapRethrows() {
        assertThatThrownBy(() -> HardCap.within(Duration.ofSeconds(5), "boom", () -> {
            throw new IllegalStateException("model said no");
        })).isInstanceOf(IllegalStateException.class).hasMessage("model said no");
    }
}
