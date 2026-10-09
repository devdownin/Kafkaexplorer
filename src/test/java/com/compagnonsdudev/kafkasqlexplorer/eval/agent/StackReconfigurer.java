// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * <p><b>A recreated container is waited for.</b> {@code docker compose up -d} returns once the
 * container has <em>started</em>, which for this application is a minute before it answers — and in
 * that minute the published port accepts a connection and closes it without a byte. The first real
 * run skipped twenty scenarios in eight seconds on exactly that, each one reported as an endpoint
 * that "did not answer". So every recreation is followed by a poll of {@link Readiness} and fails
 * with a sentence of its own if the service never comes back, which the caller turns into a skip
 * that names the cause rather than twenty that name a symptom.
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

    /** Whether the recreated service takes requests yet. Polled, so it must be cheap and never throw. */
    @FunctionalInterface
    interface Readiness {

        boolean ready();

        /** No wait: for a reconfigurer that is only asked what it would run. */
        static Readiness immediately() {
            return () -> true;
        }

        /**
         * {@code GET url} answers 200 with {@code "status":"UP"}. A refused connection, a reset one
         * and a connection closed without a byte — what the published port does while the container
         * behind it boots — all mean "not yet", never an error.
         */
        static Readiness http(URI url) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            return () -> {
                try {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    return response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
                } catch (IOException e) {
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }

    private final Path repositoryRoot;
    private final Set<String> published;
    private final boolean enabled;
    private final Readiness readiness;
    private final Duration readyWithin;
    private final Duration pollEvery;
    private Map<String, String> applied = Map.of();

    StackReconfigurer(Path repositoryRoot, boolean enabled) {
        this(repositoryRoot, enabled, Readiness.immediately());
    }

    /** Four minutes, because a cold Spring Boot with an embedded Flink on a shared runner needs one. */
    StackReconfigurer(Path repositoryRoot, boolean enabled, Readiness readiness) {
        this(repositoryRoot, enabled, readiness, Duration.ofSeconds(240), Duration.ofSeconds(2));
    }

    StackReconfigurer(Path repositoryRoot, boolean enabled, Readiness readiness,
                      Duration readyWithin, Duration pollEvery) {
        this.repositoryRoot = repositoryRoot;
        this.enabled = enabled;
        this.readiness = readiness;
        this.readyWithin = readyWithin;
        this.pollEvery = pollEvery;
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
        recreate(wanted);
        // Recorded before the wait: the container now runs this configuration whether or not it
        // comes up in time, and a scenario that asks for the same one must not recreate it again —
        // twenty four-minute timeouts in a row is what that would cost.
        applied = wanted;
        awaitReady();
        return true;
    }

    /** Polls until the service answers, or fails naming how long it waited. */
    void awaitReady() {
        if (!enabled) {
            return;
        }
        long deadline = System.nanoTime() + readyWithin.toNanos();
        while (!readiness.ready()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("The explorer service was recreated for this scenario "
                        + "but did not answer within " + readyWithin.toSeconds() + " s, so no scenario "
                        + "that needs it can run. Its log is in the stack.log of the uploaded report.");
            }
            try {
                Thread.sleep(pollEvery.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the explorer service", e);
            }
        }
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
            // Not waited for: this hands the stack back on its defaults at the end of the suite, and
            // a close() that could time out would fail a run that had already reported.
            recreate(Map.of());
            applied = Map.of();
        }
    }

    private void recreate(Map<String, String> variables) {
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
