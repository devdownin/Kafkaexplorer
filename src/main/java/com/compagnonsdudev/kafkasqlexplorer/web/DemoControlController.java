// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in, local-demo-only scenario control. Never enable on a production Explorer.
 * No Docker socket or arbitrary command execution; only the five reviewed scenario names
 * can be written to the dedicated shared volume. Disabled by default (no API route).
 */
@RestController
@RequestMapping(value = "/api/demo-control", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "explorer.demo.control.enabled", havingValue = "true")
public class DemoControlController {
  private static final Set<String> SCENARIOS =
      Set.of("normal", "overload", "incident", "recover", "seasonal");
  private final Path scenarioFile;

  public record ControlStatus(String scenario, Long changedAt) {}
  public record ScenarioChange(String scenario) {}

  public DemoControlController(
      @Value("${explorer.demo.control.file:/app/demo-control/demo-scenario.conf}") String file) {
    scenarioFile = Path.of(file).toAbsolutePath().normalize();
  }

  @GetMapping
  public ControlStatus status() {
    String name = "normal";
    Long changedAt = null;
    try {
      if (Files.isRegularFile(scenarioFile)) {
        String read = Files.readString(scenarioFile, StandardCharsets.UTF_8).trim();
        if (SCENARIOS.contains(read)) name = read;
        changedAt = Files.getLastModifiedTime(scenarioFile).toMillis();
      }
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demo scenario is unreadable", e);
    }
    return new ControlStatus(name, changedAt);
  }

  /**
   * JSON + custom request header prevents cross-site HTML form submissions; CORS is not enabled.
   * This is not user authentication: the demo must remain bound to loopback.
   */
  @PostMapping(value = "/scenario", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ControlStatus change(
      @RequestBody ScenarioChange request,
      @RequestHeader(value = "X-Kex-Demo-Control", required = false) String intent) {
    if (!"change-scenario".equals(intent)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo control intent header required");
    }
    if (request == null || request.scenario() == null || !SCENARIOS.contains(request.scenario())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown demo scenario");
    }
    Path parent = scenarioFile.getParent();
    Path staged = null;
    try {
      // The generator reads once per tick. Atomic replacement avoids observing a partial write.
      staged = Files.createTempFile(parent, ".demo-scenario-", ".tmp");
      Files.writeString(staged, request.scenario() + "\n", StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-r--r--"));
      Files.move(staged, scenarioFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return status();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demo scenario could not be saved", e);
    } finally {
      if (staged != null) {
        try { Files.deleteIfExists(staged); } catch (IOException ignored) { /* best effort */ }
      }
    }
  }
}
