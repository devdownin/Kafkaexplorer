// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer pool, driven through its factory seam so nothing needs a broker.
 *
 * <p>What is pinned here is not the saving — that is a property of the deployment and is measured
 * against a real cluster, not asserted — but the three rules that make lending a client safe at
 * all. Each of them, broken, produces a <em>wrong read</em> rather than a slow one: a consumer
 * handed out carrying the previous borrower's assignment, paused partitions or buffered records
 * answers a question nobody asked, and from outside that is indistinguishable from an empty topic
 * — which is exactly the shape of the three snapshot-read faults this codebase has already been
 * caught by.
 */
class KafkaConsumerPoolTest {

    private final AtomicInteger built = new AtomicInteger();
    private final Properties props = new Properties();

    private KafkaConsumerPool pool(int maxIdle) {
        return new KafkaConsumerPool(maxIdle, p -> {
            built.incrementAndGet();
            return new MockConsumer<>("earliest");
        });
    }

    @Test
    void aReturnedConsumerIsHandedOutAgainRatherThanRebuilt() {
        try (KafkaConsumerPool pool = pool(2)) {
            Consumer<byte[], byte[]> first;
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                first = lease.consumer();
                assertFalse(lease.reused(), "the pool was empty, so this one had to be built");
            }
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertSame(first, lease.consumer());
                assertTrue(lease.reused());
            }
            assertEquals(1, built.get(), "the second read must not have built a client");
        }
    }

    @Test
    void aReturnedConsumerCarriesNoAssignmentAndNoPausedPartition() {
        TopicPartition tp = new TopicPartition("demo.orders.1.received", 0);
        try (KafkaConsumerPool pool = pool(1)) {
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                Consumer<byte[], byte[]> consumer = lease.consumer();
                consumer.assign(List.of(tp));
                // What the record readers deliberately leave behind: a partition they have read to
                // its end is paused and never resumed, because their consumer used to be closed.
                consumer.pause(List.of(tp));
                assertFalse(consumer.paused().isEmpty());
            }
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertTrue(lease.consumer().assignment().isEmpty(),
                        "a borrowed consumer must not carry the previous read's assignment");
                assertTrue(lease.consumer().paused().isEmpty(),
                        "nor its paused partitions — it would deliver nothing and read as an empty topic");
            }
        }
    }

    @Test
    void aDiscardedConsumerIsNotHandedToAnybodyElse() {
        try (KafkaConsumerPool pool = pool(2)) {
            Consumer<byte[], byte[]> first;
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                first = lease.consumer();
                lease.discard();   // what a read that threw does
            }
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertNotSame(first, lease.consumer());
                assertFalse(lease.reused());
            }
            assertEquals(2, built.get());
        }
    }

    @Test
    void aLeaseIsExclusiveWhileItIsHeld() {
        try (KafkaConsumerPool pool = pool(4)) {
            // A KafkaConsumer is single-threaded, so two concurrent reads must never be handed the
            // same client. Holding one lease and taking another is the check for that.
            try (KafkaConsumerPool.Lease a = pool.lease(props);
                 KafkaConsumerPool.Lease b = pool.lease(props)) {
                assertNotSame(a.consumer(), b.consumer());
            }
            assertEquals(2, built.get());
        }
    }

    @Test
    void aPoolSizedZeroKeepsNothing() {
        try (KafkaConsumerPool pool = pool(0)) {
            assertTrue(pool.isDisabled());
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertFalse(lease.reused());
            }
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertFalse(lease.reused(), "the shipped default must behave exactly as before");
            }
            assertEquals(2, built.get());
        }
    }

    @Test
    void thePoolNeverGrowsPastItsBound() {
        try (KafkaConsumerPool pool = pool(1)) {
            KafkaConsumerPool.Lease a = pool.lease(props);
            KafkaConsumerPool.Lease b = pool.lease(props);
            a.close();
            b.close();   // one over the bound: closed rather than kept
            assertEquals(2, built.get());

            try (KafkaConsumerPool.Lease first = pool.lease(props)) {
                assertTrue(first.reused());
            }
            try (KafkaConsumerPool.Lease ignored = pool.lease(props);
                 KafkaConsumerPool.Lease second = pool.lease(props)) {
                assertFalse(second.reused(), "only one was ever kept");
            }
        }
    }

    @Test
    void flushingDropsWhatWasBuiltForThePreviousCluster() {
        try (KafkaConsumerPool pool = pool(2)) {
            Consumer<byte[], byte[]> first;
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                first = lease.consumer();
            }
            // POST /api/config repoints the cluster: an idle client still holds the old bootstrap
            // address, and answering with it is the one failure this pool must not introduce.
            pool.flush();
            try (KafkaConsumerPool.Lease lease = pool.lease(props)) {
                assertNotSame(first, lease.consumer());
            }
        }
    }

    @Test
    void aClosedPoolKeepsNothingItIsHandedBack() {
        KafkaConsumerPool pool = pool(2);
        KafkaConsumerPool.Lease lease = pool.lease(props);
        pool.close();
        lease.close();   // returned after shutdown: must not be retained
        assertEquals(1, built.get());
        try (KafkaConsumerPool.Lease next = pool.lease(props)) {
            assertFalse(next.reused());
        }
    }
}
