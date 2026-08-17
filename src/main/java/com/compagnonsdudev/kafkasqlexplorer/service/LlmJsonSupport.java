// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

final class LlmJsonSupport {

    /**
     * Tags a reasoning model wraps its deliberation in before answering. {@code <think>} is what
     * Qwen3 — the model this application ships as its default — emits through Ollama's
     * OpenAI-compatible endpoint; the other two are the same convention under different names.
     */
    private static final String[] REASONING_TAGS = {"think", "thinking", "reasoning"};

    private LlmJsonSupport() {
    }

    static String extractJsonPayload(String rawText) {
        // Reasoning first, fences second: a trace removed from the front can leave a ``` fence as
        // the first thing in the text, and stripMarkdownFences only acts on a leading one.
        String trimmed = stripMarkdownFences(stripReasoning(rawText)).trim();
        int start = findFirstJsonStart(trimmed);
        if (start < 0) {
            return trimmed;
        }

        int end = findBalancedJsonEnd(trimmed, start);
        if (end < 0) {
            return trimmed;
        }
        return trimmed.substring(start, end + 1).trim();
    }

    /**
     * Removes a reasoning model's deliberation, so only the answer is parsed.
     *
     * <p>This matters on the deployment shipped by default. {@code claude.model} is {@code qwen3:4b},
     * a hybrid reasoning model: through Ollama it puts its whole train of thought in the content,
     * inside {@code <think>} tags, and only then the JSON. {@link #extractJsonPayload} looks for the
     * <em>first</em> {@code &#123;} in the text — and a model reasoning about the JSON it is about to
     * write quotes fragments of that JSON while doing so, so the first brace very often sits inside
     * the trace. The balanced scan then returns a piece of the model's deliberation, and a run that
     * produced a perfectly good answer is reported as "Failed to parse the model's response as JSON".
     *
     * <p>The constrained-decoding path ({@link LlmSchemas}) makes this moot, which is exactly why it
     * cannot be relied on: {@code structured-output} is {@code OFF} for some deployments, absent for
     * SpectraLLM, left alone for an arbitrary {@code OPENAI_COMPATIBLE} gateway, and dropped by
     * {@link OpenAiCompatibleLlmClient} the moment an endpoint refuses a schema. Every one of those
     * paths lands here.
     *
     * <p>An <em>unterminated</em> block — an opening tag with no closing one — is the model hitting
     * its output cap while still thinking. Everything from the tag on is dropped, which leaves
     * nothing: that is the honest outcome, since no answer was ever produced, and the caller reports
     * it as such rather than parsing a train of thought as a result.
     */
    static String stripReasoning(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        for (String tag : REASONING_TAGS) {
            result = stripTag(result, tag);
        }
        return result;
    }

    /** Whether the text opens a reasoning block it never closes — see {@link #stripReasoning}. */
    static boolean hasUnterminatedReasoning(String text) {
        if (text == null) {
            return false;
        }
        for (String tag : REASONING_TAGS) {
            int open = indexOfIgnoreCase(text, '<' + tag + '>', 0);
            if (open >= 0 && indexOfIgnoreCase(text, "</" + tag + '>', open) < 0) {
                return true;
            }
        }
        return false;
    }

    private static String stripTag(String text, String tag) {
        String open = '<' + tag + '>';
        String close = "</" + tag + '>';
        int first = indexOfIgnoreCase(text, open, 0);
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        int start = first;
        while (start >= 0) {
            out.append(text, cursor, start);
            int end = indexOfIgnoreCase(text, close, start);
            if (end < 0) {
                // Never closed: the model ran out of room mid-thought, so nothing after this is an
                // answer. Drop the remainder rather than letting the trace be parsed as one.
                return out.toString();
            }
            cursor = end + close.length();
            start = indexOfIgnoreCase(text, open, cursor);
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * Case-insensitive {@code indexOf}, matching in place rather than over a lowercased copy. An
     * answer can be hundreds of kilobytes, so copying it once per tag would be wasteful — and, more
     * to the point, {@code toLowerCase} is not length-preserving for every character (U+0130 becomes
     * two), which would silently shift every index derived from the copy.
     */
    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int last = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    static String stripMarkdownFences(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline >= 0) {
            trimmed = trimmed.substring(firstNewline + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        return trimmed;
    }

    private static int findFirstJsonStart(String text) {
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');

        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    private static int findBalancedJsonEnd(String text, int start) {
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                continue;
            }

            if (current == '{' || current == '[') {
                stack.append(current);
                continue;
            }

            if ((current == '}' || current == ']') && stack.length() > 0) {
                char expected = current == '}' ? '{' : '[';
                char actual = stack.charAt(stack.length() - 1);
                if (actual != expected) {
                    return -1;
                }
                stack.deleteCharAt(stack.length() - 1);
                if (stack.length() == 0) {
                    return i;
                }
            }
        }

        return -1;
    }
}
