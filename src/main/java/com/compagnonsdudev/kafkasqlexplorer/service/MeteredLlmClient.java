// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * Every call to a model, counted and timed under the consumer that made it.
 *
 * <p>Only the assistant counted its calls ({@code explorer_assistant_requests_total}, which counts
 * its <em>outcomes</em> — validated, repaired — and stays). Process Mining counted nothing, so the
 * two uses of one model could not be compared in production: how often each calls it, how long it
 * takes, how often it fails. {@code explorer_llm_requests_total{consumer, outcome}} and
 * {@code explorer_llm_request_duration_seconds{consumer}} answer that for both, at the one place
 * every call passes through. A Settings test counts under the consumer whose model it tests.
 */
final class MeteredLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final String consumer;
    private final MeterRegistry registry;

    MeteredLlmClient(LlmClient delegate, String consumer, MeterRegistry registry) {
        this.delegate = delegate;
        this.consumer = consumer;
        this.registry = registry;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return measure(() -> delegate.generate(systemPrompt, userPrompt));
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        return measure(() -> delegate.generateWithMeta(systemPrompt, userPrompt));
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt, LlmOutputSchema schema) {
        return measure(() -> delegate.generateWithMeta(systemPrompt, userPrompt, schema));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private <T> T measure(Supplier<T> call) {
        long started = System.nanoTime();
        String outcome = "error";
        try {
            T result = call.get();
            outcome = "ok";
            return result;
        } finally {
            Timer.builder("explorer_llm_request_duration")
                .description("Calls to a language model, by consumer")
                .tag("consumer", consumer)
                .register(registry)
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            Counter.builder("explorer_llm_requests")
                .description("Calls to a language model, by consumer and outcome")
                .tags("consumer", consumer, "outcome", outcome)
                .register(registry)
                .increment();
        }
    }
}
