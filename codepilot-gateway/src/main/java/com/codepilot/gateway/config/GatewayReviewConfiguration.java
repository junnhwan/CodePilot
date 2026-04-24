package com.codepilot.gateway.config;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.infrastructure.persistence.inmemory.InMemoryProjectMemoryRepository;
import com.codepilot.core.infrastructure.config.GatewayProperties;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.persistence.inmemory.InMemoryReviewSessionRepository;
import com.codepilot.gateway.github.GitHubCommentWriter;
import com.codepilot.gateway.github.GitHubPullRequestClient;
import com.codepilot.gateway.github.GitHubWebhookVerifier;
import com.codepilot.gateway.review.RedisStreamReviewEventBuffer;
import com.codepilot.gateway.review.RedisWebhookDeduplicator;
import com.codepilot.gateway.review.ReviewMarkdownRenderer;
import com.codepilot.gateway.review.ReviewSseBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GatewayReviewConfiguration {

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    TokenCounter tokenCounter() {
        return new TokenCounter();
    }

    @Bean
    DiffAnalyzer diffAnalyzer() {
        return new DiffAnalyzer();
    }

    @Bean
    ContextCompiler contextCompiler(
            DiffAnalyzer diffAnalyzer,
            TokenCounter tokenCounter,
            ObjectMapper objectMapper
    ) {
        return new DefaultContextCompiler(
                diffAnalyzer,
                new JavaParserAstParser(),
                new ImpactCalculator(),
                tokenCounter,
                new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                new MemoryService(tokenCounter)
        );
    }

    @Bean
    ReviewSessionRepository reviewSessionRepository() {
        return new InMemoryReviewSessionRepository();
    }

    @Bean
    ProjectMemoryRepository projectMemoryRepository() {
        return new InMemoryProjectMemoryRepository();
    }

    @Bean
    ReviewSseBroadcaster reviewSseBroadcaster() {
        return new ReviewSseBroadcaster();
    }

    @Bean
    ReviewMarkdownRenderer reviewMarkdownRenderer() {
        return new ReviewMarkdownRenderer();
    }

    @Bean
    RedisWebhookDeduplicator redisWebhookDeduplicator(StringRedisTemplate redisTemplate) {
        return new RedisWebhookDeduplicator(redisTemplate);
    }

    @Bean
    RedisStreamReviewEventBuffer redisStreamReviewEventBuffer(StringRedisTemplate redisTemplate) {
        return new RedisStreamReviewEventBuffer(redisTemplate, "codepilot:github:review-events");
    }

    @Bean
    GitHubWebhookVerifier gitHubWebhookVerifier(GatewayProperties gatewayProperties) {
        return new GitHubWebhookVerifier(gatewayProperties.getGithub().getWebhookSecret());
    }

    @Bean
    GitHubPullRequestClient gitHubPullRequestClient(RestClient.Builder restClientBuilder, GatewayProperties gatewayProperties) {
        return new GitHubPullRequestClient(
                restClientBuilder,
                gatewayProperties.getGithub().getApiBaseUrl(),
                gatewayProperties.getGithub().getApiToken()
        );
    }

    @Bean
    GitHubCommentWriter gitHubCommentWriter(
            RestClient.Builder restClientBuilder,
            ReviewMarkdownRenderer renderer,
            GatewayProperties gatewayProperties
    ) {
        return new GitHubCommentWriter(
                restClientBuilder,
                renderer,
                gatewayProperties.getGithub().getApiBaseUrl(),
                gatewayProperties.getGithub().getApiToken()
        );
    }
}
