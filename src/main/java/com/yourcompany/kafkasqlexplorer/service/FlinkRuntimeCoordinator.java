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
    private volatile boolean metadataProviderReady = false;
    private volatile boolean metadataProviderWarned = false;

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
            ensureFlinkMetadataProvider();
            return action.get();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    /**
     * Flink 2.x does not populate Calcite's {@code RelMetadataQueryBase.THREAD_PROVIDERS}
     * ThreadLocal before the VolcanoPlanner runs cost-based optimization in this embedded
     * setup, so {@code RelMetadataQuery.getNonCumulativeCost(...)} dereferences a null
     * metadata-handler provider and throws {@code NullPointerException("metadataHandlerProvider")}
     * on every optimize() — SELECT and EXPLAIN alike. We pre-seed the ThreadLocal with Flink's
     * own provider (wrapped in a Janino provider, exactly as Flink's PlannerBase.optimize does)
     * on the current thread, under the Flink context classloader so Janino compiles the metadata
     * handlers against the right classes. Best-effort: any failure is swallowed and the query
     * simply falls back to its existing behaviour (direct Kafka reader for SELECT).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ensureFlinkMetadataProvider() {
        try {
            java.lang.reflect.Field threadProvidersField =
                Class.forName("org.apache.calcite.rel.metadata.RelMetadataQueryBase")
                    .getField("THREAD_PROVIDERS");
            ThreadLocal threadProviders = (ThreadLocal) threadProvidersField.get(null);
            if (threadProviders.get() != null) {
                return; // already set on this thread (by us or by Flink)
            }

            Class<?> relMetadataProvider = Class.forName("org.apache.calcite.rel.metadata.RelMetadataProvider");
            Class<?> flinkProviderClass =
                Class.forName("org.apache.flink.table.planner.plan.metadata.FlinkDefaultRelMetadataProvider");
            // Name-agnostic lookup of Flink's provider instance: any public static member
            // (field, no-arg method, or Scala MODULE$ accessor) that yields a RelMetadataProvider.
            Object flinkProvider = resolveRelMetadataProvider(flinkProviderClass, relMetadataProvider);
            if (flinkProvider == null) {
                throw new IllegalStateException("No RelMetadataProvider accessor on "
                    + flinkProviderClass.getName()
                    + " — static fields=" + java.util.Arrays.toString(flinkProviderClass.getFields())
                    + " static methods=" + java.util.Arrays.toString(flinkProviderClass.getMethods()));
            }

            Object janino = Class.forName("org.apache.calcite.rel.metadata.JaninoRelMetadataProvider")
                .getMethod("of", relMetadataProvider)
                .invoke(null, flinkProvider);

            threadProviders.set(janino);
            if (!metadataProviderReady) {
                metadataProviderReady = true;
                log.info("Pre-populated Flink RelMetadata provider ThreadLocal for the planner");
            }
        } catch (Throwable t) {
            if (!metadataProviderWarned) {
                metadataProviderWarned = true;
                log.warn("Could not pre-populate Flink RelMetadata provider ThreadLocal "
                    + "(SELECT will use the direct Kafka reader; EXPLAIN unavailable): {}", t.toString());
            }
        }
    }

    /**
     * Finds the {@code RelMetadataProvider} instance exposed by {@code holder}, regardless of how
     * it is named: a public static field, a public static no-arg method, or a Scala
     * {@code MODULE$} object with such a method. Returns {@code null} if none is found.
     */
    private Object resolveRelMetadataProvider(Class<?> holder, Class<?> relMetadataProvider) throws Exception {
        for (java.lang.reflect.Field f : holder.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                && relMetadataProvider.isAssignableFrom(f.getType())) {
                return f.get(null);
            }
        }
        for (java.lang.reflect.Method m : holder.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                && m.getParameterCount() == 0
                && relMetadataProvider.isAssignableFrom(m.getReturnType())) {
                return m.invoke(null);
            }
        }
        try {
            Object module = holder.getField("MODULE$").get(null);
            for (java.lang.reflect.Method m : module.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && relMetadataProvider.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(module);
                }
            }
        } catch (NoSuchFieldException notScala) {
            // not a Scala object — ignore
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        ShutdownBudget.shutdown(mutationExecutor);
    }
}
