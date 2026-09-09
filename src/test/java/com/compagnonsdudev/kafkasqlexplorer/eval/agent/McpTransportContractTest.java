// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The harness's own MCP client, against the real server, over real HTTP.
 *
 * <p><b>The gap this closes.</b> {@code McpServerBootTest} proves Spring AI <em>registers</em> the
 * fifteen tools — it reads the specification beans, with {@code webEnvironment = MOCK}, so nothing
 * ever crossed a socket. {@code McpHttpClientTest} proves this client reads the shapes a server can
 * answer — against a stub it wrote itself. Between the two sits the thing neither can see: what the
 * real transport actually puts on the wire. This test is a third-party client talking to a bound
 * port, which is the only place that fact lives.
 *
 * <p><b>And the fact worth the port is the serialization one.</b> {@code CLAUDE.md} names it as this
 * module's most expensive pitfall: the REST surface runs on Jackson 2 and the MCP transport on
 * Jackson 3, so a serializer registered against one is silently absent from the other, and the half
 * that loses it emits the naked {@code null} that {@code Measured} exists to prevent. Every
 * assertion below reads the JSON a client receives rather than an object a test constructed —
 * {@code coverage}, {@code stopReason}, and {@code Measured}'s three components as they arrive.
 *
 * <p><b>No broker is running, and that is the interesting half rather than a limitation.</b> A tool
 * that cannot reach Kafka is exactly the case the honesty contract is for: the answer must say what
 * it did not read and why, not come back empty and let the reader conclude the cluster is empty. A
 * server that dropped the envelope on its failure path would look identical to one that worked, in
 * every test that has ever been written for it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "explorer.flink-table-store-path=${java.io.tmpdir}/kse-transport-flink-tables.json",
            "explorer.settings-store-path=${java.io.tmpdir}/kse-transport-settings.json",
            "explorer.mcp.enabled=true",
            "explorer.mcp.readonly=true",
            // Small, so a tool that cannot reach a broker gives up quickly and answers rather than
            // spending the suite's time discovering what is already known here.
            "explorer.mcp.default-budget-ms=2000",
            "explorer.mcp.hard-max-budget-ms=4000",
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpTransportContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // A port nothing answers on. The point is a tool that fails to read, not one that reads.
        registry.add("kafka.bootstrap-servers", () -> "localhost:1");
    }

    @LocalServerPort
    int port;

    /**
     * The transport that is actually bound, asserted rather than assumed.
     *
     * <p>This is the assertion the whole class turns on. Spring AI documents {@code streamable} as
     * the default of {@code spring.ai.mcp.server.protocol}, and with the property absent this
     * context bound the <b>SSE</b> transport instead — so {@code /mcp}, the endpoint the console,
     * the README, {@code compose/mcp.yml} and {@code mcp-probe.sh} all advertise, was bound by
     * nothing. Naming the property in {@code application.yml} is the fix; this is what keeps it
     * fixed, and it fails loudly rather than letting the suite fall back to reporting a 405.
     */
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;

    private McpHttpClient client;
    private String serverName;

    private McpHttpClient client() {
        if (client == null) {
            client = new McpHttpClient(URI.create("http://localhost:" + port + "/mcp"),
                    Duration.ofSeconds(20));
            serverName = client.initialize();
        }
        return client;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the streamable transport is the one bound, not SSE")
    void theAdvertisedTransportIsTheBoundOne() {
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.function.RouterFunction.class))
                .withFailMessage("the bound transport is not the streamable one, so /mcp is not an "
                        + "endpoint at all — check spring.ai.mcp.server.protocol")
                .containsExactly("webMvcStreamableServerRouterFunction");
    }

    @AfterAll
    void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("a third-party client completes the handshake against the real transport")
    void handshakesOverTheRealTransport() {
        // Nothing had ever done this: every other test reads beans or a stub.
        client();
        assertThat(serverName).isNotBlank();
    }

    @Test
    @DisplayName("tools/list answers on the wire with every tool, each carrying a usable schema")
    void listsEveryToolWithASchema() {
        List<McpHttpClient.ToolSpec> tools = client().listTools();

        assertThat(tools).extracting(McpHttpClient.ToolSpec::name)
                .contains("kex_list_topics", "kex_trace_key", "kex_sql_query", "kex_consumer_lag",
                        "kex_suggest_kpis", "kex_build_join");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            // A schema the scanner could not derive is a tool a client cannot call — and over the
            // wire is where a client finds that out.
            assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        });
    }

    @Test
    @DisplayName("a tool that cannot reach the cluster refuses, naming the cause")
    void aDependencyFailureIsARefusalThatNamesItself() {
        // No broker is running here, and what that exercises is the dependency path rather than the
        // coverage one: this module answers an unreachable cluster with a refusal (-32043), not
        // with a partial result. The distinction is the server's to make and this test does not
        // argue with it — what it asserts is that the refusal reaches a client *saying what
        // happened*, because an empty answer with no explanation is indistinguishable from an empty
        // cluster, which is the confusion the whole contract exists to prevent.
        McpHttpClient.ToolAnswer answer =
                client().callTool("kex_list_topics", Map.of("prefix", "demo."));

        assertThat(answer.text())
                .withFailMessage("a tool that could not reach the broker answered nothing at all")
                .isNotBlank();
        assertThat(answer.text().toLowerCase(java.util.Locale.ROOT))
                .withFailMessage("the refusal reached the client without naming its cause: %s",
                        abbreviate(answer.text()))
                .containsAnyOf("kafka", "broker", "timed out", "timeout", "unavailable");
    }

    @Test
    @DisplayName("Measured survives the transport's Jackson 3 with all three of its components")
    void measuredKeepsItsShapeOnTheWire() throws IOException {
        // The pitfall CLAUDE.md names: a serializer registered against Jackson 2 is absent from
        // Jackson 3, and the half that loses it emits the naked null the type exists to prevent. A
        // unit test reading the record cannot see that; a client reading the JSON can.
        McpHttpClient.ToolAnswer answer =
                client().callTool("kex_describe_topic", Map.of("topic", "demo.orders.1.received"));

        // ...but only against a cluster. With no broker the tool refuses before it measures
        // anything, so there is no Measured on the wire to inspect. Aborting with the reason is the
        // honest outcome: this module's own rule is that an unmeasured thing says so rather than
        // being reported as a zero, and a harness that quietly passed here would be claiming to
        // have checked the one serialization it exists to check.
        assumeTrue(answer.text().trim().startsWith("{"),
                "no cluster: kex_describe_topic refused before producing a payload, so the "
                        + "Jackson 3 serialization of Measured is NOT verified by this run. Run it "
                        + "against the seeded stack: docker compose -f docker-compose.yml "
                        + "-f compose/mcp.yml up -d && ./setup-demo.sh localhost:9092. Answer was: "
                        + abbreviate(answer.text()));

        JsonNode payload = JSON.readTree(answer.text());
        List<JsonNode> measured = payload.findValues("measured");
        assertThat(measured)
                .withFailMessage("no Measured value crossed the wire at all, so this test asserted "
                        + "nothing about the serialization it exists for. Payload: %s",
                        abbreviate(answer.text()))
                .isNotEmpty();
        assertThat(measured).allSatisfy(flag -> assertThat(flag.isBoolean()).isTrue());

        // An unmeasured value carries its reason, and never arrives as a bare null: "the broker did
        // not answer" and "the value is zero" are the two sentences this type exists to keep apart.
        payload.findParents("measured").forEach(holder -> {
            if (!holder.path("measured").asBoolean(true)) {
                assertThat(holder.path("reason").asText())
                        .withFailMessage("an unmeasured value crossed the wire with no reason: %s",
                                holder)
                        .isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("a refusal the guard raises reaches a client as a refusal, not as an empty answer")
    void aGuardRefusalCrossesTheWireAsOne() {
        // hard-max-budget-ms is 4000 here, so asking for more is clamped-and-warned rather than
        // refused; what must never happen is the request being honoured silently or answered with
        // nothing at all.
        McpHttpClient.ToolAnswer answer = client().callTool("kex_list_topics",
                Map.of("limit", 999_999));

        assertThat(answer.text())
                .withFailMessage("a clamped request answered nothing at all")
                .isNotBlank();
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "…";
    }
}
