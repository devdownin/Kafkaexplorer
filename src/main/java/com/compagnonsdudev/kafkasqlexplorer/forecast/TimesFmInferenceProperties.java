// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.net.URI;
import java.time.Duration;

/** Operator-owned endpoint; never supplied by a metric, REST request or MCP argument. */
public class TimesFmInferenceProperties {
    private boolean enabled;
    private String serviceUrl = "http://timesfm:8000";
    private String token = "";
    private Duration timeout = Duration.ofSeconds(35);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String value) { serviceUrl = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }

    public URI validateEnabled() {
        URI uri;
        try { uri = URI.create(serviceUrl); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid TimesFM service URL"); }
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("TimesFM requires an HTTP(S) origin without credentials or a path");
        }
        if (token == null || token.length() < 32 || token.length() > 256
            || token.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("TimesFM token requires 32–256 visible ASCII characters");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
            || timeout.compareTo(Duration.ofSeconds(40)) > 0) {
            throw new IllegalArgumentException("TimesFM timeout must be between 1 and 40 seconds");
        }
        return uri.resolve("/v1/forecast");
    }
}
