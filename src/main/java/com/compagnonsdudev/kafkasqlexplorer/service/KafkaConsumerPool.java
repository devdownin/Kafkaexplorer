// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

/**
 * Lends out byte-array consumers instead of building one per read.
 *
 * <p>Constructing a {@code KafkaConsumer} is a TCP connect, an ApiVersions exchange and a metadata
 * fetch — and, on a cluster with TLS or SASL, a handshake and an authentication round trip on top.
 * That is paid once per read, and the cluster audit reads once per topic, so on a secured cluster
 * of a few hundred topics it is a measurable share of a run. A pool turns "one client per topic"
 * into "one client per worker thread".
 *
 * <p><b>It is off by default</b> ({@code explorer.consumer-pool-size: 0}), and that is a statement
 * about evidence rather than about the code. The saving is real but its size depends entirely on
 * the deployment — on a plaintext broker a construction costs milliseconds, on a TLS+SASL one it
 * costs a handshake — and nothing in this repository has measured it against a real cluster. What
 * a mistake here costs, by contrast, is not a slow read but a <em>wrong</em> one: a consumer handed
 * out with a previous read's assignment, paused partitions or buffered records still on it answers
 * a question nobody asked, and this codebase has been caught three times by read faults that look
 * identical to an empty topic from outside. So it ships as a knob an operator can turn on once
 * they can measure the difference, not as a new default nobody has.
 *
 * <p>Three rules make a lease safe to hand out.
 *
 * <ul>
 *   <li><b>A lease is exclusive.</b> A consumer is removed from the pool while it is held and put
 *       back only on {@link Lease#close()}, so it is never in two hands — which is what a
 *       {@code KafkaConsumer} requires, being single-threaded, and it is why this is a pool of
 *       leases rather than a shared client.</li>
 *   <li><b>A returned consumer is reset before anyone else sees it.</b> {@code unsubscribe()}
 *       drops the assignment, and with it the paused set and the fetch buffers — both of which the
 *       record readers now leave behind deliberately, since they pause a partition they have
 *       finished with and never resume it. A reset that throws means the client is in a state
 *       nobody can describe, so it is closed rather than pooled.</li>
 *   <li><b>A read that failed does not return its consumer.</b> {@link Lease#discard()} says the
 *       borrower does not vouch for it — an exception may have left an in-flight request, a
 *       wakeup pending, or nothing at all, and telling those apart from here is guesswork.</li>
 * </ul>
 *
 * <p>Group ids: every pooled consumer keeps the one it was built with, where an unpooled read
 * mints a fresh one. That changes nothing on the cluster — these consumers assign explicitly and
 * never commit, so no group is ever registered for them (see {@code ExplorerConsumerGroups}) — and
 * it means fewer distinct ids in the logs rather than more.
 */
final class KafkaConsumerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerPool.class);

    /** Bounded so a pooled client that will not close cannot hold shutdown open. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Seam for tests: a pool with no broker to build against. */
    interface ConsumerFactory {
        Consumer<byte[], byte[]> create(Properties props);
    }

    private final int maxIdle;
    private final ConsumerFactory factory;
    private final Deque<Consumer<byte[], byte[]>> idle = new ArrayDeque<>();
    private boolean closed;

    KafkaConsumerPool(int maxIdle) {
        this(maxIdle, props -> new KafkaConsumer<>(props));
    }

    KafkaConsumerPool(int maxIdle, ConsumerFactory factory) {
        this.maxIdle = Math.max(0, maxIdle);
        this.factory = factory;
    }

    /** True when this pool keeps nothing — every lease then builds and closes its own client. */
    boolean isDisabled() {
        return maxIdle == 0;
    }

    /**
     * Borrows a consumer, building one when none is idle.
     *
     * @param props used only when a new client has to be built; a pooled one keeps the properties
     *     it was created with, which is why {@link #flush()} is what a cluster repoint calls
     */
    Lease lease(Properties props) {
        Consumer<byte[], byte[]> pooled = null;
        synchronized (this) {
            if (!closed && !idle.isEmpty()) {
                pooled = idle.pop();
            }
        }
        return new Lease(pooled != null ? pooled : factory.create(props), pooled != null);
    }

    /**
     * Closes and drops everything held.
     *
     * <p>What a cluster repoint calls: a pooled consumer carries the bootstrap address, the
     * security settings and the deserializers it was built with, so after {@code POST /api/config}
     * the idle ones are pointed at the previous cluster. Keeping them would answer a question
     * about cluster B with a client connected to cluster A, which is the one failure this whole
     * class must not introduce.
     */
    void flush() {
        Deque<Consumer<byte[], byte[]>> discarded;
        synchronized (this) {
            discarded = new ArrayDeque<>(idle);
            idle.clear();
        }
        discarded.forEach(KafkaConsumerPool::closeQuietly);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        flush();
    }

    private static void closeQuietly(Consumer<byte[], byte[]> consumer) {
        try {
            consumer.close(CLOSE_TIMEOUT);
        } catch (Exception e) {
            log.debug("A pooled consumer did not close cleanly: {}", e.toString());
        }
    }

    /** One borrowed consumer, returned to the pool when the lease is closed. */
    final class Lease implements AutoCloseable {

        private final Consumer<byte[], byte[]> consumer;
        private final boolean reused;
        private boolean discarded;
        private boolean released;

        private Lease(Consumer<byte[], byte[]> consumer, boolean reused) {
            this.consumer = consumer;
            this.reused = reused;
        }

        Consumer<byte[], byte[]> consumer() {
            return consumer;
        }

        /** True when this lease was served from the pool rather than by building a client. */
        boolean reused() {
            return reused;
        }

        /** Says the borrower does not vouch for this consumer: it is closed rather than returned. */
        void discard() {
            discarded = true;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            if (discarded || maxIdle == 0) {
                closeQuietly(consumer);
                return;
            }
            try {
                // Resumed explicitly, then unsubscribed. Clearing the assignment does drop the
                // per-partition state that holds the pause flag, so the second call alone is very
                // nearly enough — but "very nearly", inferred from how the client happens to be
                // built, is not what a borrowed consumer's cleanliness should rest on. Saying it
                // outright costs nothing and is what makes the reset checkable: the pool's own
                // test asserts an empty paused set, and it failed against the version that only
                // unsubscribed. Resume first, while there is still an assignment to name.
                consumer.resume(consumer.paused());
                consumer.unsubscribe();
            } catch (Exception e) {
                log.debug("A consumer could not be reset and is closed rather than pooled: {}", e.toString());
                closeQuietly(consumer);
                return;
            }
            boolean keep;
            synchronized (KafkaConsumerPool.this) {
                keep = !closed && idle.size() < maxIdle;
                if (keep) idle.push(consumer);
            }
            if (!keep) closeQuietly(consumer);
        }
    }
}
