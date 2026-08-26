// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * What an analysis was actually able to look at.
 *
 * <p>A Process Mining answer is a diagram and a list of anomalies, and neither of them can say what
 * it rests on. A snapshot over eight topics where three were empty, one was misspelled and two fell
 * past the prompt's character budget rendered exactly like one where all eight were read and
 * analysed — the omissions were written into the prompt, for the model, and nowhere on screen. The
 * model's silence about a topic then reads as a finding about that topic rather than as the absence
 * of a question, which is the strongest way this feature can mislead.
 *
 * <p>So the run states its own scope, on the same rule as every other bounded read here (the topic
 * search's {@code stopReason}, Stream Flow's coverage, the activity sparkline's
 * {@code coveredFromMs}): what was read, what of it reached the model, and what could not be
 * measured — never a zero standing in for a measurement nobody took.
 *
 * <p>{@code messagesMeasured} and {@code messagesDetailed} are two answers because the prompt now
 * carries two kinds of evidence: a process measured over every record read, and a few whole case
 * traces inlined as worked examples. Reporting only the second — as a single {@code
 * messagesAnalysed} once did — said "6 of 3,000" about a run that had measured all three thousand.
 * See {@link TopicCoverage}. A run where {@code messagesMeasured} is zero built no event log, and
 * the per-topic sampling ran instead; that is the signal a reader uses to tell the two apart.
 *
 * @param promptChars      the size of the prompt actually sent, against
 * @param promptCharBudget {@code process-mining.prompt-char-budget}. Two numbers rather than a
 *                         flag: whether the budget bound the run is a question of how close they
 *                         are, and the per-topic breakdown says what it cost
 * @param readTruncated    the read stopped on its own wall-clock or silence budget, so every count
 *                         here is a floor
 * @param readError        the read failed and the analysis ran on what had arrived before it did
 * @param warnings         anything else worth stating about this run's scope — an unresolvable
 *                         field mapping, for instance, which changes what correlation could mean
 */
public record ProcessMiningCoverage(
    List<TopicCoverage> topics,
    int messagesRead,
    int messagesMeasured,
    int messagesDetailed,
    int promptChars,
    int promptCharBudget,
    boolean readTruncated,
    String readError,
    List<String> warnings
) {
    public ProcessMiningCoverage {
        topics = topics == null ? List.of() : List.copyOf(topics);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Totals are derived from the per-topic rows, so the header can never disagree with the table. */
    public static ProcessMiningCoverage of(List<TopicCoverage> topics, int promptChars,
                                           int promptCharBudget, boolean readTruncated,
                                           String readError, List<String> warnings) {
        int read = topics.stream().mapToInt(TopicCoverage::messagesRead).sum();
        int measured = topics.stream().mapToInt(TopicCoverage::messagesMeasured).sum();
        int detailed = topics.stream().mapToInt(TopicCoverage::messagesDetailed).sum();
        return new ProcessMiningCoverage(topics, read, measured, detailed, promptChars,
            promptCharBudget, readTruncated, readError, warnings);
    }

    /** Returns a copy carrying one more scope note. */
    public ProcessMiningCoverage withWarning(String warning) {
        if (warning == null || warning.isBlank()) {
            return this;
        }
        List<String> merged = new ArrayList<>(warnings);
        merged.add(warning);
        return new ProcessMiningCoverage(topics, messagesRead, messagesMeasured, messagesDetailed,
            promptChars, promptCharBudget, readTruncated, readError, merged);
    }
}
