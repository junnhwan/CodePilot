package com.codepilot.core.infrastructure.config;

import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties({
        LlmProperties.class,
        StorageProperties.class,
        GatewayProperties.class
})
public class CodePilotCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WebClient.Builder codePilotWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, LlmProperties llmProperties) {
        return new OpenAiCompatibleLlmClient(webClientBuilder, objectMapper, llmProperties);
    }
}
