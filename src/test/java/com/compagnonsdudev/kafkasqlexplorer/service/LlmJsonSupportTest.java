// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last line of defence on every unconstrained path — SpectraLLM, an arbitrary
 * OpenAI-compatible gateway, {@code structured-output: OFF}, and any endpoint that refused a schema
 * once. It had no test of its own.
 */
class LlmJsonSupportTest {

    @Test
    void extractsPlainJson() {
        assertEquals("{\"a\": 1}", LlmJsonSupport.extractJsonPayload("{\"a\": 1}"));
    }

    @Test
    void extractsJsonWrappedInMarkdownFences() {
        assertEquals("{\"a\": 1}",
            LlmJsonSupport.extractJsonPayload("```json\n{\"a\": 1}\n```"));
    }

    @Test
    void extractsJsonSurroundedByProse() {
        assertEquals("{\"a\": 1}",
            LlmJsonSupport.extractJsonPayload("Here is the result:\n{\"a\": 1}\nHope that helps."));
    }

    /**
     * The defect this class was blind to, on the model the application ships as its default.
     * {@code qwen3:4b} is a reasoning model: through Ollama it emits its whole train of thought
     * before the answer — and a model reasoning about JSON quotes fragments of that JSON while it
     * does so, so the first {@code &#123;} in the text sits inside the trace. The balanced scan then
     * returned a piece of the deliberation, and a run that had produced a perfectly good answer was
     * reported as "Failed to parse the model's response as JSON".
     */
    @Test
    void ignoresAReasoningTraceThatQuotesTheJsonItIsAboutToWrite() {
        String raw = """
            <think>
            The user wants a flowchart. I should answer with {"flowchart": "..."} and
            probably {"anomalies": []} if I find nothing.
            </think>
            {"flowchart": "flowchart TD\\n A-->B", "anomalies": []}
            """;

        assertEquals("{\"flowchart\": \"flowchart TD\\n A-->B\", \"anomalies\": []}",
            LlmJsonSupport.extractJsonPayload(raw));
    }

    @Test
    void ignoresAReasoningTraceBeforeAFencedAnswer() {
        // The fence becomes the first thing in the text once the trace is gone, which is why the
        // reasoning has to be stripped before the fences and not after.
        String raw = "<think>Let me think about {this}.</think>\n```json\n{\"a\": 1}\n```";
        assertEquals("{\"a\": 1}", LlmJsonSupport.extractJsonPayload(raw));
    }

    @Test
    void handlesTheOtherSpellingsAndAnyCase() {
        assertEquals("{\"a\": 1}",
            LlmJsonSupport.extractJsonPayload("<thinking>{x}</thinking>{\"a\": 1}"));
        assertEquals("{\"a\": 1}",
            LlmJsonSupport.extractJsonPayload("<Reasoning>{x}</REASONING>{\"a\": 1}"));
    }

    @Test
    void handlesSeveralReasoningBlocks() {
        assertEquals("{\"a\": 1}",
            LlmJsonSupport.extractJsonPayload("<think>{x}</think>hm<think>{y}</think>{\"a\": 1}"));
    }

    /**
     * An opening tag with no closing one is the model hitting its output cap while still thinking:
     * there is no answer, and the honest outcome is nothing rather than a train of thought parsed
     * as a result.
     */
    @Test
    void dropsEverythingAfterAnUnterminatedReasoningBlock() {
        String raw = "<think>I should return {\"flowchart\": \"flowchart TD\" and then";
        assertEquals("", LlmJsonSupport.extractJsonPayload(raw));
        assertTrue(LlmJsonSupport.hasUnterminatedReasoning(raw));
    }

    @Test
    void aClosedBlockIsNotReportedAsUnterminated() {
        assertFalse(LlmJsonSupport.hasUnterminatedReasoning("<think>done</think>{\"a\": 1}"));
        assertFalse(LlmJsonSupport.hasUnterminatedReasoning("{\"a\": 1}"));
        assertFalse(LlmJsonSupport.hasUnterminatedReasoning(null));
    }

    /** A payload that merely mentions the tag inside a string must not be truncated. */
    @Test
    void leavesTextAloneWhenThereIsNoReasoningBlock() {
        String raw = "{\"comments\": \"the <thinking part> of the pipeline\"}";
        assertEquals(raw, LlmJsonSupport.extractJsonPayload(raw));
    }

    @Test
    void returnsTheTextUnchangedWhenItHoldsNoJson() {
        assertEquals("I cannot help with that.",
            LlmJsonSupport.extractJsonPayload("I cannot help with that."));
    }

    @Test
    void toleratesNullAndBlank() {
        assertEquals("", LlmJsonSupport.extractJsonPayload(null));
        assertEquals("", LlmJsonSupport.stripReasoning(null));
        assertEquals("", LlmJsonSupport.stripReasoning(""));
    }
}
