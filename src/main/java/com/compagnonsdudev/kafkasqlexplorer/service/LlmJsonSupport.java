// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

final class LlmJsonSupport {

    private LlmJsonSupport() {
    }

    static String extractJsonPayload(String rawText) {
        String trimmed = stripMarkdownFences(rawText).trim();
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
