package com.codepilot.gateway.review;

import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.gateway.github.GitHubCommentWriter;
import com.codepilot.gateway.github.GitHubPullRequestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitHubReviewWorkerContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RedisStreamReviewEventBuffer.class, () -> mock(RedisStreamReviewEventBuffer.class))
            .withBean(ReviewSessionRepository.class, () -> mock(ReviewSessionRepository.class))
            .withBean(GitHubPullRequestClient.class, () -> mock(GitHubPullRequestClient.class))
            .withBean(GitHubCommentWriter.class, () -> mock(GitHubCommentWriter.class))
            .withBean(ReviewSseBroadcaster.class, ReviewSseBroadcaster::new)
            .withBean(LlmClient.class, () -> mock(LlmClient.class))
            .withBean(ProjectMemoryRepository.class, () -> mock(ProjectMemoryRepository.class))
            .withBean(DiffAnalyzer.class, DiffAnalyzer::new)
            .withBean(ContextCompiler.class, () -> mock(ContextCompiler.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TokenCounter.class, TokenCounter::new)
            .withBean(GitHubReviewWorker.class);

    @Test
    void createsReviewWorkerBeanWhenDependenciesArePresent() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(GitHubReviewWorker.class));
    }
}
