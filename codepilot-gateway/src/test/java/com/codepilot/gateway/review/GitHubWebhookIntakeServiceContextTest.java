package com.codepilot.gateway.review;

import com.codepilot.core.domain.session.ReviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitHubWebhookIntakeServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RedisWebhookDeduplicator.class, () -> mock(RedisWebhookDeduplicator.class))
            .withBean(RedisStreamReviewEventBuffer.class, () -> mock(RedisStreamReviewEventBuffer.class))
            .withBean(ReviewSessionRepository.class, () -> mock(ReviewSessionRepository.class))
            .withBean(ReviewSseBroadcaster.class, ReviewSseBroadcaster::new)
            .withBean(GitHubWebhookIntakeService.class);

    @Test
    void createsWebhookIntakeServiceBeanWhenDependenciesArePresent() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(GitHubWebhookIntakeService.class));
    }
}
