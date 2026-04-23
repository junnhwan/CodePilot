package com.codepilot.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codepilot.gateway")
public class GatewayProperties {

    private final Github github = new Github();

    private final RateLimit rateLimit = new RateLimit();

    public Github getGithub() {
        return github;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class Github {

        private String apiBaseUrl = "https://api.github.com";

        private String webhookSecret = "";

        private String apiToken = "";

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }
    }

    public static class RateLimit {

        private String perRepo = "10/min";

        private String global = "100/min";

        public String getPerRepo() {
            return perRepo;
        }

        public void setPerRepo(String perRepo) {
            this.perRepo = perRepo;
        }

        public String getGlobal() {
            return global;
        }

        public void setGlobal(String global) {
            this.global = global;
        }
    }
}
