// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.compagnonsdudev.kafkasqlexplorer.forecast.ForecastPilotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ForecastControllerTest {
  private final ForecastPilotService pilot = mock(ForecastPilotService.class);

  @SuppressWarnings("unchecked")
  private ForecastController controller() {
    ObjectProvider<ForecastPilotService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(pilot);
    return new ForecastController(provider);
  }

  @Test
  void aRefusalAnswers409WithItsReason() throws Exception {
    doThrow(new IllegalArgumentException("The last quality block failed"))
        .when(pilot)
        .activate("id", true);
    var response = controller().activate("id", new ForecastController.Activation(true, true));
    assertEquals(409, response.getStatusCode().value());
    assertEquals("The last quality block failed", response.getBody().reason());
  }

  @Test
  void anAcceptedActivationAnswers204WithoutBody() throws Exception {
    var response = controller().activate("id", new ForecastController.Activation(false, false));
    assertEquals(204, response.getStatusCode().value());
    assertNull(response.getBody());
    verify(pilot).activate("id", false);
  }
}
