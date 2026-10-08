// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.forecast.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Uses the application's existing operator REST access boundary; MCP mutations remain absent. */
@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {
  private final ObjectProvider<ForecastPilotService> services;

  public ForecastController(ObjectProvider<ForecastPilotService> services) {
    this.services = services;
  }

  public record SeriesView(
      String seriesId,
      String metricId,
      String environment,
      ForecastRecord result,
      ForecastPilotService.ActivationReadiness activation,
      long stepMillis,
      int minimumContextPoints,
      boolean withdrawable) {}

  public record Status(boolean enabled, String state, List<SeriesView> series) {}

  @GetMapping
  public ResponseEntity<Status> status() {
    var p = services.getIfAvailable();
    if (p == null) return ResponseEntity.ok(new Status(false, "DISABLED", List.of()));
    try {
      var rows = new java.util.ArrayList<SeriesView>();
      for (var s : p.series()) {
        var result = p.get(s.seriesId());
        rows.add(
            new SeriesView(
                s.seriesId(), s.metricId(), s.environment(), result, p.readiness(s.seriesId(), result),
                s.profile().stepMillis(), p.minimumContextPoints(s), p.isWithdrawable(s.seriesId())));
      }
      return ResponseEntity.ok(new Status(true, "AVAILABLE", List.copyOf(rows)));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(new Status(true, "UNAVAILABLE", List.of()));
    }
  }

  public record Activation(boolean active, boolean confirmed) {}

  /** Why activation was refused: the same sentence the page shows beside the button. */
  public record Refusal(String reason) {}

  @PutMapping("/{seriesId}/activation")
  public ResponseEntity<Refusal> activate(
      @PathVariable String seriesId, @RequestBody Activation activation) {
    var p = services.getIfAvailable();
    if (p == null) return ResponseEntity.notFound().build();
    if (activation == null || activation.active() && !activation.confirmed())
      return ResponseEntity.badRequest().build();
    try {
      p.activate(seriesId, activation.active());
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(409).body(new Refusal(e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(503).build();
    }
  }
}
