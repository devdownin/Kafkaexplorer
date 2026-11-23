// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Local, opt-in demo-only control. A write requests a scenario; only the generator,
 * after reading back Kafka offsets on its next tick, can acknowledge its application.
 * Not exposed in regular installations and never executes shell commands.
 */
@RestController
@RequestMapping("/api/demo-control")
public class DemoControlController {
  private static final Set<String> SCENARIOS =
      Set.of("normal", "overload", "incident", "recover", "seasonal");

  @Value("${explorer.demo-control.enabled:false}")
  private boolean enabled;

  @Value("${explorer.demo-control.directory:/app/demo-control}")
  private String directory;

  /** An old acknowledgement must not advertise a dead traffic generator as active. */
  @Value("${explorer.demo-control.ack-max-age-seconds:75}")
  private long ackMaxAgeSeconds;

  /**
   * scenario is the last *confirmed* Kafka scenario, nullable when unverified.
   * requestedScenario is the operator's request; phase is ACTIVE, APPLYING or STALE.
   * appliedAt is epoch milliseconds of the generator's last verified tick.
   */
  public record State(boolean enabled, String scenario, String requestedScenario, String phase,
                      Long appliedAt, Long startedAt) {}
  public record Selection(String scenario) {}

  @GetMapping
  public ResponseEntity<State> status() {
    if (!enabled) return ResponseEntity.notFound().build();
    try {
      return ResponseEntity.ok(readState());
    } catch (IOException e) {
      return ResponseEntity.internalServerError().build();
    }
  }

  private State readState() throws IOException {
    Path dir = Path.of(directory);
    Path requestFile = dir.resolve("demo-scenario.conf");
    Path appliedFile = dir.resolve("demo-scenario-applied.conf");
    String request = Files.isRegularFile(requestFile)
        ? Files.readString(requestFile, StandardCharsets.UTF_8).trim() : "normal";
    String requested = scenarioOf(request);
    if (requested == null) throw new IOException("Invalid demo scenario request");
    if (!Files.isRegularFile(appliedFile))
      return new State(true, null, requested, "APPLYING", null, null);

    String receipt = Files.readString(appliedFile, StandardCharsets.UTF_8).trim();
    // v2|<exact request>|<last verified epoch>|<first verified epoch>.
    // Continue reading v1 receipts from older published demo generators.
    boolean v2 = receipt.startsWith("v2|");
    String payload = v2 ? receipt.substring(3) : receipt;
    int separator = payload.lastIndexOf('|');
    if (separator < 1) return new State(true, null, requested, "STALE", null, null);
    long startedSeconds;
    if (v2) {
      try {
        startedSeconds = Long.parseLong(payload.substring(separator + 1));
      } catch (NumberFormatException e) {
        return new State(true, null, requested, "STALE", null, null);
      }
      payload = payload.substring(0, separator);
      separator = payload.lastIndexOf('|');
      if (separator < 1) return new State(true, null, requested, "STALE", null, null);
    } else {
      startedSeconds = -1;
    }
    String acknowledgedRequest = payload.substring(0, separator);
    String acknowledgedScenario = scenarioOf(acknowledgedRequest);
    long epochSeconds;
    try {
      epochSeconds = Long.parseLong(payload.substring(separator + 1));
      if (!v2) startedSeconds = epochSeconds;
    } catch (NumberFormatException e) {
      return new State(true, null, requested, "STALE", null, null);
    }
    long now = Instant.now().getEpochSecond();
    if (acknowledgedScenario == null || epochSeconds <= 0 || epochSeconds > now + 5
        || startedSeconds <= 0 || startedSeconds > epochSeconds
        || now - epochSeconds > ackMaxAgeSeconds)
      return new State(true, null, requested, "STALE", null, null);

    // A previous request may still be confirmed while a new one is in flight.
    return new State(true, acknowledgedScenario, requested,
        request.equals(acknowledgedRequest) ? "ACTIVE" : "APPLYING",
        epochSeconds * 1000, startedSeconds * 1000);
  }

  private static String scenarioOf(String request) {
    int separator = request.indexOf('|');
    String name = separator < 0 ? request : request.substring(0, separator);
    return SCENARIOS.contains(name) ? name : null;
  }

  @PutMapping
  public ResponseEntity<State> select(@RequestBody Selection selection,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestHeader(value = "Host", required = false) String host) {
    if (!enabled) return ResponseEntity.notFound().build();
    if (origin != null && (host == null || !sameOrigin(origin, host)))
      return ResponseEntity.status(403).build();
    if (selection == null || !SCENARIOS.contains(selection.scenario()))
      return ResponseEntity.badRequest().build();

    try {
      Path dir = Path.of(directory);
      Files.createDirectories(dir);
      Path temp = Files.createTempFile(dir, ".demo-scenario-", ".tmp");
      try {
        // The UUID differentiates repeat requests and prevents a stale receipt from
        // confirming an action that has not yet reached Kafka.
        Files.writeString(temp, selection.scenario() + "|" + UUID.randomUUID() + "\n",
            StandardCharsets.UTF_8);
        // CLI and Explorer have different local UIDs. This directory is demo-only,
        // never contains credentials, and is not mounted outside this stack.
        Files.setPosixFilePermissions(temp, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE));
        Files.move(temp, dir.resolve("demo-scenario.conf"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
      return ResponseEntity.ok(readState());
    } catch (IOException e) {
      return ResponseEntity.internalServerError().build();
    }
  }

  private static boolean sameOrigin(String origin, String host) {
    try {
      var uri = java.net.URI.create(origin);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getUserInfo() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null || uri.getRawFragment() != null)
        return false;
      String authority = uri.getRawAuthority();
      return authority != null && authority.equalsIgnoreCase(host);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
