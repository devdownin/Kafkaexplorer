// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

/**
 * A ready-to-paste client configuration, generated from the endpoint actually bound.
 *
 * <p><b>No token is ever placed in the snippet</b>, and {@code tokenHint} says where one goes
 * instead. A generated snippet is pasted into a file, a chat, a ticket; putting a credential in it
 * makes the console the thing that leaked it, on the screen whose subject is controlling what an
 * agent may see.
 *
 * @param client    the target — {@code claude-code}, {@code claude-desktop}, {@code generic}
 * @param format    the file the snippet belongs in, for the caption above it
 * @param snippet   the configuration itself
 * @param tokenHint where the credential goes, when the deployment needs one; null when it does not
 */
public record McpClientConfig(String client, String format, String snippet, String tokenHint) {}
