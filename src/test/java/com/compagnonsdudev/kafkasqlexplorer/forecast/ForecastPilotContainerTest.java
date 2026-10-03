// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CI exercises actual PostgreSQL locks and publication fencing, not an H2 emulation. */
@Testcontainers(disabledWithoutDocker = true)
class ForecastPilotContainerTest {
  @Container
  static final GenericContainer<?> postgres =
      new GenericContainer<>("postgres:17.6-alpine")
          .withEnv("POSTGRES_DB", "history")
          .withEnv("POSTGRES_PASSWORD", "integration-test-only")
          .withExposedPorts(5432)
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                  .withStartupTimeout(Duration.ofSeconds(60)));

  @Test
  void realDatabaseFencesAndPersists() throws Exception {
    new ForecastPilotPostgresTest()
        .exercise(
            "jdbc:postgresql://"
                + postgres.getHost()
                + ":"
                + postgres.getMappedPort(5432)
                + "/history",
            "postgres",
            "integration-test-only");
  }
}
