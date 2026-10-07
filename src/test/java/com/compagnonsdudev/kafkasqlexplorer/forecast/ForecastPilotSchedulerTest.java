// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class ForecastPilotSchedulerTest {

  @Test
  void cycleRunsOffTheApplicationsSharedSchedulingThread() throws Exception {
    var pilot = mock(ForecastPilotService.class);
    var thread = new CompletableFuture<String>();
    doAnswer(i -> thread.complete(Thread.currentThread().getName())).when(pilot).refresh();
    var scheduler = new ForecastPilotScheduler(pilot, Duration.ofMillis(10));
    scheduler.start();
    try {
      assertEquals(ForecastPilotScheduler.THREAD_NAME, thread.get(5, TimeUnit.SECONDS));
    } finally {
      scheduler.stop();
    }
    assertFalse(scheduler.isRunning());
  }

  @Test
  void aFailedCycleDoesNotCancelTheNextOnes() throws Exception {
    var pilot = mock(ForecastPilotService.class);
    var second = new CompletableFuture<Void>();
    doThrow(new IllegalStateException("boom"))
        .doAnswer(i -> second.complete(null))
        .when(pilot)
        .refresh();
    var scheduler = new ForecastPilotScheduler(pilot, Duration.ofMillis(10));
    scheduler.start();
    try {
      assertDoesNotThrow(() -> second.get(5, TimeUnit.SECONDS));
    } finally {
      scheduler.stop();
    }
  }

  /** Either bean type would switch off Spring Boot's task scheduler and capture @Scheduled. */
  @Test
  void configurationPublishesNoSchedulerBeanSpringBootWouldAdopt() {
    new ApplicationContextRunner()
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(
            MetricHistoryConfigurationTest.Binding.class,
            ForecastingProperties.class,
            ForecastPilotConfiguration.class,
            MetricHistoryConfiguration.class,
            TimesFmConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBean(ForecastPilotScheduler.class).isRunning());
              assertTrue(context.getBeansOfType(ScheduledExecutorService.class).isEmpty());
              assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
            });
  }
}
