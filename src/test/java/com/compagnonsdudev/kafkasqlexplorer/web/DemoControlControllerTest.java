// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DemoControlControllerTest {
  @TempDir Path directory;

  @Test
  void readsAndChangesOnlyAllowedModes() throws Exception {
    Path file = directory.resolve("demo-scenario.conf");
    var controller = new DemoControlController(file.toString());
    assertEquals("normal", controller.status().scenario());
    assertNull(controller.status().changedAt());
    var result = controller.change(new DemoControlController.ScenarioChange("overload"), "change-scenario");
    assertEquals("overload", result.scenario());
    assertNotNull(result.changedAt());
    assertEquals("overload", Files.readString(file).trim());
    controller.change(new DemoControlController.ScenarioChange("normal"), "change-scenario");
    assertEquals("normal", controller.status().scenario());
  }

  @Test
  void rejectsUnknownNamesAndUnintendedRequests() throws Exception {
    var controller = new DemoControlController(directory.resolve("demo-scenario.conf").toString());
    var unauthorized = assertThrows(ResponseStatusException.class,
        () -> controller.change(new DemoControlController.ScenarioChange("overload"), null));
    assertEquals(HttpStatus.FORBIDDEN, unauthorized.getStatusCode());
    for (String candidate : new String[] {"../secrets", "echo 1", "OVERLOAD", "", "normal\nincident"}) {
      var error = assertThrows(ResponseStatusException.class,
          () -> controller.change(new DemoControlController.ScenarioChange(candidate), "change-scenario"));
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
    assertFalse(Files.exists(directory.resolve("demo-scenario.conf")));
  }
}
