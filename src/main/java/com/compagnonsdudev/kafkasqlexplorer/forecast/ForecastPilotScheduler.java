// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the pilot on a thread of its own.
 *
 * <p>As a {@code @Scheduled} method the cycle shared Spring's scheduler, which has a single thread,
 * with {@code MetricService.refreshMetrics}: a cycle of up to a minute plus one 35 s inference
 * delayed collection, and the missed refreshes became gaps in the very history it forecasts from.
 *
 * <p>The executor is private on purpose. Published as a {@code ScheduledExecutorService} bean it
 * would satisfy Spring Boot's {@code @ConditionalOnMissingBean} on the task scheduler, and every
 * other {@code @Scheduled} method of the application would move onto this thread.
 */
public final class ForecastPilotScheduler implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(ForecastPilotScheduler.class);
  static final String THREAD_NAME = "forecast-pilot";

  private final ForecastPilotService pilot;
  private final Duration interval;
  /** Run after each cycle, on this thread: what is derived from the forecasts the cycle just wrote. */
  private final Runnable afterCycle;
  private volatile ScheduledExecutorService executor;

  public ForecastPilotScheduler(ForecastPilotService pilot, Duration interval) {
    this(pilot, interval, () -> {});
  }

  public ForecastPilotScheduler(ForecastPilotService pilot, Duration interval, Runnable afterCycle) {
    this.pilot = pilot;
    this.interval = interval;
    this.afterCycle = afterCycle;
  }

  @Override
  public synchronized void start() {
    if (executor != null) return;
    executor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name(THREAD_NAME).daemon().factory());
    long period = interval.toMillis();
    executor.scheduleWithFixedDelay(this::cycle, period, period, TimeUnit.MILLISECONDS);
  }

  /** An exception escaping the task would cancel every later run of a fixed-delay schedule. */
  private void cycle() {
    try {
      pilot.refresh();
    } catch (RuntimeException e) {
      LOG.warn("Forecast pilot cycle failed", e);
    }
    // After a failed cycle too: the previous forecasts are still the latest ones, and what is
    // derived from them (the threshold-reach series) has to keep counting down from them.
    try {
      afterCycle.run();
    } catch (RuntimeException e) {
      LOG.warn("Forecast pilot post-cycle step failed", e);
    }
  }

  @Override
  public synchronized void stop() {
    if (executor == null) return;
    executor.shutdownNow();
    executor = null;
  }

  @Override
  public boolean isRunning() {
    return executor != null;
  }
}
