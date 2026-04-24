package com.codepilot.gateway.config;

import com.codepilot.core.infrastructure.config.LlmProperties;
import com.codepilot.gateway.CodePilotGatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayApplicationPropertyMappingTest {

    @Test
    void bridgesCodepilotRedisEnvVarsToSpringRedisProperties() {
        try (ConfigurableApplicationContext context = runGatewayContext(Map.of(
                "CODEPILOT_REDIS_URL", "redis://redis.example.com:6380/2",
                "CODEPILOT_REDIS_USERNAME", "gateway-user",
                "CODEPILOT_REDIS_PASSWORD", "gateway-secret"
        ))) {
            assertThat(context.getEnvironment().getProperty("spring.data.redis.url"))
                    .isEqualTo("redis://redis.example.com:6380/2");
            assertThat(context.getEnvironment().getProperty("spring.data.redis.username"))
                    .isEqualTo("gateway-user");
            assertThat(context.getEnvironment().getProperty("spring.data.redis.password"))
                    .isEqualTo("gateway-secret");
        }
    }

    @Test
    void keepsOpenAiCompatibleBaseUrlConfigurableForGateway() {
        try (ConfigurableApplicationContext context = runGatewayContext(Map.of(
                "OPENAI_BASE_URL", "https://proxy.example.com/v1",
                "OPENAI_API_KEY", "proxy-token",
                "CODEPILOT_LLM_DEFAULT_MODEL", "gpt-4.1-mini"
        ))) {
            LlmProperties llmProperties = context.getBean(LlmProperties.class);
            LlmProperties.Provider provider = llmProperties.resolveDefaultProvider();

            assertThat(provider.getBaseUrl()).isEqualTo("https://proxy.example.com/v1");
            assertThat(provider.getApiKey()).isEqualTo("proxy-token");
            assertThat(llmProperties.getDefaultModel()).isEqualTo("gpt-4.1-mini");
        }
    }

    private ConfigurableApplicationContext runGatewayContext(Map<String, String> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("management.health.redis.enabled", "false");
        properties.put("GITHUB_API_TOKEN", "test-token");
        properties.put("GITHUB_WEBHOOK_SECRET", "test-secret");
        properties.put("OPENAI_API_KEY", "test-key");
        properties.putAll(overrides);

        return new SpringApplicationBuilder(CodePilotGatewayApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }
}
