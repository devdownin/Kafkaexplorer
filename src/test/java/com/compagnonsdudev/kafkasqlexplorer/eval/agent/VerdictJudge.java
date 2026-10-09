// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verdict 2: a second model call, scoring the grid of decisions from {@code SPECAGENT.md} §2.2.
 *
 * <p><b>The judge sees the agent's answer and the grid, and nothing else.</b> Not the trace, not the
 * scenario's prompt, not its title, not its invariant. A judge shown the trace would score the
 * approach, which the trace already asserts mechanically and exactly — and two measurements of the
 * same thing do not make a better one, they make one that can disagree with itself. A judge shown
 * the invariant would be told what the right answer is and asked whether the agent found it, which
 * is a leading question.
 *
 * <p><b>It scores decisions, never wordings.</b> The instruction says so in as many words, because
 * the failure this whole design avoids is the one {@code LlmAnalysisEvalTest} names: a tighter
 * assertion on a model's prose is a test that fails on a paraphrase, and a judge left to its own
 * taste supplies that tightness for free.
 */
final class VerdictJudge {

    private static final String SYSTEM_PROMPT = """
            You are grading one answer written by another engineer, against a checklist.

            Grade DECISIONS, never wording. The answer may be phrased any way at all; what you are \
            deciding is whether it made, or avoided making, each claim on the list. A paraphrase \
            that makes the same claim is met. A different claim in similar words is not met.

            Four kinds of requirement:
            - mustAssert: the answer has to state this.
            - mustNotAssert: the answer must NOT state this. It is met when the claim is absent. \
            An answer that says the opposite, or that declines to claim it, both meet it.
            - mustQualify: the answer has to carry this reservation somewhere, in any words.
            - mustCite: the answer has to quote or name this specific piece of evidence. A general \
            gesture at the idea does not meet it — the point is that the answer shows it read the \
            evidence rather than guessed it.

            Answer with JSON only, no prose around it:
            {"findings":[{"index":0,"met":true,"why":"one sentence"}]}

            One entry per numbered requirement, in the order given. "why" is always required, \
            including when met — it is what makes a disputed grade re-readable.""";

    /** Three tries, then the judge is declared unavailable for this answer. */
    private static final int ATTEMPTS = 3;
    private static final long FIRST_BACKOFF_MS = 5_000;

    private final AgentModel judge;
    private final ObjectMapper json = new ObjectMapper();
    private final int attempts;
    private final Pause pause;

    /** Injectable so a test does not wait out a backoff. */
    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    VerdictJudge(AgentModel judge) {
        this(judge, ATTEMPTS, Thread::sleep);
    }

    VerdictJudge(AgentModel judge, int attempts, Pause pause) {
        this.judge = judge;
        this.attempts = Math.max(1, attempts);
        this.pause = pause;
    }

    JudgeVerdict score(AgentScenario.Verdict grid, String answer) {
        List<Requirement> requirements = flatten(grid);
        if (requirements.isEmpty()) {
            return JudgeVerdict.nothingToJudge();
        }
        if (answer == null || answer.isBlank()) {
            // Not a grade the judge could give: there is nothing to read. Scoring an empty answer
            // as "made none of the forbidden claims" would pass every mustNotAssert for an agent
            // that said nothing at all.
            return JudgeVerdict.notJudged("the agent produced no final answer to grade");
        }

        String reply = null;
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= attempts && reply == null; attempt++) {
            try {
                reply = judge.respond(SYSTEM_PROMPT,
                        List.of(new AgentModel.Exchange.User(prompt(requirements, answer))),
                        List.of()).text();
            } catch (RuntimeException e) {
                failure = e;
                if (attempt < attempts && !backOff(attempt)) {
                    break;
                }
            }
        }
        if (reply == null) {
            // Inconclusive, not failed: a provider that answered 502 or 429 said nothing about
            // the agent, and the answer it was asked to grade stays ungraded.
            return JudgeVerdict.inconclusive("the judge call failed after " + attempts + " attempt(s): "
                    + (failure == null ? "no reply" : failure.getMessage()));
        }
        return read(reply, requirements);
    }

    /** Waits 5 s, then 10 s…; false when interrupted, so the caller stops retrying. */
    private boolean backOff(int attempt) {
        try {
            pause.sleep(FIRST_BACKOFF_MS << (attempt - 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Requirement(String kind, String expectation) {
    }

    private static List<Requirement> flatten(AgentScenario.Verdict grid) {
        List<Requirement> requirements = new ArrayList<>();
        grid.mustAssert().forEach(e -> requirements.add(new Requirement("mustAssert", e)));
        grid.mustNotAssert().forEach(e -> requirements.add(new Requirement("mustNotAssert", e)));
        grid.mustQualify().forEach(e -> requirements.add(new Requirement("mustQualify", e)));
        grid.mustCite().forEach(e -> requirements.add(new Requirement("mustCite", e)));
        return List.copyOf(requirements);
    }

    private static String prompt(List<Requirement> requirements, String answer) {
        StringBuilder text = new StringBuilder("Requirements:\n");
        for (int i = 0; i < requirements.size(); i++) {
            text.append(i).append(". [").append(requirements.get(i).kind()).append("] ")
                    .append(requirements.get(i).expectation()).append('\n');
        }
        return text.append("\nThe answer to grade:\n---\n").append(answer).append("\n---").toString();
    }

    /**
     * Reads the judge's JSON, and refuses to guess.
     *
     * <p>A reply that does not parse, or that grades a different number of requirements than were
     * asked, is <b>inconclusive</b> rather than partially believed: filling the gaps with "met" would
     * pass a scenario nobody scored, and filling them with "not met" would fail an agent for the
     * judge's mistake. Both are the false verdict §7 exists to prevent, in opposite directions.
     */
    private JudgeVerdict read(String reply, List<Requirement> requirements) {
        JsonNode findings;
        try {
            findings = json.readTree(extractJson(reply)).path("findings");
        } catch (IOException e) {
            return JudgeVerdict.inconclusive("the judge did not answer JSON: " + abbreviate(reply));
        }
        if (!findings.isArray() || findings.size() != requirements.size()) {
            return JudgeVerdict.inconclusive("the judge graded " + (findings.isArray() ? findings.size() : 0)
                    + " requirement(s) where " + requirements.size() + " were asked");
        }

        // By index rather than by order of arrival: a judge that renumbered its findings would
        // otherwise have its grades silently attached to the wrong requirements.
        Map<Integer, JsonNode> byIndex = new LinkedHashMap<>();
        for (JsonNode finding : findings) {
            JsonNode index = finding.path("index");
            if (!index.isInt() || index.asInt() < 0 || index.asInt() >= requirements.size()) {
                return JudgeVerdict.inconclusive("a finding carries no usable index: " + finding);
            }
            byIndex.put(index.asInt(), finding);
        }
        if (byIndex.size() != requirements.size()) {
            return JudgeVerdict.inconclusive("the judge graded the same requirement twice");
        }

        List<JudgeVerdict.Finding> graded = new ArrayList<>();
        for (int i = 0; i < requirements.size(); i++) {
            JsonNode finding = byIndex.get(i);
            graded.add(new JudgeVerdict.Finding(
                    requirements.get(i).kind(),
                    requirements.get(i).expectation(),
                    finding.path("met").asBoolean(false),
                    finding.path("why").asText("(the judge gave no reason)")));
        }
        return new JudgeVerdict(graded, null);
    }

    /** A model asked for JSON often wraps it in a fence or a sentence; the object itself is taken. */
    private static String extractJson(String reply) {
        int open = reply.indexOf('{');
        int close = reply.lastIndexOf('}');
        return open >= 0 && close > open ? reply.substring(open, close + 1) : reply;
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
