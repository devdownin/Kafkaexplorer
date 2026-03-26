// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import jakarta.annotation.PreDestroy;
import org.apache.flink.table.api.TableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Service
public class FlinkRuntimeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FlinkRuntimeCoordinator.class);

    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock(true);
    private final ExecutorService mutationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("flink-runtime-mutation-" + t.getId());
        t.setDaemon(true);
        return t;
    });
    private final ClassLoader flinkClassLoader;

    public FlinkRuntimeCoordinator(TableEnvironment tableEnv) {
        this.flinkClassLoader = tableEnv.getClass().getClassLoader();
    }

    public <T> T runRead(String operationName, Supplier<T> action) {
        return runWithLock("READ", operationName, runtimeLock.readLock(), action, System.nanoTime());
    }

    public void runRead(String operationName, Runnable action) {
        runRead(operationName, () -> {
            action.run();
            return null;
        });
    }

    public <T> T runMutation(String operationName, Supplier<T> action) {
        long queuedAt = System.nanoTime();
        Future<T> future = mutationExecutor.submit(
            () -> runWithLock("MUTATION", operationName, runtimeLock.writeLock(), action, queuedAt)
        );
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Flink runtime mutation '" + operationName + "'", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Flink runtime mutation failed for '" + operationName + "'", cause);
        }
    }

    public void runMutation(String operationName, Runnable action) {
        runMutation(operationName, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runWithLock(String kind, String operationName, Lock lock, Supplier<T> action, long queuedAt) {
        long waitedNs = 0L;
        lock.lock();
        long startedAt = System.nanoTime();
        try {
            waitedNs = startedAt - queuedAt;
            if (waitedNs > 0) {
                log.debug("Flink runtime {} operation '{}' started after waiting {} ms", kind, operationName, TimeUnit.NANOSECONDS.toMillis(waitedNs));
            }
            return withFlinkClassLoader(action);
        } finally {
            long durationNs = System.nanoTime() - startedAt;
            lock.unlock();
            log.debug(
                "Flink runtime {} operation '{}' completed in {} ms (waited {} ms)",
                kind,
                operationName,
                TimeUnit.NANOSECONDS.toMillis(durationNs),
                TimeUnit.NANOSECONDS.toMillis(waitedNs)
            );
        }
    }

    private <T> T withFlinkClassLoader(Supplier<T> action) {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(flinkClassLoader != null ? flinkClassLoader : saved);
        try {
            return action.get();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    @PreDestroy
    public void shutdown() {
        mutationExecutor.shutdown();
        try {
            if (!mutationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mutationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mutationExecutor.shutdownNow();
        }
    }
}
