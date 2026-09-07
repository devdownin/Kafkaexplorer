// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallOrigin;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs one tool from the console, down the path an agent's call takes.
 *
 * <p><b>The same specifications, not a parallel route.</b> It resolves the tool out of the very
 * list the MCP transport serves — already wrapped by the interceptor — so the scope check, the
 * ceilings, the redaction and the recording all apply exactly as they would to an agent. A "Try it"
 * that reached the tool method directly would be a way to exercise the surface without the guard,
 * which is the opposite of what the button is for: an operator presses it to see what their agent
 * would get, and a different answer is a useless one.
 *
 * <p>Only the attribution differs. The call is marked {@code CONSOLE}, so it counts toward the load
 * it really causes without appearing in the answer to "what has my agent been doing?".
 */
public class McpToolInvoker {

    private final ObjectProvider<List<SyncToolSpecification>> toolSpecs;

    public McpToolInvoker(ObjectProvider<List<SyncToolSpecification>> toolSpecs) {
        this.toolSpecs = toolSpecs;
    }

    /** The tools the console may run — exactly those an agent can see. */
    public List<String> invocableTools() {
        return specifications().stream().map(spec -> spec.tool().name()).sorted().toList();
    }

    /**
     * Invokes {@code tool}, or reports why it could not be.
     *
     * <p>{@link McpError} is caught rather than propagated: a protocol refusal is the correct answer
     * to show in the panel — that *is* what the agent would receive — and letting it escape would
     * turn an informative refusal into a 500 on the operator's screen.
     */
    public McpTryResult invoke(String tool, Map<String, Object> arguments) {
        Optional<SyncToolSpecification> specification = specifications().stream()
                .filter(spec -> spec.tool().name().equals(tool))
                .findFirst();

        if (specification.isEmpty()) {
            // `.formatted` on the whole message, not on the last literal of the concatenation:
            // written the other way it binds to the fragment it touches, which carries no
            // placeholder, and the operator reads "no tool named %s is registered" — the one
            // sentence in this panel whose job is to name the tool. Caught by CodeQL, which counts
            // placeholders against arguments.
            return McpTryResult.notInvocable(tool, ("no tool named %s is registered. Withheld "
                    + "tools are listed in the catalogue with the reason they are hidden; a hidden "
                    + "tool cannot be run from here either — \"Try it\" is not a way around the "
                    + "guard.").formatted(tool));
        }

        try {
            CallToolResult result = McpCallOrigin.as(McpCallRecord.Origin.CONSOLE,
                    () -> specification.get().callHandler().apply(null, request(tool, arguments)));
            return McpTryResult.of(tool, result);
        } catch (McpError e) {
            return McpTryResult.refused(tool, e.getJsonRpcError().code(), e.getJsonRpcError().message());
        }
    }

    /**
     * The builder rather than {@code new CallToolRequest(name, arguments)}, which the SDK
     * deprecated: the constructor fixes the argument shape, while the builder is where the meta and
     * progress-token fields a later phase will need are set.
     */
    private static CallToolRequest request(String tool, Map<String, Object> arguments) {
        return CallToolRequest.builder().name(tool).arguments(arguments).build();
    }

    private List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> specs = toolSpecs.getIfAvailable();
        return specs == null ? List.of() : specs;
    }
}
