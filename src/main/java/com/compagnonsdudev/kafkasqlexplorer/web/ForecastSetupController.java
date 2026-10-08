// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastSetupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Operator REST boundary only; there are no configuration mutation tools in MCP. */
@RestController
@RequestMapping("/api/forecasts")
public class ForecastSetupController {
  private final ForecastSetupService setup;
  public ForecastSetupController(ForecastSetupService setup) { this.setup = setup; }
  @GetMapping("/preparation")
  public ForecastSetupService.Readiness readiness() { return setup.readiness(); }
  @PostMapping("/preparation/probe")
  public ForecastSetupService.Readiness probe() { return setup.probe(); }
  @GetMapping("/candidates")
  public ForecastSetupService.Candidates candidates() { return setup.candidates(); }
  public record Problem(String reason) {}
  @PostMapping("/configuration")
  public ResponseEntity<?> draft(@RequestBody ForecastSetupService.DraftRequest request) {
    try { return ResponseEntity.ok(setup.draft(request)); }
    catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(new Problem(e.getMessage())); }
  }
  /** Approves at runtime when the deployment allows it; 409 names why it does not. */
  @PostMapping("/series")
  public ResponseEntity<?> apply(@RequestBody ForecastSetupService.DraftRequest request) {
    try { return ResponseEntity.status(201).body(setup.apply(request)); }
    catch (IllegalStateException e) { return ResponseEntity.status(409).body(new Problem(e.getMessage())); }
    catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(new Problem(e.getMessage())); }
    catch (Exception e) { return ResponseEntity.status(503).body(new Problem("Forecast persistence is unavailable")); }
  }
  @DeleteMapping("/series/{seriesId}")
  public ResponseEntity<?> withdraw(@PathVariable String seriesId) {
    try { setup.withdraw(seriesId); return ResponseEntity.noContent().build(); }
    catch (IllegalStateException | IllegalArgumentException e) { return ResponseEntity.status(409).body(new Problem(e.getMessage())); }
    catch (Exception e) { return ResponseEntity.status(503).body(new Problem("Forecast persistence is unavailable")); }
  }
  @GetMapping("/{seriesId}/progress")
  public ResponseEntity<ForecastSetupService.Progress> progress(@PathVariable String seriesId) {
    try { return ResponseEntity.ok(setup.progress(seriesId)); }
    catch (IllegalArgumentException e) { return ResponseEntity.notFound().build(); }
    catch (Exception e) { return ResponseEntity.status(503).build(); }
  }
}
