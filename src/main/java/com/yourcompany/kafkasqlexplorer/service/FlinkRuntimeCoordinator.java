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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Default ceiling on how long a caller waits to get onto the Flink runtime.
     *
     * <p>There was none. {@code lock.lock()} and {@code future.get()} both wait forever, so any
     * operation that stopped making progress — a job submission against a wedged MiniCluster, a
     * DDL against an unreachable broker — took every later query down with it, and each one waited
     * for ever too. A synchronous query bounded only its <em>row fetch</em>, which is the one step
     * that could never be the cause: the caller had already gone through validation, table
     * registration and job submission, none of which had a deadline. The visible symptom is a Run
     * button that spins with no result and no error, which is exactly what a status bar must never
     * do.
     */
    static final long DEFAULT_WAIT_MS = 30_000;

    /** Past this, a wait is worth a WARN naming what is holding the runtime. */
    private static final long SLOW_WAIT_WARN_MS = 2_000;

    /** Whoever currently holds (or last held) the runtime — the only thing that can explain a wait. */
    private final AtomicReference<Holder> holder = new AtomicReference<>();

    private record Holder(String kind, String operationName, String threadName, long startedAtNanos) {
        long heldMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        }

        @Override
        public String toString() {
            return String.format("%s '%s' (thread %s), running for %d ms", kind, operationName, threadName, heldMs());
        }
    }

    /**
     * The runtime could not be entered in time.
     *
     * <p>A {@link RuntimeException} so it travels the existing error paths — {@code
     * SqlErrorClassifier} treats it as an engine failure, which is what it is: the statement is
     * fine, the engine was not free to run it.
     */
    public static class FlinkRuntimeBusyException extends RuntimeException {
        public FlinkRuntimeBusyException(String message) {
            super(message);
        }
    }

    public FlinkRuntimeCoordinator(TableEnvironment tableEnv) {
        this.flinkClassLoader = tableEnv.getClass().getClassLoader();
    }

    public <T> T runRead(String operationName, Supplier<T> action) {
        return runRead(operationName, action, DEFAULT_WAIT_MS);
    }

    /** @param waitMs how long to wait for the runtime before giving up with {@link FlinkRuntimeBusyException}. */
    public <T> T runRead(String operationName, Supplier<T> action, long waitMs) {
        return runWithLock("READ", operationName, runtimeLock.readLock(), action, System.nanoTime(), waitMs);
    }

    public void runRead(String operationName, Runnable action) {
        runRead(operationName, () -> {
            action.run();
            return null;
        });
    }

    public <T> T runMutation(String operationName, Supplier<T> action) {
        return runMutation(operationName, action, DEFAULT_WAIT_MS);
    }

    /**
     * @param waitMs budget covering both the queue behind other mutations and the operation itself.
     *
     * <p>On expiry the caller is released and the task is cancelled. Cancelling cannot pull a
     * thread out of a Flink call that is already running, so the mutation thread may stay busy —
     * later mutations then fail the same way, with a message naming what is holding it, instead of
     * every one of them hanging silently.
     */
    public <T> T runMutation(String operationName, Supplier<T> action, long waitMs) {
        long queuedAt = System.nanoTime();
        Future<T> future = mutationExecutor.submit(
            () -> runWithLock("MUTATION", operationName, runtimeLock.writeLock(), action, queuedAt, waitMs)
        );
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw busy("MUTATION", operationName, waitMs);
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

    private <T> T runWithLock(String kind, String operationName, Lock lock, Supplier<T> action,
                              long queuedAt, long waitMs) {
        // Le temps déjà passé en file compte dans le budget : sans ça, une mutation qui a attendu
        // son tour vingt secondes repartirait pour un budget complet, et le plafond annoncé à
        // l'appelant ne voudrait plus rien dire.
        long remainingMs = waitMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt);
        boolean acquired;
        try {
            acquired = lock.tryLock(Math.max(0, remainingMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Flink runtime ('" + operationName + "')", e);
        }
        if (!acquired) {
            throw busy(kind, operationName, waitMs);
        }

        long startedAt = System.nanoTime();
        long waitedNs = startedAt - queuedAt;
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(waitedNs);
        // Une attente longue n'était tracée qu'en DEBUG, donc invisible sur une installation
        // ordinaire : c'est précisément l'information qui explique une requête qui ne revient pas.
        if (waitedMs >= SLOW_WAIT_WARN_MS) {
            log.warn("Flink runtime {} operation '{}' waited {} ms to start — the runtime is serialised, "
                + "and the previous holder was {}", kind, operationName, waitedMs, describeHolder());
        } else if (waitedNs > 0) {
            log.debug("Flink runtime {} operation '{}' started after waiting {} ms", kind, operationName, waitedMs);
        }

        holder.set(new Holder(kind, operationName, Thread.currentThread().getName(), startedAt));
        try {
            return withFlinkClassLoader(action);
        } finally {
            long durationNs = System.nanoTime() - startedAt;
            holder.set(null);
            lock.unlock();
            log.debug(
                "Flink runtime {} operation '{}' completed in {} ms (waited {} ms)",
                kind,
                operationName,
                TimeUnit.NANOSECONDS.toMillis(durationNs),
                waitedMs
            );
        }
    }

    /** Qui tient le runtime, ou une mention explicite quand personne ne le tient. */
    private String describeHolder() {
        Holder current = holder.get();
        return current == null ? "nobody (it was released in the meantime)" : current.toString();
    }

    private FlinkRuntimeBusyException busy(String kind, String operationName, long waitMs) {
        String message = String.format(
            "The Flink runtime was busy: %s operation '%s' gave up after %d ms. Currently held by %s.",
            kind, operationName, waitMs, describeHolder());
        log.warn(message);
        return new FlinkRuntimeBusyException(message);
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
