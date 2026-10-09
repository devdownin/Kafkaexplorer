// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.List;

/**
 * Verdict 2's result: one finding per grid entry, plus the judge's own failure mode.
 *
 * @param findings      what the judge decided, one per requirement, in the grid's order
 * @param notJudged     why the grid could not be scored at all — an unparseable answer, a provider
 *                      error — or null when it was. It is a record component rather than an
 *                      exception because <b>a scenario that could not be judged is not a scenario
 *                      that passed</b>, and the report has to be able to say which of the two it
 *                      is. Swallowing this is how a suite reports green over an unrun grid
 * @param inconclusive  true when the fault is the judge's — a provider error, a rate limit, a reply
 *                      that is not the JSON asked for. The agent's answer was never graded, so
 *                      neither "passed" nor "failed" is true of it; a free, saturated judge turned
 *                      thirteen of twenty-one scenarios into failures that way. False for an agent
 *                      that produced no answer, which is the agent's failure
 */
record JudgeVerdict(List<Finding> findings, String notJudged, boolean inconclusive) {

    /**
     * @param requirement which half of the grid this came from, e.g. {@code mustQualify}
     * @param expectation the grid entry verbatim, so a failure quotes what was asked
     * @param met         the judge's decision
     * @param why         the judge's reason, kept whatever the decision. §7: a failure whose
     *                    material cannot be re-read is a failure people end up disabling
     */
    record Finding(String requirement, String expectation, boolean met, String why) {
    }

    JudgeVerdict {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    JudgeVerdict(List<Finding> findings, String notJudged) {
        this(findings, notJudged, false);
    }

    /** The agent left nothing to grade: its failure, not the judge's. */
    static JudgeVerdict notJudged(String reason) {
        return new JudgeVerdict(List.of(), reason, false);
    }

    /** The judge could not grade: the answer is neither passed nor failed. */
    static JudgeVerdict inconclusive(String reason) {
        return new JudgeVerdict(List.of(), reason, true);
    }

    /** Nothing to score: a trace-only scenario, which passes verdict 2 by having no verdict 2. */
    static JudgeVerdict nothingToJudge() {
        return new JudgeVerdict(List.of(), null);
    }

    boolean passed() {
        return notJudged == null && findings.stream().allMatch(Finding::met);
    }

    List<String> failures() {
        if (notJudged != null) {
            return List.of((inconclusive ? "the judge could not score the grid: " : "the grid could not be scored: ")
                    + notJudged);
        }
        return findings.stream()
                .filter(finding -> !finding.met())
                .map(finding -> finding.requirement() + " — \"" + finding.expectation()
                        + "\" — the judge said: " + finding.why())
                .toList();
    }
}
