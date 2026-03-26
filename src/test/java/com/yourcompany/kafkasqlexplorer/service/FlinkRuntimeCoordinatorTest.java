package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FlinkRuntimeCoordinatorTest {

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

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test thread interrupted", e);
        }
    }
}
