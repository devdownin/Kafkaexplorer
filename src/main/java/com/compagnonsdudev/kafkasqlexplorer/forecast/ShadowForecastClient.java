// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/**
 * The shadow client under its own type: a second {@code TimesFmClient} bean would make every
 * injection of the primary one ambiguous, and an ambiguity resolved the wrong way serves Timestral.
 */
public record ShadowForecastClient(TimesFmClient client) implements AutoCloseable {
  @Override
  public void close() {
    client.close();
  }
}
