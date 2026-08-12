// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Nothing may wait on the Flink runtime without a deadline.
 *
 * <p>It used to: {@code lock.lock()} and {@code future.get()} both wait for ever. A synchronous
 * query bounded only its row fetch — the step that comes <em>after</em> validation, table
 * registration and job submission, none of which had a deadline — so an operation that stopped
 * making progress took every later query with it, and the UI spun on "Running…" with no result and
 * no error. These tests are written with their own {@code @Timeout}: before the fix they do not
 * fail, they hang, which is the whole point.
 */
class FlinkRuntimeCoordinatorTest {

    private FlinkRuntimeCoordinator newCoordinator() {
        return new FlinkRuntimeCoordinator(Mockito.mock(TableEnvironment.class));
    }

    /*
     * Les deux tests d'origine : la sérialisation des mutations et le fait qu'une lecture
     * attende la mutation en cours. Toujours vrais — la borne ajoutée plus bas ne change que
     * ce qui se passe quand l'attente ne finit pas.
     */
    @Test
    void mutationsAreExecutedSequentially() throws Exception {
        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(mock(TableEnvironment.class));
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
            coordinator.runMutation("first-mutation", () -> {
                events.add("first-start");
                firstStarted.countDown();
                await(releaseFirst);
                events.add("first-end");
            })
        );

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
            coordinator.runMutation("second-mutation", () -> {
                events.add("second-start");
                secondStarted.countDown();
            })
        );

        Thread.sleep(150);
        assertEquals(List.of("first-start"), events);
        assertEquals(1L, secondStarted.getCount());

        releaseFirst.countDown();

        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);

        assertEquals(List.of("first-start", "first-end", "second-start"), events);
    }

    @Test
    void readsWaitForActiveMutation() throws Exception {
        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(mock(TableEnvironment.class));
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);

        CompletableFuture<Void> mutation = CompletableFuture.runAsync(() ->
            coordinator.runMutation("blocking-mutation", () -> {
                mutationStarted.countDown();
                await(releaseMutation);
            })
        );

        assertTrue(mutationStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<String> read = CompletableFuture.supplyAsync(() ->
            coordinator.runRead("blocked-read", () -> "ok")
        );

        Thread.sleep(150);
        assertFalse(read.isDone());

        releaseMutation.countDown();

        assertEquals("ok", read.get(2, TimeUnit.SECONDS));
        mutation.get(2, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void readsAndMutationsRunNormallyWhenNothingIsHeld() {
        FlinkRuntimeCoordinator coordinator = newCoordinator();
        assertEquals("read", coordinator.runRead("op", () -> "read"));
        assertEquals("write", coordinator.runMutation("op", () -> "write"));
    }

    @Test
    @Timeout(10)
    void aReadGivesUpInsteadOfWaitingForeverOnAHeldRuntime() throws Exception {
        FlinkRuntimeCoordinator coordinator = newCoordinator();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> coordinator.runMutation("long-running-ddl", () -> {
                holding.countDown();
                await(release);
                return null;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS), "the mutation should have taken the runtime");

            long start = System.currentTimeMillis();
            FlinkRuntimeCoordinator.FlinkRuntimeBusyException failure = assertThrows(
                FlinkRuntimeCoordinator.FlinkRuntimeBusyException.class,
                () -> coordinator.runRead("select", () -> "rows", 300));
            long waited = System.currentTimeMillis() - start;

            assertTrue(waited < 5_000, "the read should give up quickly, waited " + waited + " ms");
            // Le message doit nommer ce qui tient le runtime : sans ça, l'erreur remplace un
            // symptôme muet par un symptôme incompréhensible.
            assertTrue(failure.getMessage().contains("long-running-ddl"),
                "the message should name the holder: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("select"),
                "the message should name the operation that gave up: " + failure.getMessage());
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void aMutationGivesUpInsteadOfQueueingForeverBehindAnother() throws Exception {
        FlinkRuntimeCoordinator coordinator = newCoordinator();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> coordinator.runMutation("stuck-job-submission", () -> {
                holding.countDown();
                await(release);
                return null;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));

            FlinkRuntimeCoordinator.FlinkRuntimeBusyException failure = assertThrows(
                FlinkRuntimeCoordinator.FlinkRuntimeBusyException.class,
                () -> coordinator.runMutation("register-table", () -> "ddl", 300));
            assertTrue(failure.getMessage().contains("stuck-job-submission"), failure.getMessage());
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    /** A caller released by the deadline must not have its action run behind its back later. */
    @Test
    @Timeout(10)
    void aTimedOutMutationIsCancelledRatherThanRunLater() throws Exception {
        FlinkRuntimeCoordinator coordinator = newCoordinator();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ranLate = new AtomicBoolean(false);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> coordinator.runMutation("holder", () -> {
                holding.countDown();
                await(release);
                return null;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));

            assertThrows(FlinkRuntimeCoordinator.FlinkRuntimeBusyException.class,
                () -> coordinator.runMutation("abandoned", () -> {
                    ranLate.set(true);
                    return null;
                }, 300));

            release.countDown();
            Thread.sleep(300);
            assertFalse(ranLate.get(), "the abandoned mutation must not run after its caller gave up");
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    /** The default is a ceiling, not the absence of one. */
    @Test
    void theDefaultWaitIsBounded() {
        assertTrue(FlinkRuntimeCoordinator.DEFAULT_WAIT_MS > 0);
        assertTrue(FlinkRuntimeCoordinator.DEFAULT_WAIT_MS <= 60_000);
    }

    /** A busy runtime is an engine fault, so a SELECT keeps its fallback to the direct reader. */
    @Test
    void aBusyRuntimeIsClassifiedAsAnEngineFailure() {
        String message = new FlinkRuntimeCoordinator.FlinkRuntimeBusyException(
            "The Flink runtime was busy: MUTATION operation 'execute-sql-select' gave up after 10000 ms.")
            .getMessage();
        assertFalse(SqlErrorClassifier.classify(message).isUserError());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
