package com.codepilot.core.infrastructure.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codepilot.llm")
public class LlmProperties {

    private String defaultProvider = "openai";

    private String defaultModel = "gpt-4.1-mini";

    private List<Provider> providers = new ArrayList<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : new ArrayList<>(providers);
    }

    public Provider resolveProvider(String name) {
        return providers.stream()
                .filter(provider -> Objects.equals(provider.getName(), name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No LLM provider configured for name=" + name));
    }

    public Provider resolveDefaultProvider() {
        return resolveProvider(defaultProvider);
    }

    public static class Provider {

        private String name = "openai";

        private String baseUrl = "https://api.openai.com/v1";

        private String apiKey = "";

        private List<String> models = new ArrayList<>();

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration readTimeout = Duration.ofSeconds(60);

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
