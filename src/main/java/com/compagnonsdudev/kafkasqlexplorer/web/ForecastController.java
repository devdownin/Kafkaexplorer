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
      boolean withdrawable,
      /** Why this series could not be read; the other series are answered anyway. */
      String error) {}

  public record Status(boolean enabled, String state, List<SeriesView> series) {}

  @GetMapping
  public ResponseEntity<Status> status() {
    var p = services.getIfAvailable();
    if (p == null) return ResponseEntity.ok(new Status(false, "DISABLED", List.of()));
    try {
      var rows = new java.util.ArrayList<SeriesView>();
      // One series per row, each read on its own (METRICS-FORECAST-UX-AUDIT.md B2): a single record
      // that did not decode turned this whole answer into a 503 with no series, which also stripped
      // every metric card of its forecast line and left nothing to withdraw the culprit from.
      // The list carries no history points (P1): up to 512 per series were sent every 30 s to a
      // page that draws one series, whose detail is GET /{seriesId}.
      for (var s : p.series()) rows.add(view(p, s, false));
      return ResponseEntity.ok(new Status(true, "AVAILABLE", List.copyOf(rows)));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(new Status(true, "UNAVAILABLE", List.of()));
    }
  }

  /** One series with its whole record — the context the chart draws included. */
  @GetMapping("/{seriesId}")
  public ResponseEntity<SeriesView> series(@PathVariable String seriesId) {
    var p = services.getIfAvailable();
    if (p == null) return ResponseEntity.notFound().build();
    ForecastPilotProperties.Series s;
    try {
      s = p.resolve(seriesId);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(view(p, s, true));
  }

  private static SeriesView view(ForecastPilotService p, ForecastPilotProperties.Series s, boolean full) {
    String id = s.seriesId();
    try {
      var result = p.get(id);
      return new SeriesView(id, s.metricId(), s.environment(), full ? result : withoutHistory(result),
          p.readiness(id, result), s.profile().stepMillis(), p.minimumContextPoints(s), p.isWithdrawable(id), null);
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(ForecastController.class)
          .warn("Forecast series {} could not be read: {}", id, e.toString());
      String error = "The latest result of this series could not be read (" + e.getClass().getSimpleName()
          + "); the other series are unaffected. The server log has the cause.";
      ForecastPilotService.ActivationReadiness activation;
      try {
        activation = p.readiness(id, null);
      } catch (Exception ignored) {
        activation = new ForecastPilotService.ActivationReadiness(
            false, error, 0, 0, null, 0, ForecastQualityWindow.FAILED_BLOCKS_TO_DEGRADE);
      }
      return new SeriesView(id, s.metricId(), s.environment(), null, activation, s.profile().stepMillis(),
          p.minimumContextPoints(s), p.isWithdrawable(id), error);
    }
  }

  /** The record without its history points: the counts and the status stay, the 512 points go. */
  static ForecastRecord withoutHistory(ForecastRecord r) {
    if (r == null || r.context() == null) return r;
    var c = r.context();
    var light = new PreparedMetricSeries(c.status(), c.reason(), c.seriesId(), c.definitionVersion(),
        c.sourceUnit(), c.outputUnit(), c.fromInclusive(), c.toExclusive(), c.profile(),
        c.profileFingerprint(), c.inputFingerprint(), c.observedPoints(), c.missingPoints(),
        c.imputedPoints(), List.of());
    return new ForecastRecord(r.key(), r.generatedAt(), r.state(), r.strategy(), r.visibility(), light,
        r.forecast(), r.quality(), r.evaluatedPoints(), r.evaluatedThrough(), r.baselineMae(), r.reason(),
        r.qualityWindow());
  }

  /**
   * Timestral beside TimesFM on the same cohorts. Read-only and operator-side: the shadow is never
   * served, so it reaches neither the forecast list above nor any MCP tool.
   */
  @GetMapping("/{seriesId}/shadow")
  public ResponseEntity<ForecastPilotService.ShadowComparison> shadow(@PathVariable String seriesId) {
    var p = services.getIfAvailable();
    if (p == null || !p.shadowEnabled()) return ResponseEntity.notFound().build();
    try {
      var comparison = p.shadowComparison(seriesId);
      return comparison == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(comparison);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.notFound().build();
    } catch (Exception e) {
      return ResponseEntity.status(503).build();
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
