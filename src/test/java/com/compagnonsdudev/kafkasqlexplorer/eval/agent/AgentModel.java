// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.List;
import java.util.Map;

/**
 * The model under test, reduced to the one thing the harness needs of it: a turn.
 *
 * <p>An interface rather than a call to {@code LlmClient}, and that is forced rather than chosen —
 * {@code LlmClient.generate(system, user)} returns a String and has no notion of a tool, so the
 * application's own client cannot drive an agent loop. What is reused instead is the
 * <b>configuration</b> ({@code CLAUDE_PROVIDER}, the keys, the model name), exactly as
 * {@code SPECAGENT.md} §5.3 asks: no new setting.
 *
 * <p>It also exists so the runner can be tested without a model. {@code AgentRunnerTest} drives a
 * scripted implementation through the real MCP client against a stub server, which is what makes
 * the bounded loop and the trace capture assertable in {@code mvn verify} rather than only in a run
 * that costs money.
 */
interface AgentModel {

    /** A tool the model asked for, with the arguments it chose. */
    record RequestedCall(String id, String name, Map<String, Object> arguments) {
    }

    /**
     * What the model said this turn.
     *
     * <p>Both halves can be present: a model may narrate and call in the same turn. The loop ends
     * on a turn with no call, which is the model's own signal that it has answered.
     */
    record Turn(String text, List<RequestedCall> calls) {

        public Turn {
            text = text == null ? "" : text;
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        public boolean isFinal() {
            return calls.isEmpty();
        }
    }

    /** One entry of the conversation, rendered by each implementation into its provider's shape. */
    sealed interface Exchange {

        record User(String text) implements Exchange {
        }

        record Assistant(String text, List<RequestedCall> calls) implements Exchange {
        }

        /**
         * A tool answer going back to the model.
         *
         * <p>{@code refusalCode} travels with it because a refusal is <b>content the model must
         * read</b>, not an error the harness absorbs: the scenarios that matter most are about what
         * the agent does after being told no.
         */
        record ToolResult(String callId, String name, String text, Integer refusalCode)
                implements Exchange {
        }
    }

    /** The next turn, given the conversation so far and the tools the server actually listed. */
    Turn respond(String systemPrompt, List<Exchange> transcript, List<McpHttpClient.ToolSpec> tools);

    /** What answered, for the report — a verdict has to say which model produced it. */
    String describe();
}
