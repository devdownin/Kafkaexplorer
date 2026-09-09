// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads and <b>validates</b> the scenario directory.
 *
 * <p>Validation is the whole point, and it is strict in both directions. A missing field is
 * refused, and so is an <i>unknown</i> one: a scenario carrying {@code mustNotCite} — a key this
 * harness has never had — would otherwise load, run, and report green having asserted nothing,
 * which is the false green {@code SPECAGENT.md} §7 says is worse than no harness at all. A typo in
 * a scenario file is not a scenario that tests slightly less; it is a scenario that tests less than
 * its author believes, silently.
 *
 * <p>{@link SafeConstructor} rather than the default: a scenario file is data, and YAML's default
 * constructor instantiates arbitrary Java types named in the document. Nothing in this tree needs
 * that, and a test resource is exactly the kind of file that gets copied in from elsewhere.
 */
final class ScenarioLoader {

    /** The format's spelling for "a returned resume token must be followed", from §3. */
    private static final Pattern MUST_CALL = Pattern.compile("^mustCall\\((\\w+)\\)$");

    private static final List<String> TOP_LEVEL = List.of(
            "id", "title", "invariant", "serverConfig", "fixture", "prompt",
            "maxToolCalls", "budgetMs", "trace", "verdict", "expectRefusal", "midSession");
    private static final List<String> MID_SESSION_KEYS =
            List.of("afterCalls", "disableTool", "reason");

    /**
     * The one setting outside {@code explorer.mcp.*} a scenario may move, named rather than a
     * pattern.
     *
     * <p>{@code dependency-down-is-not-a-guard} needs a dependency that is genuinely unreachable,
     * which no {@code explorer.mcp.*} value can produce — and §4.5 recorded that as the reason the
     * scenario could not be written. Pointing the application at an address nothing answers is how
     * an operator makes a dependency unavailable; it is a published deployment setting, not the
     * internal state §3 forbids reaching into. It is a list of exactly one so that widening it is a
     * decision somebody makes on purpose.
     */
    private static final List<String> SETTINGS_OUTSIDE_MCP = List.of("kafka.bootstrap-servers");
    private static final List<String> FIXTURE_KEYS = List.of("seeder", "requires");
    private static final List<String> REQUIRES_KEYS = List.of("topics", "keys");
    private static final List<String> TRACE_KEYS =
            List.of("mustCall", "mustNotCall", "onResumeTokenReturned", "maxCalls");
    private static final List<String> VERDICT_KEYS =
            List.of("mustAssert", "mustNotAssert", "mustQualify", "mustCite");

    private ScenarioLoader() {
    }

    /**
     * Every {@code *.yaml} under {@code directory}, sorted by id so a report's lines do not move
     * between runs.
     *
     * <p>An empty directory returns an empty list rather than throwing: "there are no scenarios"
     * is a legitimate state for a harness under construction, and the caller reports it as such.
     * A directory that does not exist is a different answer and also empty — the caller's skip
     * reason says which.
     */
    static List<AgentScenario> loadAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(ScenarioLoader::load)
                    .sorted(Comparator.comparing(AgentScenario::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the scenario directory " + directory, e);
        }
    }

    static AgentScenario load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the scenario " + file, e);
        }
    }

    static AgentScenario parse(InputStream in, String origin) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);   // a duplicated key silently keeps the last one
        Object document = new Yaml(new SafeConstructor(options)).load(in);
        if (!(document instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(origin + ": expected a YAML mapping at the top level");
        }
        Map<String, Object> root = asStringKeyed(map, origin, "the document");
        rejectUnknownKeys(root, TOP_LEVEL, origin, "the document");

        String id = requireText(root, "id", origin);
        // The id names the JUnit case and the report line, and a scenario file whose id disagrees
        // with its own name is one nobody finds from a failure message.
        String expectedFile = id + ".yaml";
        if (!expectedFile.equals(origin)) {
            throw new IllegalArgumentException(
                    origin + ": id '" + id + "' does not match the file name (expected "
                            + expectedFile + ")");
        }

        AgentScenario.Fixture fixture = fixture(root, origin);
        AgentScenario.Trace trace = trace(root, origin);
        AgentScenario.Verdict verdict = verdict(root, origin);
        Integer expectRefusal = root.get("expectRefusal") == null
                ? null : requireInt(root, "expectRefusal", origin);
        AgentScenario.MidSession midSession = midSession(root, origin);

        AgentScenario scenario = new AgentScenario(
                id,
                requireText(root, "title", origin),
                requireText(root, "invariant", origin),
                serverConfig(root, origin),
                fixture,
                requireText(root, "prompt", origin),
                requireInt(root, "maxToolCalls", origin),
                requireInt(root, "budgetMs", origin),
                trace,
                verdict,
                expectRefusal,
                midSession);

        validate(scenario, origin);
        return scenario;
    }

    /**
     * The cross-field rules — the ones a per-field check cannot see.
     *
     * <p>Each of these describes a scenario that would run and prove nothing, which is the failure
     * this loader exists to make loud.
     */
    private static void validate(AgentScenario s, String origin) {
        if (s.maxToolCalls() <= 0 || s.budgetMs() <= 0) {
            throw new IllegalArgumentException(
                    origin + ": maxToolCalls and budgetMs are hard bounds and must be positive");
        }
        if (s.effectiveMaxCalls() > s.maxToolCalls()) {
            throw new IllegalArgumentException(
                    origin + ": trace.maxCalls (" + s.trace().maxCalls() + ") exceeds maxToolCalls ("
                            + s.maxToolCalls() + "), so the looser bound would never be reached");
        }
        for (String tool : s.trace().mustCall()) {
            if (s.trace().mustNotCall().contains(tool)) {
                throw new IllegalArgumentException(
                        origin + ": " + tool + " is in both mustCall and mustNotCall");
            }
        }
        if (s.trace().mustCall().isEmpty() && s.verdict().isEmpty() && s.expectRefusal() == null) {
            throw new IllegalArgumentException(
                    origin + ": the scenario asserts nothing — no mustCall, no verdict grid and no "
                            + "expectRefusal, so it would report green having measured nothing");
        }
        if (s.midSession() != null && s.midSession().afterCalls() >= s.effectiveMaxCalls()) {
            throw new IllegalArgumentException(
                    origin + ": midSession.afterCalls (" + s.midSession().afterCalls()
                            + ") leaves no call for the agent to make after the switch, so the "
                            + "scenario would assert nothing about how it reads the refusal");
        }
        if (s.fixture().topics().isEmpty() && s.fixture().keys().isEmpty()) {
            throw new IllegalArgumentException(
                    origin + ": fixture.requires names neither a topic nor a key, so nothing "
                            + "resolves it against the seeder");
        }
    }

    private static AgentScenario.MidSession midSession(Map<String, Object> root, String origin) {
        Object raw = root.get("midSession");
        if (raw == null) {
            return null;
        }
        Map<String, Object> block = requireMap(root, "midSession", origin);
        rejectUnknownKeys(block, MID_SESSION_KEYS, origin, "midSession");
        int afterCalls = requireInt(block, "afterCalls", origin);
        if (afterCalls < 1) {
            // Zero would fire before the agent has called anything, which is a serverConfig with
            // extra steps — and would test the registration-time path this scenario exists to tell
            // apart from the runtime one.
            throw new IllegalArgumentException(
                    origin + ": midSession.afterCalls must be at least 1, so the gesture lands "
                            + "during the session rather than before it");
        }
        return new AgentScenario.MidSession(afterCalls,
                requireText(block, "disableTool", origin),
                requireText(block, "reason", origin));
    }

    private static AgentScenario.Fixture fixture(Map<String, Object> root, String origin) {
        Map<String, Object> fixture = requireMap(root, "fixture", origin);
        rejectUnknownKeys(fixture, FIXTURE_KEYS, origin, "fixture");
        Map<String, Object> requires = requireMap(fixture, "requires", origin);
        rejectUnknownKeys(requires, REQUIRES_KEYS, origin, "fixture.requires");
        return new AgentScenario.Fixture(
                requireText(fixture, "seeder", origin),
                strings(requires, "topics", origin),
                strings(requires, "keys", origin));
    }

    private static AgentScenario.Trace trace(Map<String, Object> root, String origin) {
        Map<String, Object> trace = requireMap(root, "trace", origin);
        rejectUnknownKeys(trace, TRACE_KEYS, origin, "trace");
        Object resume = trace.get("onResumeTokenReturned");
        String resumeTool = null;
        if (resume != null) {
            Matcher matcher = MUST_CALL.matcher(resume.toString().trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        origin + ": onResumeTokenReturned takes the form mustCall(<tool>), not '"
                                + resume + "'");
            }
            resumeTool = matcher.group(1);
        }
        return new AgentScenario.Trace(
                strings(trace, "mustCall", origin),
                strings(trace, "mustNotCall", origin),
                resumeTool,
                trace.get("maxCalls") == null ? 0 : requireInt(trace, "maxCalls", origin));
    }

    private static AgentScenario.Verdict verdict(Map<String, Object> root, String origin) {
        Object raw = root.get("verdict");
        if (raw == null) {
            return new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of());
        }
        Map<String, Object> verdict = requireMap(root, "verdict", origin);
        rejectUnknownKeys(verdict, VERDICT_KEYS, origin, "verdict");
        return new AgentScenario.Verdict(
                strings(verdict, "mustAssert", origin),
                strings(verdict, "mustNotAssert", origin),
                strings(verdict, "mustQualify", origin),
                strings(verdict, "mustCite", origin));
    }

    /**
     * {@code serverConfig} as strings, whatever YAML made of the values.
     *
     * <p>They are applied as Spring properties, which are strings; letting YAML decide that
     * {@code 2000} is an Integer and {@code demo.} is a String only to stringify both again buys a
     * type that never survives the trip.
     */
    private static Map<String, String> serverConfig(Map<String, Object> root, String origin) {
        Object raw = root.get("serverConfig");
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> config = requireMap(root, "serverConfig", origin);
        Map<String, String> flat = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (value == null) {
                throw new IllegalArgumentException(
                        origin + ": serverConfig." + key + " has no value");
            }
            if (!key.startsWith("explorer.mcp.") && !SETTINGS_OUTSIDE_MCP.contains(key)) {
                // §3: the harness knows no back door. A setting outside the module's own prefix
                // would be reconfiguring the application around the surface under test — except for
                // the one named in SETTINGS_OUTSIDE_MCP, and the exception is a list rather than a
                // pattern so that adding to it is a decision.
                throw new IllegalArgumentException(
                        origin + ": serverConfig accepts explorer.mcp.* and " + SETTINGS_OUTSIDE_MCP
                                + ", not " + key);
            }
            flat.put(key, String.valueOf(value));
        });
        return Map.copyOf(flat);
    }

    private static void rejectUnknownKeys(Map<String, Object> map, List<String> known,
                                         String origin, String where) {
        List<String> unknown = new ArrayList<>(map.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    origin + ": unknown key(s) in " + where + ": " + unknown
                            + ". Known keys are " + known);
        }
    }

    private static Map<String, Object> asStringKeyed(Map<?, ?> map, String origin, String where) {
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(
                        origin + ": " + where + " has a non-string key " + key);
            }
            typed.put(text, value);
        });
        return typed;
    }

    private static Map<String, Object> requireMap(Map<String, Object> parent, String key,
                                                  String origin) {
        Object raw = parent.get(key);
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(origin + ": '" + key + "' must be a mapping");
        }
        return asStringKeyed(map, origin, key);
    }

    private static String requireText(Map<String, Object> parent, String key, String origin) {
        Object raw = parent.get(key);
        if (raw == null || raw.toString().isBlank()) {
            throw new IllegalArgumentException(origin + ": '" + key + "' is required and not blank");
        }
        return raw.toString().trim();
    }

    private static int requireInt(Map<String, Object> parent, String key, String origin) {
        Object raw = parent.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(
                origin + ": '" + key + "' must be a number, not " + describe(raw));
    }

    private static List<String> strings(Map<String, Object> parent, String key, String origin) {
        Object raw = parent.get(key);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(origin + ": '" + key + "' must be a list");
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item == null || item.toString().isBlank()) {
                throw new IllegalArgumentException(origin + ": '" + key + "' holds a blank entry");
            }
            values.add(item.toString().trim());
        }
        return List.copyOf(values);
    }

    private static String describe(Object raw) {
        return raw == null ? "nothing"
                : raw.getClass().getSimpleName().toLowerCase(Locale.ROOT) + " '" + raw + "'";
    }
}
