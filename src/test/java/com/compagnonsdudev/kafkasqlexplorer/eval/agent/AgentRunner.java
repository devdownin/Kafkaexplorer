// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tool loop: model ↔ MCP client, bounded.
 *
 * <p><b>The bound is an assertion, not a safety cut-off</b> ({@code SPECAGENT.md} §5.2). One of this
 * server's arguments is that a question costs one call where a {@code consume_messages} costs N, so
 * an agent that spends six calls on a one-call question has denied the argument even when it ends up
 * right. Overrunning therefore fails the scenario — {@link ToolCallTrace} says so from the trace —
 * and the loop stops rather than paying for an answer that is already a failure.
 *
 * <p><b>The system prompt says nothing about how to read a coverage envelope.</b> That is the whole
 * experiment: the tool descriptions already carry the reading rule ahead of the payload, deliberately
 * and at some cost, and a harness that repeated the rule in its own prompt would be measuring its
 * prompt rather than the server. What it does say is what any agent framework says — you have tools,
 * use them, answer the user — because withholding *that* would measure a model's ability to guess a
 * harness's conventions.
 */
final class AgentRunner {

    /**
     * Deliberately generic. Every sentence here would appear in any agent scaffold; nothing in it
     * mentions coverage, stop reasons, measured values or refusals, because those are exactly what
     * the run is asking whether the tool descriptions teach on their own.
     */
    private static final String SYSTEM_PROMPT = """
            You are an engineer answering a question about a Kafka cluster.

            You have tools. Call the ones you need, read what they return, and then answer the \
            question directly in plain prose. When you have enough to answer, answer — do not call \
            a tool again to be sure.""";

    private final AgentModel model;
    private final McpHttpClient mcp;

    AgentRunner(AgentModel model, McpHttpClient mcp) {
        this.model = model;
        this.mcp = mcp;
    }

    /**
     * What one session produced.
     *
     * @param answer     the agent's final prose, which is all the judge ever sees
     * @param trace      verdict 1's material
     * @param overrun    the bound that was hit, or null when the agent finished on its own
     */
    record Session(String answer, ToolCallTrace trace, String overrun) {
    }

    Session run(AgentScenario scenario) {
        List<McpHttpClient.ToolSpec> tools = mcp.listTools();
        Set<String> listed = new LinkedHashSet<>(tools.stream()
                .map(McpHttpClient.ToolSpec::name).toList());

        List<AgentModel.Exchange> transcript = new ArrayList<>();
        transcript.add(new AgentModel.Exchange.User(scenario.prompt()));

        long deadline = System.currentTimeMillis() + scenario.budgetMs();
        int spent = 0;
        String answer = "";
        String overrun = null;

        while (true) {
            AgentModel.Turn turn = model.respond(SYSTEM_PROMPT, List.copyOf(transcript), tools);
            answer = turn.text();
            if (turn.isFinal()) {
                break;
            }
            transcript.add(new AgentModel.Exchange.Assistant(turn.text(), turn.calls()));

            for (AgentModel.RequestedCall requested : turn.calls()) {
                // The ceiling is checked before the call, so a run never exceeds it — the trace
                // records what was actually spent and the assertion reads that.
                if (spent >= scenario.effectiveMaxCalls()) {
                    overrun = "it asked for more than the " + scenario.effectiveMaxCalls()
                            + " tool calls this scenario allows";
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    overrun = "it ran past the " + scenario.budgetMs() + " ms budget";
                    break;
                }
                McpHttpClient.ToolAnswer result =
                        mcp.callTool(requested.name(), requested.arguments());
                spent++;
                // The refusal goes back to the model as content. Absorbing it here would answer
                // the question the guard scenarios exist to ask.
                transcript.add(new AgentModel.Exchange.ToolResult(requested.id(), requested.name(),
                        result.text(), result.refusalCode()));
            }
            if (overrun != null) {
                break;
            }
        }
        return new Session(answer, new ToolCallTrace(mcp.trace(), listed), overrun);
    }
}
