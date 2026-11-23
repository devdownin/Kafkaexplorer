// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Opt-in, local-demo-only scenario control. Disabled by default and never exposes arbitrary
 * file paths or shell execution. The Kafka generator reads the same one-line file.
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

  public record State(String scenario, boolean enabled) {}
  public record Selection(String scenario) {}

  @GetMapping
  public ResponseEntity<State> status() {
    if (!enabled) return ResponseEntity.notFound().build();
    try {
      Path file = Path.of(directory).resolve("demo-scenario.conf");
      String value = Files.isRegularFile(file)
          ? Files.readString(file, StandardCharsets.UTF_8).trim() : "normal";
      return ResponseEntity.ok(new State(SCENARIOS.contains(value) ? value : "normal", true));
    } catch (IOException e) {
      return ResponseEntity.internalServerError().build();
    }
  }

  @PutMapping
  public ResponseEntity<State> select(@RequestBody Selection selection,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestHeader(value = "Host", required = false) String host) {
    if (!enabled) return ResponseEntity.notFound().build();
    // Browser-driven changes must originate on the same host. The demo remains bound to
    // loopback by default; do not enable this controller on a public deployment.
    if (origin != null && (host == null || !sameOrigin(origin, host)))
      return ResponseEntity.status(403).build();
    if (selection == null || !SCENARIOS.contains(selection.scenario()))
      return ResponseEntity.badRequest().build();
    try {
      Path dir = Path.of(directory);
      Files.createDirectories(dir);
      Path temp = Files.createTempFile(dir, ".demo-scenario-", ".tmp");
      try {
        Files.writeString(temp, selection.scenario() + "\n", StandardCharsets.UTF_8);
        Files.move(temp, dir.resolve("demo-scenario.conf"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
      return ResponseEntity.ok(new State(selection.scenario(), true));
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
