// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The one place every MCP tool call passes through: it names the caller, turns a refusal into the
 * JSON-RPC code the agent can read, enforces the output ceiling, and records what happened.
 *
 * <p>It exists because three things the module already claimed were, without it, not true. The
 * recorder was written, tested and wired to metrics that no code path incremented — so a serving
 * deployment reported {@code explorer_mcp_calls_total} at zero, which reads as "no calls", not as
 * "nothing counts". {@code hard-max-output-bytes} was published in the catalogue as a ceiling a
 * caller cannot argue out of, and nothing measured a byte. And {@code McpToolException} carried a
 * KIP-1318 code that nothing read: {@code ToolGuard} refused correctly and the SDK then flattened
 * the refusal into a generic tool error, so the whole point of adopting those codes — that an agent
 * trained on that surface can tell "out of scope" from "the broker is down" — did not survive the
 * trip. Three claims, one missing layer.
 *
 * <p><b>Wrapping the specification rather than the tool method.</b> Spring AI builds a
 * {@link SyncToolSpecification} per {@code @McpTool} method, each a record of the tool's schema and
 * a call handler. Wrapping the handler is the only vantage point that sees all four things at once:
 * the exchange (who is calling), the request (what they asked, by tool name), the result, and the
 * exception. An AOP aspect around the methods would see the first none of and could not shape the
 * error response; a check inside each tool would have to be written once per tool and would be
 * forgotten by the tool added next.
 *
 * <p><b>An oversized payload is refused, not cut.</b> Truncating a serialised result yields
 * malformed JSON at best and a plausible shorter answer at worst, and the second is the failure
 * this module exists to prevent — a model cannot tell a cut list from a complete one. The reply
 * instead says the tool, the size, the ceiling and what to narrow. The caller loses a round trip
 * and keeps the truth.
 */
public class McpToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(McpToolInterceptor.class);

    /** What an unauthenticated local caller is called. stdio has no identity to read. */
    static final String LOCAL_IDENTITY = "local (stdio)";

    private final McpProperties properties;
    private final ToolGuard guard;
    private final DlpScrubber dlp;
    private final McpCallRecorder recorder;

    /**
     * Its own mapper, deliberately: this one only measures and reports, so it must not inherit
     * whatever the application's shared mapper has been configured to do to a payload.
     */
    private final ObjectMapper sizer = new ObjectMapper();

    public McpToolInterceptor(McpProperties properties, ToolGuard guard, DlpScrubber dlp,
                              McpCallRecorder recorder) {
        this.properties = properties;
        this.guard = guard;
        this.dlp = dlp;
        this.recorder = recorder;
    }

    /** The same specification, with its call handler wrapped. */
    public SyncToolSpecification wrap(SyncToolSpecification specification) {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate =
                specification.callHandler();
        return new SyncToolSpecification(specification.tool(),
                (exchange, request) -> intercept(delegate, exchange, request));
    }

    private CallToolResult intercept(
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> delegate,
            McpSyncServerExchange exchange, CallToolRequest request) {

        String correlationId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();

        String tool = request.name();
        String identity = identityOf(exchange);
        String clientInfo = clientInfoOf(exchange);
        Map<String, Object> redactedParams = dlp.scrubParams(request.arguments());

        try {
            // Quarantine is checked here rather than in each tool: it is about the caller, and the
            // caller is only visible at this layer. A tool cannot see who invoked it.
            guard.checkNotQuarantined(identity);

            CallToolResult result = delegate.apply(exchange, request);
            Measured<Long> outputBytes = sizeOf(result);

            if (outputBytes.measured() && outputBytes.value() > properties.getHardMaxOutputBytes()) {
                CallToolResult refusal = tooLarge(tool, outputBytes.value());
                record(correlationId, startedAt, startedNanos, tool, identity, clientInfo,
                        redactedParams, McpCallRecord.Outcome.OK, null, null,
                        result.structuredContent(), true, sizeOf(refusal));
                return refusal;
            }

            record(correlationId, startedAt, startedNanos, tool, identity, clientInfo,
                    redactedParams, McpCallRecord.Outcome.OK, null, null,
                    result.structuredContent(),
                    McpCallContext.truncated(result.structuredContent()), outputBytes);
            return result;

        } catch (McpToolException e) {
            // Recorded exactly like a success — a control that blocks silently is a control nobody
            // ever tunes — but returned through whichever of MCP's two error channels the code
            // belongs to. See McpErrorCode.Level: a refusal the model must not argue with is a
            // JSON-RPC error, a failure it is meant to read and correct is tool output.
            if (e.errorCode().reportedToTheModel()) {
                CallToolResult failure = executionFailure(e);
                record(correlationId, startedAt, startedNanos, tool, identity, clientInfo,
                        redactedParams, outcomeOf(e), e.jsonRpcCode(), e.guard(), null, false,
                        sizeOf(failure));
                return failure;
            }
            record(correlationId, startedAt, startedNanos, tool, identity, clientInfo,
                    redactedParams, outcomeOf(e), e.jsonRpcCode(), e.guard(), null, false,
                    Measured.of(0L));
            throw asMcpError(e);

        } catch (RuntimeException e) {
            record(correlationId, startedAt, startedNanos, tool, identity, clientInfo,
                    redactedParams, McpCallRecord.Outcome.ERROR, null, null, null, false,
                    Measured.of(0L));
            log.warn("MCP tool {} failed for correlationId={}", tool, correlationId, e);
            throw e;
        }
    }

    /**
     * A dependency that did not answer is an {@code ERROR}, everything else a {@code DENIED}.
     *
     * <p>The console groups by this, and filing a broker outage under "denied" would send an
     * operator to loosen a guard that was never involved.
     */
    private static McpCallRecord.Outcome outcomeOf(McpToolException e) {
        return e.errorCode() == McpErrorCode.DEPENDENCY_UNAVAILABLE
                ? McpCallRecord.Outcome.ERROR
                : McpCallRecord.Outcome.DENIED;
    }

    /**
     * A failure the model is expected to read and act on.
     *
     * <p>The code travels in the text rather than being dropped: it is what tells a model that the
     * statement was rejected before anything ran, as against a cluster that was briefly away. The
     * message is the planner's own — its line and column are the whole reason this path exists
     * instead of a hard error the client may never show.
     */
    private static CallToolResult executionFailure(McpToolException e) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent("[%d %s] %s".formatted(
                        e.jsonRpcCode(), e.errorCode().name(), e.getMessage()))
                .build();
    }

    /**
     * Turns our refusal into the SDK's hard failure.
     *
     * <p>{@link McpError} rather than an error {@code CallToolResult}, and the difference is not
     * cosmetic: the SDK propagates an {@code McpError} as a JSON-RPC error carrying its code, while
     * a plain runtime exception becomes a result with {@code isError} and a text blob. Only the
     * first puts {@code -32041} where a client looks for it — and only the first stops a model
     * treating a scope refusal as something to rephrase its way around.
     */
    private static McpError asMcpError(McpToolException e) {
        return McpError.builder(e.jsonRpcCode())
                .message(e.getMessage())
                .data(Map.of("guard", e.guard() == null ? "NONE" : e.guard().name(),
                        "code", e.errorCode().name()))
                .build();
    }

    private CallToolResult tooLarge(String tool, long outputBytes) {
        return CallToolResult.builder()
                .isError(true)
                .addTextContent(("%s produced %d bytes, over this server's %d byte ceiling "
                        + "(explorer.mcp.hard-max-output-bytes), so nothing was returned. "
                        + "The response was NOT truncated — a cut result would be indistinguishable "
                        + "from a complete one. Ask for less: a lower maxRows or n, a narrower "
                        + "prefix, fewer topics, or a WHERE that filters further.")
                        .formatted(tool, outputBytes, properties.getHardMaxOutputBytes()))
                .build();
    }

    /**
     * The serialised size of a response, or why it could not be taken.
     *
     * <p>Unmeasured rather than {@code 0}: a size that could not be computed is not a small
     * response, and the ceiling is deliberately <em>not</em> applied to one — refusing a payload
     * because this mapper choked on it would fail a call the transport's own mapper could have
     * sent perfectly well. The console shows the hole instead of a zero that would read as a tiny
     * answer.
     */
    private Measured<Long> sizeOf(CallToolResult result) {
        try {
            return Measured.of((long) sizer.writeValueAsBytes(result).length);
        } catch (JsonProcessingException | RuntimeException e) {
            log.debug("MCP output size could not be measured", e);
            return Measured.unmeasured("the response could not be serialised for measurement: "
                    + e.getClass().getSimpleName());
        }
    }

    private void record(String correlationId, Instant startedAt, long startedNanos, String tool,
                        String identity, String clientInfo, Map<String, Object> redactedParams,
                        McpCallRecord.Outcome outcome, Integer jsonRpcCode, McpGuard deniedBy,
                        Object payload, boolean truncated, Measured<Long> outputBytes) {
        recorder.record(new McpCallRecord(
                correlationId,
                startedAt,
                (System.nanoTime() - startedNanos) / 1_000_000L,
                McpCallRecord.Origin.AGENT,
                identity,
                clientInfo,
                tool,
                redactedParams,
                outcome,
                jsonRpcCode,
                deniedBy,
                McpCallContext.recordsScanned(payload),
                McpCallContext.stopReason(payload),
                truncated,
                outputBytes));
    }

    /**
     * Who is calling.
     *
     * <p>The session id until OAuth lands in phase 5, and named as the placeholder it is rather
     * than dressed up as a subject: the console must not show a transport identifier in a column
     * headed "identity" as though it had been authenticated.
     */
    private static String identityOf(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return LOCAL_IDENTITY;
        }
        String sessionId = exchange.sessionId();
        return sessionId == null || sessionId.isBlank() ? LOCAL_IDENTITY : "session:" + sessionId;
    }

    /** What the client declared at {@code initialize}, e.g. {@code claude-code/1.4.2}. */
    private static String clientInfoOf(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return null;
        }
        Implementation client = exchange.getClientInfo();
        if (client == null) {
            return null;
        }
        return client.version() == null ? client.name() : client.name() + "/" + client.version();
    }
}
