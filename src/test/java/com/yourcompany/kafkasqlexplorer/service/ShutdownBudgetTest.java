// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownBudgetTest {

    @BeforeEach
    void resetClock() {
        ShutdownBudget.reset();
    }

    @Test
    void anIdlePoolTerminatesWithoutConsumingTheBudget() {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        long start = System.currentTimeMillis();
        ShutdownBudget.shutdown(executor);

        assertTrue(executor.isTerminated());
        assertTrue(System.currentTimeMillis() - start < 1_000,
            "an idle pool must not wait for its slice");
    }

    @Test
    void poolsShareOneDeadlineInsteadOfEachAwaitingItsOwn() throws Exception {
        // Two pools each holding a task that never finishes, and a budget already spent.
        // The regression this pins: every service used to await its own five seconds, so
        // the cost of shutting down grew by five seconds per pool added to the codebase.
        CountDownLatch blocked = new CountDownLatch(2);
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        for (ExecutorService executor : new ExecutorService[]{first, second}) {
            executor.submit(() -> {
                blocked.countDown();
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(blocked.await(5, TimeUnit.SECONDS), "tasks should be running");
        ShutdownBudget.deadlineIn(0);

        long start = System.currentTimeMillis();
        ShutdownBudget.shutdown(first);
        ShutdownBudget.shutdown(second);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(first.isShutdown() && second.isShutdown());
        // Only the per-pool floor each, never a fresh five seconds.
        assertTrue(elapsed < 4 * ShutdownBudget.MIN_SLICE_MS,
            "two blocked pools took " + elapsed + "ms past the deadline; the floor is "
                + ShutdownBudget.MIN_SLICE_MS + "ms each");
    }

    @Test
    void theRemainingBudgetIsOneClockShrinkingAcrossCalls() throws Exception {
        long firstRead = ShutdownBudget.remainingMs();
        assertTrue(firstRead <= ShutdownBudget.TOTAL_MS,
            "the first read starts the clock, it cannot exceed the total");

        Thread.sleep(50);

        assertTrue(ShutdownBudget.remainingMs() < firstRead,
            "a second pool must see less than the first, not a fresh budget");
    }

    @Test
    void eachPoolKeepsAFloorOnceTheBudgetIsSpent() {
        ShutdownBudget.deadlineIn(-1_000);

        assertEquals(ShutdownBudget.MIN_SLICE_MS, ShutdownBudget.remainingMs(),
            "the last pool destroyed still gets its floor rather than being interrupted "
                + "the instant the budget runs out");
    }
}
