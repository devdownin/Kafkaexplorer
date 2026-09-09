// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a scenario's {@code serverConfig} the way an operator would: by recreating the container.
 *
 * <p>There is no endpoint that sets an arbitrary {@code explorer.mcp.*} at runtime, and adding one
 * would be exactly the back door {@code SPECAGENT.md} §3 forbids — a harness that reached into
 * internal state would be testing a server nobody deploys. {@code compose/mcp.yml} publishes every
 * guard as an environment variable, so the honest path is to set the variable and recreate the
 * {@code explorer} service. Scenarios sharing a configuration share a boot, which is what keeps the
 * cost at roughly ten seconds per distinct configuration rather than per scenario.
 *
 * <p><b>A key the overlay does not publish is refused, not applied.</b> That is the rule this class
 * exists to enforce: compose passes only the variables the file names, so a setting written into a
 * scenario and absent from the overlay would leave the container on its default while the scenario
 * believed it had changed it — a run that measures the wrong world and reports confidently about
 * it. Refusing names the file to edit.
 */
final class StackReconfigurer implements AutoCloseable {

    /** The overlay's own declarations: {@code - EXPLORER_MCP_X=${EXPLORER_MCP_X:-…}}. */
    private static final Pattern PUBLISHED = Pattern.compile("^\\s*-\\s*([A-Z0-9_]+)=", Pattern.MULTILINE);

    private final Path repositoryRoot;
    private final Set<String> published;
    private final boolean enabled;
    private Map<String, String> applied = Map.of();

    StackReconfigurer(Path repositoryRoot, boolean enabled) {
        this.repositoryRoot = repositoryRoot;
        this.enabled = enabled;
        this.published = readPublished(repositoryRoot.resolve("compose/mcp.yml"));
    }

    private static Set<String> readPublished(Path overlay) {
        if (!Files.isRegularFile(overlay)) {
            return Set.of();
        }
        try {
            Matcher matcher = PUBLISHED.matcher(Files.readString(overlay));
            Set<String> names = new java.util.LinkedHashSet<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return Set.copyOf(names);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + overlay, e);
        }
    }

    /**
     * Spring's relaxed binding, in the one direction that is single-valued.
     *
     * <p>{@code explorer.mcp.hard-max-topics} is {@code EXPLORER_MCP_HARD_MAX_TOPICS}: dots and
     * hyphens both become underscores, and the whole thing uppercases. The reverse is many-to-one
     * and is why nothing here tries to go back the other way.
     */
    static String variableFor(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    /** The variables a scenario's serverConfig maps to, refusing any the overlay does not publish. */
    Map<String, String> resolve(AgentScenario scenario) {
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> unpublished = new ArrayList<>();
        scenario.serverConfig().forEach((property, value) -> {
            String variable = variableFor(property);
            if (!published.contains(variable)) {
                unpublished.add(property + " (" + variable + ")");
            }
            variables.put(variable, value);
        });
        if (!unpublished.isEmpty()) {
            throw new IllegalStateException(scenario.id()
                    + ": compose/mcp.yml does not publish " + unpublished
                    + ", so the container would never see it and the scenario would run against the "
                    + "defaults believing it had changed them. Add the variable to the overlay.");
        }
        return Map.copyOf(variables);
    }

    /** The command that applies {@code variables}, exposed so it can be asserted without Docker. */
    List<String> command() {
        return List.of("docker", "compose",
                "-f", "docker-compose.yml", "-f", "compose/mcp.yml",
                "up", "-d", "--force-recreate", "--no-deps", "explorer");
    }

    /**
     * Brings the stack to {@code scenario}'s configuration, or leaves it alone when it is already
     * there. Returns true when the container was recreated.
     */
    boolean applyTo(AgentScenario scenario) {
        Map<String, String> wanted = resolve(scenario);
        if (wanted.equals(applied)) {
            return false;
        }
        run(wanted);
        applied = wanted;
        return true;
    }

    /**
     * Puts the stack back on the overlay's own defaults.
     *
     * <p>Called from a {@code finally}, and §7 says why: a scenario that narrowed a scope and died
     * leaving it narrowed fails the next one for a reason that does not belong to it — the fault the
     * two {@code FlinkSqlService} suites already paid for and that earned them their
     * {@code @AfterEach}.
     */
    @Override
    public void close() {
        if (!applied.isEmpty()) {
            run(Map.of());
            applied = Map.of();
        }
    }

    private void run(Map<String, String> variables) {
        if (!enabled) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command())
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true);
        // Unset rather than blanked for anything not wanted: compose reads `${VAR:-default}`, and an
        // empty value is a value — it would apply the empty string where the default was meant.
        published.forEach(builder.environment()::remove);
        builder.environment().putAll(variables);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(180, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Recreating the explorer service failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot run docker compose", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted recreating the explorer service", e);
        }
    }
}
