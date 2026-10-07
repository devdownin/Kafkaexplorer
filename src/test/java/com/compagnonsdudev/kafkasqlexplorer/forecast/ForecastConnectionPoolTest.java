// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastConnectionPoolTest {
  private final List<Connection> opened = new ArrayList<>();

  private ForecastConnectionPool pool(int maxIdle) {
    return new ForecastConnectionPool(
        () -> {
          var c = mock(Connection.class);
          when(c.isValid(anyInt())).thenReturn(true);
          when(c.getAutoCommit()).thenReturn(true);
          opened.add(c);
          return c;
        },
        maxIdle);
  }

  @Test
  void aReturnedConnectionIsReusedInsteadOfReopened() throws Exception {
    var pool = pool(2);
    pool.borrow().close();
    pool.borrow().close();
    assertEquals(1, opened.size());
    verify(opened.getFirst(), never()).close();
  }

  @Test
  void anUncommittedTransactionIsRolledBackSoItsAdvisoryLockIsReleased() throws Exception {
    var pool = pool(2);
    var c = pool.borrow();
    var physical = opened.getFirst();
    when(physical.getAutoCommit()).thenReturn(false);
    c.close();
    verify(physical).rollback();
    verify(physical).setAutoCommit(true);
    assertEquals(1, pool.idleCount());
  }

  @Test
  void beyondTheIdleBoundAConnectionIsClosedNotQueued() throws Exception {
    var pool = pool(1);
    var a = pool.borrow();
    var b = pool.borrow();
    a.close();
    b.close();
    assertEquals(1, pool.idleCount());
    verify(opened.get(1)).close();
  }

  @Test
  void anInvalidIdleConnectionIsDiscarded() throws Exception {
    var pool = pool(1);
    pool.borrow().close();
    when(opened.getFirst().isValid(anyInt())).thenReturn(false);
    pool.borrow().close();
    assertEquals(2, opened.size());
    verify(opened.getFirst()).close();
  }

  @Test
  void aConnectionCannotBeUsedOrClosedTwiceAfterItsReturn() throws Exception {
    var pool = pool(1);
    var c = pool.borrow();
    c.close();
    c.close();
    assertTrue(c.isClosed());
    assertThrows(SQLException.class, c::commit);
    assertEquals(1, pool.idleCount());
  }

  @Test
  void aFailedResetClosesInsteadOfPooling() throws Exception {
    var pool = pool(1);
    var c = pool.borrow();
    var physical = opened.getFirst();
    when(physical.getAutoCommit()).thenReturn(false);
    doThrow(new SQLException("broken")).when(physical).rollback();
    c.close();
    assertEquals(0, pool.idleCount());
    verify(physical).close();
  }
}
