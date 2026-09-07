// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts secrets and obvious personal data from anything leaving through MCP.
 *
 * <p>It runs on three surfaces and the third is the one that is easy to forget: tool output,
 * resource content, and <b>the recorded call parameters</b>. Redacting only what is displayed
 * leaves the secret sitting in the ring buffer and on the audit topic, where it outlives the call
 * and is read by whoever can reach either — so the scrub happens before persistence, not before
 * rendering.
 *
 * <p>DDL is delegated to {@link DdlGeneratorService#maskSensitiveProperties}, which the UI paths
 * already use. One masker, not two: a second pattern set drifts, and the drift is only ever
 * discovered by a credential reaching a page.
 *
 * <p>The patterns below are deliberately conservative. This is a redactor, not a classifier —
 * catching a bearer token and an obvious email address is worth the occasional over-redaction,
 * while a heuristic broad enough to catch everything would mangle the payloads the tools exist to
 * show. {@code explorer.mcp.dlp.mode=off} exists for the deployment that has decided its cluster
 * carries nothing to protect.
 */
public class DlpScrubber {

    private static final String MASK = "******";

    /**
     * {@code key: value} / {@code "key" = "value"} where the key names a credential.
     *
     * <p>The value may contain spaces, and that is not laxity: {@code Authorization: Bearer <jwt>}
     * is the shape this rule most needs to catch, and a value pattern that stopped at whitespace
     * masked the word {@code Bearer} and left the token standing — a redaction that looks like it
     * worked. It stops at a quote, a delimiter or the end of the line, so a quoted JSON value ends
     * where its quote does. The cost is occasional over-redaction of the rest of a line, which is
     * the right side to err on here.
     */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?[\\w.-]*(?:password|passwd|secret|token|api[_.-]?key|credential|authorization)"
                    + "[\\w.-]*[\"']?\\s*[:=]\\s*)([\"']?)([^,;\"'}\\r\\n]+)\\2");

    /** A bearer token in a header-shaped string. */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]{8,}=*");

    /** An email address — the one PII shape common enough in Kafka payloads to be worth a rule. */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final McpProperties properties;

    public DlpScrubber(McpProperties properties) {
        this.properties = properties;
    }

    public boolean active() {
        return properties.getDlp().getMode() != McpProperties.Dlp.Mode.OFF;
    }

    /** Scrubs a free-text payload — a message body, an error message, a SQL string. */
    public String scrub(String text) {
        if (text == null || !active()) {
            return text;
        }
        // Bearer first, deliberately: a token can sit in a value whose key names nothing sensitive
        // (a payload field called `header`, a nested log line), and the assignment rule would then
        // never look at it. Running it first also means the assignment rule that follows collapses
        // an already-masked `Bearer ******` into one mask rather than two.
        String out = BEARER.matcher(text).replaceAll("$1" + MASK);
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1$2" + MASK + "$2");
        return EMAIL.matcher(out).replaceAll(MASK);
    }

    /** Scrubs DDL through the same masker the UI paths use. */
    public String scrubDdl(String ddl) {
        return active() ? DdlGeneratorService.maskSensitiveProperties(ddl) : ddl;
    }

    /**
     * Scrubs recorded call parameters before they are kept.
     *
     * <p>Keys are matched as well as values: a parameter literally named {@code password} is
     * redacted whatever its value looks like, which the value patterns alone would miss for a
     * secret that happens to be a plain word.
     */
    public Map<String, Object> scrubParams(Map<String, Object> params) {
        if (params == null || params.isEmpty() || !active()) {
            return params == null ? Map.of() : params;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        params.forEach((key, value) -> out.put(key, scrubValue(key, value)));
        return out;
    }

    private Object scrubValue(String key, Object value) {
        if (sensitiveKey(key)) {
            return MASK;
        }
        return value instanceof String s ? scrub(s) : value;
    }

    private static boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("apikey") || lower.contains("api_key");
    }
}
