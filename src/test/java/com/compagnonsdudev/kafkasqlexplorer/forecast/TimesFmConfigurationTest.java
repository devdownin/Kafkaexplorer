// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class TimesFmConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(MetricHistoryConfigurationTest.Binding.class,
            ForecastingProperties.class, TimesFmConfiguration.class);
    }

    @Test void disabledFeatureDoesNotCreateOrValidateAClient() {
        runner().withPropertyValues("explorer.forecasting.inference.service-url=invalid").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(TimesFmClient.class).isEmpty());
        });
    }
    @Test void enabledClientBindsWithoutConnectingToTheModel() {
        runner().withPropertyValues("explorer.forecasting.inference.enabled=true",
            "explorer.forecasting.inference.token=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "explorer.forecasting.inference.service-url=http://127.0.0.1:1",
            "explorer.forecasting.inference.timeout=2s").run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(TimesFmClient.class).size());
            });
    }
    @Test void enabledClientRequiresAServiceCredential() {
        runner().withPropertyValues("explorer.forecasting.inference.enabled=true").run(context ->
            assertNotNull(context.getStartupFailure()));
    }
    @ParameterizedTest @ValueSource(strings = {"file:///tmp/model", "http://user:secret@host", "http://host/path", "http://host?token=secret", "http://host#fragment"})
    void endpointCannotCarryCredentialsOrClientControlledPaths(String url) {
        var p = new TimesFmInferenceProperties();
        p.setServiceUrl(url);
        p.setToken("x".repeat(32));
        assertThrows(IllegalArgumentException.class, p::validateEnabled);
    }
}
