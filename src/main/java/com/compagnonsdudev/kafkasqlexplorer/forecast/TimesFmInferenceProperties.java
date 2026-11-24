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
    private Shadow shadow = new Shadow();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String value) { serviceUrl = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public Shadow getShadow() { return shadow; }
    public void setShadow(Shadow value) { shadow = value; }

    /**
     * Timestral beside TimesFM on the same service, scored on the same cohorts and never served.
     * Shorter timeout than the primary's: each shadow call is spent inside the same cycle budget.
     */
    public static class Shadow {
        /** What the service loads when its own {@code TIMESTRAL_MODEL} is unset; the two must agree. */
        public static final String DEFAULT_MODEL_ID = "mistralai/Timestral-28M";
        private static final java.util.regex.Pattern REPO_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}");

        private boolean enabled;
        private Duration timeout = Duration.ofSeconds(15);
        private String modelId = DEFAULT_MODEL_ID;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration value) { timeout = value; }
        public String getModelId() { return modelId; }
        public void setModelId(String value) { modelId = value; }

        /**
         * The checkpoint the service was told to load ({@code TIMESTRAL_MODEL}), which every shadow
         * answer must name. Blank is the default rather than an error, as on the service; the primary's
         * id is refused, since a shadow that answered under it could be stored as a TimesFM forecast.
         */
        static String validatedModelId(String modelId) {
            String value = modelId == null || modelId.isBlank() ? DEFAULT_MODEL_ID : modelId.strip();
            if (!REPO_ID.matcher(value).matches() || value.equals(TimesFmClient.MODEL_ID)) {
                throw new IllegalArgumentException("The shadow model must be a Hugging Face repository id (owner/name) "
                    + "other than TimesFM's");
            }
            return value;
        }

        Duration validatedTimeout() {
            if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Shadow timeout must be between 1 and 30 seconds");
            }
            return timeout;
        }
    }

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
