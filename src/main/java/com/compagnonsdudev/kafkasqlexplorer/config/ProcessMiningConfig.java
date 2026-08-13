// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tuning for the Process Mining ingestion path (live consumer + payload digestion +
 * prompt budgeting). The defaults target "complex" payloads: JSON documents around
 * 1 MB with ~10 levels of nesting, which cannot be buffered or prompted verbatim.
 */
@Configuration
@ConfigurationProperties(prefix = "process-mining")
public class ProcessMiningConfig {

    /** Records returned by a single poll. With MB-sized payloads this bounds the per-tick work. */
    private int maxPollRecords = 100;
    /** {@code max.partition.fetch.bytes}: must exceed the largest expected record or fetches degrade to one record per round trip. */
    private int maxPartitionFetchBytes = 8 * 1024 * 1024;
    /** {@code fetch.max.bytes}: upper bound on the bytes a single poll can pull across all partitions. */
    private int fetchMaxBytes = 32 * 1024 * 1024;

    /** Payload bytes actually parsed. Beyond this only the prefix is scanned and the digest is flagged truncated. */
    private int maxPayloadBytes = 4 * 1024 * 1024;
    /** Nesting levels walked. Deeper subtrees are skipped (not materialized) and the digest is flagged truncated. */
    private int maxDepth = 12;
    /** Hard cap on the parser events spent on one payload — protects against pathological documents. */
    private int maxParseEvents = 400_000;
    /** Array elements descended into. The remainder is skipped; the true element count is still reported. */
    private int arraySampleSize = 2;
    /** Distinct leaf paths kept per payload shape. */
    private int maxShapePaths = 250;
    /** Leaf paths shown per shape in the prompt. */
    private int maxShapePathsInPrompt = 80;
    /** Salient (non-mapped) scalar values kept per message. */
    private int maxSampleFields = 40;
    /** Scalar values longer than this are truncated in the digest. */
    private int maxValueChars = 160;
    /** Prefix kept for payloads that are not structured (plain text / unparseable). */
    private int previewChars = 400;
    /** Distinct payload shapes remembered across windows (LRU). */
    private int shapeCacheSize = 200;
    /** Estimated heap the buffered digests of one session may occupy before the oldest are dropped. */
    private long maxWindowDigestBytes = 32L * 1024 * 1024;

    /** Messages inlined per topic in an LLM prompt (evenly sampled when the window holds more). */
    private int maxMessagesPerTopicInPrompt = 60;
    /** Total characters the "messages" part of a prompt may occupy. ~4 chars/token. */
    private int promptCharBudget = 120_000;

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public int getMaxPartitionFetchBytes() {
        return maxPartitionFetchBytes;
    }

    public void setMaxPartitionFetchBytes(int maxPartitionFetchBytes) {
        this.maxPartitionFetchBytes = maxPartitionFetchBytes;
    }

    public int getFetchMaxBytes() {
        return fetchMaxBytes;
    }

    public void setFetchMaxBytes(int fetchMaxBytes) {
        this.fetchMaxBytes = fetchMaxBytes;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxParseEvents() {
        return maxParseEvents;
    }

    public void setMaxParseEvents(int maxParseEvents) {
        this.maxParseEvents = maxParseEvents;
    }

    public int getArraySampleSize() {
        return arraySampleSize;
    }

    public void setArraySampleSize(int arraySampleSize) {
        this.arraySampleSize = arraySampleSize;
    }

    public int getMaxShapePaths() {
        return maxShapePaths;
    }

    public void setMaxShapePaths(int maxShapePaths) {
        this.maxShapePaths = maxShapePaths;
    }

    public int getMaxShapePathsInPrompt() {
        return maxShapePathsInPrompt;
    }

    public void setMaxShapePathsInPrompt(int maxShapePathsInPrompt) {
        this.maxShapePathsInPrompt = maxShapePathsInPrompt;
    }

    public int getMaxSampleFields() {
        return maxSampleFields;
    }

    public void setMaxSampleFields(int maxSampleFields) {
        this.maxSampleFields = maxSampleFields;
    }

    public int getMaxValueChars() {
        return maxValueChars;
    }

    public void setMaxValueChars(int maxValueChars) {
        this.maxValueChars = maxValueChars;
    }

    public int getPreviewChars() {
        return previewChars;
    }

    public void setPreviewChars(int previewChars) {
        this.previewChars = previewChars;
    }

    public int getShapeCacheSize() {
        return shapeCacheSize;
    }

    public void setShapeCacheSize(int shapeCacheSize) {
        this.shapeCacheSize = shapeCacheSize;
    }

    public long getMaxWindowDigestBytes() {
        return maxWindowDigestBytes;
    }

    public void setMaxWindowDigestBytes(long maxWindowDigestBytes) {
        this.maxWindowDigestBytes = maxWindowDigestBytes;
    }

    public int getMaxMessagesPerTopicInPrompt() {
        return maxMessagesPerTopicInPrompt;
    }

    public void setMaxMessagesPerTopicInPrompt(int maxMessagesPerTopicInPrompt) {
        this.maxMessagesPerTopicInPrompt = maxMessagesPerTopicInPrompt;
    }

    public int getPromptCharBudget() {
        return promptCharBudget;
    }

    public void setPromptCharBudget(int promptCharBudget) {
        this.promptCharBudget = promptCharBudget;
    }
}
