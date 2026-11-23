// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class DemoControlControllerTest {
  @TempDir Path directory;

  private DemoControlController controller(boolean enabled) {
    var controller = new DemoControlController();
    ReflectionTestUtils.setField(controller, "enabled", enabled);
    ReflectionTestUtils.setField(controller, "directory", directory.toString());
    ReflectionTestUtils.setField(controller, "ackMaxAgeSeconds", 75L);
    return controller;
  }

  @Test
  void requestsAreNotConfusedWithKafkaVerifiedApplication() throws Exception {
    var controller = controller(true);
    var initial = controller.status().getBody();
    assertNotNull(initial);
    assertEquals("APPLYING", initial.phase());
    assertNull(initial.scenario());
    assertEquals("normal", initial.requestedScenario());

    var requested = controller.select(new DemoControlController.Selection("incident"),
        "http://localhost:8080", "localhost:8080").getBody();
    assertNotNull(requested);
    assertEquals("incident", requested.requestedScenario());
    assertNull(requested.scenario());
    assertEquals("APPLYING", requested.phase());

    String request = Files.readString(directory.resolve("demo-scenario.conf"), StandardCharsets.UTF_8).trim();
    assertTrue(request.startsWith("incident|"));
    Files.writeString(directory.resolve("demo-scenario-applied.conf"),
        request + "|" + Instant.now().getEpochSecond() + "\n");
    var confirmed = controller.status().getBody();
    assertNotNull(confirmed);
    assertEquals("ACTIVE", confirmed.phase());
    assertEquals("incident", confirmed.scenario());
    assertNotNull(confirmed.appliedAt());
    assertEquals(confirmed.appliedAt(), confirmed.startedAt()); // legacy receipt

    long firstTick = Instant.now().getEpochSecond() - 10;
    Files.writeString(directory.resolve("demo-scenario-applied.conf"),
        "v2|" + request + "|" + Instant.now().getEpochSecond() + "|" + firstTick + "\n");
    var versioned = controller.status().getBody();
    assertNotNull(versioned);
    assertEquals(firstTick * 1000, versioned.startedAt());
    assertEquals("ACTIVE", versioned.phase());

    var replaced = controller.select(new DemoControlController.Selection("normal"), null, null).getBody();
    assertNotNull(replaced);
    assertEquals("APPLYING", replaced.phase());
    assertEquals("incident", replaced.scenario());
    assertEquals("normal", replaced.requestedScenario());
  }

  @Test
  void staleAcknowledgementNeverAdvertisesADeadGeneratorAsActive() throws Exception {
    var controller = controller(true);
    Files.writeString(directory.resolve("demo-scenario.conf"), "overload\n");
    Files.writeString(directory.resolve("demo-scenario-applied.conf"),
        "overload|" + (Instant.now().getEpochSecond() - 300) + "\n");
    var status = controller.status().getBody();
    assertNotNull(status);
    assertEquals("STALE", status.phase());
    assertNull(status.scenario());
    assertEquals("overload", status.requestedScenario());
  }

  @Test
  void controllerIsOptInAndRejectsCrossOriginAndUnknownScenarios() {
    var disabled = controller(false);
    assertEquals(404, disabled.status().getStatusCode().value());
    assertEquals(404, disabled.select(new DemoControlController.Selection("normal"), null, null)
        .getStatusCode().value());

    var enabled = controller(true);
    assertEquals(403, enabled.select(new DemoControlController.Selection("incident"),
        "https://other.invalid", "localhost:8080").getStatusCode().value());
    assertEquals(400, enabled.select(new DemoControlController.Selection("arbitrary"), null, null)
        .getStatusCode().value());
  }
}
