// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Keeps a few idle PostgreSQL connections for the forecast store.
 *
 * <p>Every read opened and closed a connection, TLS handshake included, and the breach tool opened
 * one per series. Idle connections are bounded and the number in use is not: a connection beyond
 * the idle bound is simply closed on return, so this never queues a caller.
 *
 * <p>A returned connection is rolled back before reuse, because the pilot's fence is a
 * <em>transaction</em> advisory lock: a borrower that closed without committing — an exception
 * path — must release it exactly as a physical close did.
 */
final class ForecastConnectionPool {
  @FunctionalInterface
  interface Factory {
    Connection open() throws Exception;
  }

  private static final int VALIDATION_TIMEOUT_SECONDS = 1;

  private final Factory factory;
  private final BlockingQueue<Connection> idle;

  ForecastConnectionPool(Factory factory, int maxIdle) {
    this.factory = factory;
    this.idle = new ArrayBlockingQueue<>(maxIdle);
  }

  /** A connection whose {@code close()} returns it here instead of closing it. */
  Connection borrow() throws Exception {
    Connection physical;
    while ((physical = idle.poll()) != null) {
      if (isUsable(physical)) return wrap(physical);
      closeQuietly(physical);
    }
    return wrap(factory.open());
  }

  int idleCount() {
    return idle.size();
  }

  private Connection wrap(Connection physical) {
    var returned = new java.util.concurrent.atomic.AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "close" -> {
                  if (returned.compareAndSet(false, true)) release(physical);
                  return null;
                }
                case "isClosed" -> {
                  return returned.get() || physical.isClosed();
                }
                case "unwrap", "isWrapperFor" -> {
                  // Handing out the physical connection would let a caller close it under the pool.
                  throw new java.sql.SQLFeatureNotSupportedException("Pooled connection");
                }
                default -> {
                  if (returned.get()) throw new java.sql.SQLException("Connection returned to pool");
                  try {
                    return method.invoke(physical, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                }
              }
            });
  }

  private void release(Connection physical) {
    try {
      if (!physical.getAutoCommit()) {
        physical.rollback();
        physical.setAutoCommit(true);
      }
      if (!idle.offer(physical)) physical.close();
    } catch (Exception e) {
      closeQuietly(physical);
    }
  }

  private static boolean isUsable(Connection c) {
    try {
      return c.isValid(VALIDATION_TIMEOUT_SECONDS);
    } catch (Exception e) {
      return false;
    }
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (Exception ignored) {
      // Already broken: nothing left to release.
    }
  }
}
