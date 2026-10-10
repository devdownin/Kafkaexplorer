// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A wall-clock ceiling on one piece of work, enforced from outside it.
 *
 * <p>The budget {@link AgentRunner} checks is read between two tool calls, so it cannot end a call
 * that never returns. Per-request timeouts close that on every client this harness owns; this is the
 * backstop for the one that is missed — the first full run lost 3 h 39 min, and every report it had
 * not yet printed, to a single call nothing bounded. The work is interrupted, not abandoned: a thread
 * blocked in an HTTP send ends on the interrupt.
 */
final class HardCap {

    /** The ceiling was reached; what it was and what was running travel in the message. */
    static final class Exceeded extends RuntimeException {
        Exceeded(String message) {
            super(message);
        }
    }

    private HardCap() {
    }

    static <T> T within(Duration ceiling, String what, Supplier<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hard-cap-" + what);
            thread.setDaemon(true);
            return thread;
        });
        Future<T> future = executor.submit(work::get);
        try {
            return future.get(ceiling.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exceeded(what + " was still running after " + ceiling.toSeconds()
                    + " s, the hard ceiling on one scenario; it was interrupted");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + what, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }
}
